package tproxy

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

type Server struct {
	UID, Parent              int
	Path, Token, JournalPath string
	mu                       sync.Mutex
	active, prepared         bool
	lastHealthy              time.Time
	failed                   string
	firewall                 *Firewall
	healthPort               int
	echoTCP                  net.Listener
	echoUDP                  net.PacketConn
	echoTCP6                 net.Listener
	echoUDP6                 net.PacketConn
	downstreams              []string
	listener                 *net.UnixListener
}

func processIdentity(pid int) (string, error) {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return "", err
	}
	_, tail, ok := strings.Cut(string(data), ") ")
	fields := strings.Fields(tail)
	if !ok || len(fields) < 20 {
		return "", errors.New("invalid process identity")
	}
	return fields[19], nil
}

func (s *Server) Run() (err error) {
	if os.Getuid() != 0 || s.UID < 10000 || s.Parent <= 0 || len(s.Token) < 32 {
		return errors.New("invalid root broker identity")
	}
	identity, err := processIdentity(s.Parent)
	if err != nil {
		return err
	}
	s.firewall = NewFirewall(s.UID, s.JournalPath)
	s.firewall.Generation = s.Token
	if err = s.firewall.Cleanup(); err != nil {
		return err
	}
	defer func() { s.mu.Lock(); defer s.mu.Unlock(); err = errors.Join(err, s.firewall.Cleanup()) }()
	_ = os.Remove(s.Path)
	listener, err := net.ListenUnix("unixpacket", &net.UnixAddr{Name: s.Path, Net: "unixpacket"})
	if err != nil {
		return err
	}
	listener.SetUnlinkOnClose(false)
	defer listener.Close()
	s.listener = listener
	if err = os.Chown(s.Path, s.UID, s.UID); err != nil {
		return err
	}
	if err = os.Chmod(s.Path, 0600); err != nil {
		return err
	}
	if err = s.startEcho(); err != nil {
		return err
	}
	s.firewall.HealthPort = s.healthPort
	defer s.echoTCP.Close()
	defer s.echoUDP.Close()
	if s.echoTCP6 != nil {
		defer s.echoTCP6.Close()
		defer s.echoUDP6.Close()
	}
	quit := make(chan struct{})
	defer close(quit)
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-quit:
				return
			case <-ticker.C:
			}
			current, e := processIdentity(s.Parent)
			if e != nil || current != identity {
				_ = listener.Close()
				return
			}
			s.mu.Lock()
			if s.active {
				probeErr := s.probe()
				if probeErr == nil && s.firewall.state.Config.Tether {
					probeErr = s.firewall.verifyOffload()
					if probeErr == nil {
						var interfaces []string
						interfaces, probeErr = s.firewall.downstreams()
						if probeErr == nil && strings.Join(interfaces, ",") != strings.Join(s.downstreams, ",") {
							probeErr = errors.New("tether interfaces changed")
						}
					}
				}
				if probeErr == nil {
					s.lastHealthy = time.Now()
				} else if time.Since(s.lastHealthy) >= 5*time.Second {
					s.failed = probeErr.Error()
					if e = s.firewall.Cleanup(); e != nil {
						s.failed += "; cleanup: " + e.Error()
					}
					s.active, s.prepared = false, false
				}
			}
			s.mu.Unlock()
		}
	}()
	for {
		conn, e := listener.AcceptUnix()
		if e != nil {
			if errors.Is(e, net.ErrClosed) {
				return nil
			}
			return e
		}
		go s.handle(conn)
	}
}

func (s *Server) handle(conn *net.UnixConn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	raw, err := conn.SyscallConn()
	if err != nil {
		return
	}
	var peer *unix.Ucred
	if err = raw.Control(func(fd uintptr) { peer, err = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED) }); err != nil || peer == nil || int(peer.Uid) != s.UID {
		return
	}
	var label string
	var labelErr error
	if _, e := os.Stat("/sys/fs/selinux/enforce"); e == nil {
		if e = raw.Control(func(fd uintptr) {
			label, labelErr = unix.GetsockoptString(int(fd), unix.SOL_SOCKET, unix.SO_PEERSEC)
		}); e != nil || labelErr != nil || label == "" {
			return
		}
	}
	data, oob := make([]byte, maxMessage), make([]byte, maxAncillary)
	n, on, flags, _, err := conn.ReadMsgUnix(data, oob)
	if err != nil {
		return
	}
	fd, err := receivedFD(oob[:on])
	if fd >= 0 {
		defer unix.Close(fd)
	}
	var request Request
	if err != nil || flags&(unix.MSG_TRUNC|unix.MSG_CTRUNC) != 0 || json.Unmarshal(data[:n], &request) != nil {
		return
	}
	if request.Version != Version || subtle.ConstantTimeCompare([]byte(request.Token), []byte(s.Token)) != 1 {
		return
	}
	if request.Op == "prepare" || request.Op == "activate" || request.Op == "cleanup" || request.Op == "shutdown" {
		_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	}
	response, outFD := s.dispatch(request, fd, label)
	if outFD >= 0 {
		defer unix.Close(outFD)
	}
	data, err = json.Marshal(response)
	if err != nil {
		return
	}
	var rights []byte
	if outFD >= 0 {
		rights = unix.UnixRights(outFD)
	}
	_, _, _ = conn.WriteMsgUnix(data, rights, nil)
	if request.Op == "shutdown" && response.Error == "" {
		_ = s.listener.Close()
	}
}

