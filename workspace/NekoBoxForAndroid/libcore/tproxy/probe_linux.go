package tproxy

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

const probeTimeout = 400 * time.Millisecond

type udpProbe struct {
	conn net.Conn
}

func (p *udpProbe) close() {
	if p.conn != nil {
		_ = p.conn.Close()
		p.conn = nil
	}
}

func (p *udpProbe) check(network, address string) error {
	if p.conn == nil {
		conn, err := dialChallenge(network, address)
		if err != nil {
			return err
		}
		p.conn = conn
	}
	err := exchangeChallenge(p.conn, true, probeTimeout)
	if err != nil {
		// A read deadline does not invalidate the socket or its NAT mapping.
		// Retain the flow so a temporary lost reply cannot cause socket churn.
		if timeout, ok := errors.AsType[net.Error](err); !ok || !timeout.Timeout() {
			p.close()
		}
		return fmt.Errorf("%s core probe: %w", network, err)
	}
	return nil
}

func dialChallenge(network, address string) (net.Conn, error) {
	dialer := net.Dialer{Timeout: probeTimeout, Control: func(_, _ string, raw syscall.RawConn) error {
		var sockErr error
		err := raw.Control(func(fd uintptr) { sockErr = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, CaptureMark) })
		return errors.Join(err, sockErr)
	}}
	// A policy route of type local otherwise selects the unassigned synthetic
	// destination as the source. The challenge must originate on loopback.
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
		return nil, fmt.Errorf("%s core probe dial: %w", network, err)
	}
	return conn, nil
}

func challenge(network, address string) error {
	conn, err := dialChallenge(network, address)
	if err != nil {
		return err
	}
	defer conn.Close()
	if err = exchangeChallenge(conn, false, probeTimeout); err != nil {
		return fmt.Errorf("%s core probe: %w", network, err)
	}
	return nil
}

func exchangeChallenge(conn net.Conn, datagram bool, timeout time.Duration) error {
	if err := conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		return fmt.Errorf("deadline: %w", err)
	}
	nonce := make([]byte, 32)
	if _, err := rand.Read(nonce); err != nil {
		return fmt.Errorf("nonce: %w", err)
	}
	payload := []byte(hex.EncodeToString(nonce))
	n, err := conn.Write(payload)
	if err != nil {
		return fmt.Errorf("write: %w", err)
	}
	if n != len(payload) {
		return fmt.Errorf("write: %w", io.ErrShortWrite)
	}
	if !datagram {
		reply := make([]byte, len(payload))
		if _, err = io.ReadFull(conn, reply); err != nil {
			return fmt.Errorf("read: %w", err)
		}
		if subtle.ConstantTimeCompare(payload, reply) != 1 {
			return errors.New("invalid core probe reply")
		}
		return nil
	}
	// An extra byte detects oversized datagrams instead of accepting a valid
	// prefix. Old replies may remain queued after an earlier read timed out.
	reply := make([]byte, len(payload)+1)
	invalid := false
	for {
		n, err = conn.Read(reply)
		if err != nil {
			if invalid {
				err = errors.Join(err, errors.New("invalid or stale core probe reply"))
			}
			return fmt.Errorf("read: %w", err)
		}
		if n == len(payload) && subtle.ConstantTimeCompare(payload, reply[:n]) == 1 {
			return nil
		}
		invalid = true
	}
}

// Echo loops cannot use the lifecycle lock: probes hold it while awaiting the
// echo. Keep diagnostics separate and only expose them when a UDP probe fails.
type echoStats struct {
	mu                     sync.Mutex
	received, sent, errors uint64
	lastError              string
}

func (s *echoStats) record(received, sent bool, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if received {
		s.received++
	}
	if sent {
		s.sent++
	}
	if err != nil {
		s.errors++
		s.lastError = err.Error()
	}
}

func (s *echoStats) snapshot() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return fmt.Sprintf("received=%d sent=%d errors=%d last_error=%q", s.received, s.sent, s.errors, s.lastError)
}