func (s *Server) dispatch(r Request, fd int, label string) (response Response, outFD int) {
	response.UID = -1
	response.HealthPort = s.healthPort
	outFD = -1
	var err error
	defer func() {
		if err != nil {
			response.Error = err.Error()
		}
	}()
	// These operations are needed by the core while readiness probes hold the
	// lifecycle lock. Never serialize FD/owner RPCs behind firewall operations.
	switch r.Op {
	case "protect":
		if fd < 0 {
			err = errors.New("missing socket descriptor")
		} else {
			err = protectFD(fd)
		}
		return
	case "socket":
		if fd >= 0 {
			err = errors.New("unexpected descriptor")
		} else {
			outFD, err = transparentSocketForPeer(r.Network, r.Address, r.Reply, label)
		}
		return
	case "owner":
		response.UID, err = ResolveOwner(r.Protocol, r.Source, r.Destination)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	response.Active = s.active
	switch r.Op {
	case "status":
		response.Error = s.failed
	case "prepare":
		if r.Config == nil || s.active {
			err = errors.New("invalid prepare state")
		} else {
			err = s.firewall.Prepare(*r.Config)
			s.prepared = err == nil
			s.failed = ""
		}
	case "activate":
		if !s.prepared || s.active {
			err = errors.New("capture is not prepared")
			break
		}
		if err = s.probe(); err == nil {
			err = s.firewall.Activate()
		}
		if err == nil && s.firewall.state.Config.Tether {
			s.downstreams, err = s.firewall.downstreams()
		}
		if err != nil {
			err = errors.Join(err, s.firewall.Cleanup())
			s.prepared = false
		} else {
			s.active = true
			s.lastHealthy = time.Now()
			response.Active = true
		}
	case "cleanup", "shutdown":
		err = s.firewall.Cleanup()
		s.active, s.prepared = false, false
		response.Active = false
	default:
		err = errors.New("unsupported broker operation")
	}
	return response, outFD
}

func (s *Server) startEcho() error {
	tcp, err := net.Listen("tcp4", "127.0.0.2:0")
	if err != nil {
		return err
	}
	s.echoTCP = tcp
	s.healthPort = tcp.Addr().(*net.TCPAddr).Port
	udp, err := net.ListenPacket("udp4", net.JoinHostPort("127.0.0.2", strconv.Itoa(s.healthPort)))
	if err != nil {
		_ = tcp.Close()
		return err
	}
	s.echoUDP = udp
	startEchoLoops(tcp, udp)
	tcp6, e := net.Listen("tcp6", net.JoinHostPort("::1", strconv.Itoa(s.healthPort)))
	if e == nil {
		udp6, e := net.ListenPacket("udp6", net.JoinHostPort("::1", strconv.Itoa(s.healthPort)))
		if e != nil {
			_ = tcp6.Close()
		} else {
			s.echoTCP6, s.echoUDP6 = tcp6, udp6
			startEchoLoops(tcp6, udp6)
		}
	}
	return nil
}

func startEchoLoops(tcp net.Listener, udp net.PacketConn) {
	go func() {
		for {
			conn, e := tcp.Accept()
			if e != nil {
				return
			}
			go func() {
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(time.Second))
				_, _ = io.Copy(conn, io.LimitReader(conn, 64))
			}()
		}
	}()
	go func() {
		data := make([]byte, 64)
		for {
			n, addr, e := udp.ReadFrom(data)
			if e != nil {
				return
			}
			_, _ = udp.WriteTo(data[:n], addr)
		}
	}()
}

func (s *Server) probe() error {
	c := s.firewall.state.Config
	// Probe the real device listener through marked loopback delivery. A special
	// root-only route in the core sends this challenge to the local echo endpoint.
	if c.IPv4 {
		address := net.JoinHostPort("203.0.113.254", strconv.Itoa(s.healthPort))
		for _, network := range []string{"tcp4", "udp4"} {
			if err := challenge(network, address); err != nil {
				return err
			}
		}
	}
	if c.IPv6 {
		if s.echoTCP6 == nil {
			return errors.New("IPv6 echo endpoint unavailable")
		}
		address := net.JoinHostPort("fdfe:ffff::1", strconv.Itoa(s.healthPort))
		for _, network := range []string{"tcp6", "udp6"} {
			if err := challenge(network, address); err != nil {
				return err
			}
		}
	}
	return nil
}

func challenge(network, address string) error {
	dialer := net.Dialer{Timeout: 400 * time.Millisecond, Control: func(_, _ string, raw syscall.RawConn) error {
		var sockErr error
		err := raw.Control(func(fd uintptr) { sockErr = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, CaptureMark) })
		return errors.Join(err, sockErr)
	}}
	// A policy route of type local otherwise selects the synthetic destination
	// itself as the source, which is not an assigned address on this device.
	ip := net.IPv4(127, 0, 0, 1)
	if strings.HasSuffix(network, "6") {
		ip = net.IPv6loopback
	}
	if strings.HasPrefix(network, "tcp") {
		dialer.LocalAddr = &net.TCPAddr{IP: ip}
	} else {
		dialer.LocalAddr = &net.UDPAddr{IP: ip}
	}
	conn, err := dialer.Dial(network, address)
	if err != nil {
		return fmt.Errorf("%s core probe: %w", network, err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(400 * time.Millisecond))
	nonce := make([]byte, 32)
	if _, err = rand.Read(nonce); err != nil {
		return err
	}
	challenge := []byte(hex.EncodeToString(nonce))
	if _, err = conn.Write(challenge); err != nil {
		return err
	}
	reply := make([]byte, len(challenge))
	if strings.HasPrefix(network, "udp") {
		var n int
		n, err = conn.Read(reply)
		if err == nil && n != len(reply) {
			err = io.ErrUnexpectedEOF
		}
	} else {
		_, err = io.ReadFull(conn, reply)
	}
	if err != nil {
		return fmt.Errorf("%s core probe: %w", network, err)
	}
	if subtle.ConstantTimeCompare(challenge, reply) != 1 {
		return errors.New("invalid core probe reply")
	}
	return nil
}
