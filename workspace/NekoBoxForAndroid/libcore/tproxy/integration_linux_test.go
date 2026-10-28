package tproxy

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	boxService "github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport/fakeip"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/redirect"
	"github.com/sagernet/sing/service"
)

type integrationFactory struct{ client Client }

func (f integrationFactory) OpenTProxySocket(network, address string, reply bool) (int, error) {
	_, fd, err := f.client.Call(Request{Op: "socket", Network: network, Address: address, Reply: reply}, -1)
	return fd, err
}

// Run only in an isolated Docker network namespace, never against the host's
// networking. The fixture authenticates the test UID; production Run rejects
// root as an owner and authenticates the actual Android app UID instead.
func TestKernelTProxyRoundTripAndCleanup(t *testing.T) {
	if os.Getenv("NB4A_TPROXY_KERNEL_TEST") != "1" {
		t.Skip("requires isolated Docker namespace and NET_ADMIN")
	}
	if os.Getuid() != 0 {
		t.Fatal("kernel test requires root inside isolated container")
	}
	for _, tool := range []string{"ip", "iptables", "ip6tables"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Fatal(err)
		}
	}
	dir := t.TempDir()
	listener, err := net.ListenUnix("unixpacket", &net.UnixAddr{Name: filepath.Join(dir, "broker"), Net: "unixpacket"})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	s := &Server{UID: os.Getuid(), Token: strings.Repeat("a", 64), firewall: NewFirewall(10123, filepath.Join(dir, "journal")), listener: listener}
	defer s.closeProbes()
	if err = s.startEcho(); err != nil {
		t.Fatal(err)
	}
	defer s.echoTCP.Close()
	defer s.echoUDP.Close()
	if s.echoTCP6 != nil {
		defer s.echoTCP6.Close()
		defer s.echoUDP6.Close()
	}
	s.firewall.HealthPort = s.healthPort
	go func() {
		for {
			conn, e := listener.AcceptUnix()
			if e != nil {
				return
			}
			go s.handle(conn)
		}
	}()
	client := Client{Path: listener.Addr().String(), Token: s.Token}
	port := func() int {
		l, e := net.Listen("tcp4", "127.0.0.1:0")
		if e != nil {
			t.Fatal(e)
		}
		defer l.Close()
		return l.Addr().(*net.TCPAddr).Port
	}
	c := Config{UID: 10123, Port4: port(), Port6: port(), TetherPort4: port(), TetherPort6: port(), IPv4: true, IPv6: true, AllApps: true}
	if err = s.firewall.Prepare(c); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := s.firewall.Cleanup(); err != nil {
			t.Error(err)
		}
	}()
	inbounds, outbounds, transports := inbound.NewRegistry(), outbound.NewRegistry(), dns.NewTransportRegistry()
	redirect.RegisterTProxy(inbounds)
	direct.RegisterOutbound(outbounds)
	local.RegisterTransport(transports)
	fakeip.RegisterTransport(transports)
	ctx := service.ContextWithDefaultRegistry(box.Context(t.Context(), inbounds, outbounds,
		endpoint.NewRegistry(), transports, boxService.NewRegistry(), certificate.NewRegistry()))
	service.MustRegister[adapter.TProxySocketFactory](ctx, integrationFactory{client})
	raw := fmt.Sprintf(`{"log":{"disabled":true},"dns":{"servers":[{"type":"local","tag":"local"},{"type":"fakeip","tag":"fake","inet4_range":"198.18.0.0/15","inet6_range":"fc00::/18"}],"final":"local"},"inbounds":[{"type":"tproxy","tag":"tproxy-in4","listen":"0.0.0.0","listen_port":%d},{"type":"tproxy","tag":"tproxy-in6","listen":"::","listen_port":%d}],"outbounds":[{"type":"direct","tag":"direct","routing_mark":536870912}],"route":{"final":"direct","rules":[{"ip_cidr":["203.0.113.254/32"],"port":[%d],"override_address":"127.0.0.2","outbound":"direct"},{"ip_cidr":["fdfe:ffff::1/128"],"port":[%d],"override_address":"::1","outbound":"direct"}]}}`, c.Port4, c.Port6, s.healthPort, s.healthPort)
	var opts option.Options
	if err = opts.UnmarshalJSONContext(ctx, []byte(raw)); err != nil {
		t.Fatal(err)
	}
	core, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		t.Fatal(err)
	}
	defer core.Close()
	if err = core.Start(); err != nil {
		t.Fatal(err)
	}
	if err = s.probe(); err != nil {
		for _, command := range [][]string{{"ip", "-4", "rule", "show"}, {"ip", "-4", "route", "get", "203.0.113.254", "mark", "0x40000000"}, {"iptables", "-t", "mangle", "-S"}} {
			output, e := exec.Command(command[0], command[1:]...).CombinedOutput()
			t.Logf("%s: %s %v", strings.Join(command, " "), output, e)
		}
		t.Fatal("prepared core cannot handle transparent traffic:", err)
	}
	var probeAddresses [2]string
	for i := range s.udpProbes {
		probeAddresses[i] = s.udpProbes[i].conn.LocalAddr().String()
	}
	// Count sockets bound to the synthetic health endpoint, not TCP TIME_WAIT
	// entries. A fresh client per probe used to accumulate UDP reply sockets.
	replySockets := func() int {
		t.Helper()
		count := 0
		for _, item := range []struct{ network, address string }{{"udp", "203.0.113.254"}, {"udp6", "fdfe:ffff::1"}} {
			data, err := os.ReadFile("/proc/net/" + item.network)
			if err != nil {
				t.Fatal(err)
			}
			for line := range strings.SplitSeq(string(data), "\n") {
				fields := strings.Fields(line)
				if len(fields) < 2 {
					continue
				}
				endpoint, err := procEndpoint(fields[1])
				if err == nil && endpoint.Addr().String() == item.address && int(endpoint.Port()) == s.healthPort {
					count++
				}
			}
		}
		return count
	}
	initialReplies := replySockets()
	if initialReplies != 2 {
		t.Fatalf("expected one reply socket per family, got %d", initialReplies)
	}
	checkRepeatedProbes := func() {
		t.Helper()
		for range 30 {
			if err := s.probe(); err != nil {
				t.Fatal(err)
			}
			for i := range s.udpProbes {
				if got := s.udpProbes[i].conn.LocalAddr().String(); got != probeAddresses[i] {
					t.Fatalf("probe source changed: %s -> %s", probeAddresses[i], got)
				}
			}
		}
		if count := replySockets(); count != initialReplies {
			t.Fatalf("UDP reply sockets grew from %d to %d", initialReplies, count)
		}
	}
	checkRepeatedProbes()
	if err = s.firewall.Activate(); err != nil {
		t.Fatal(err)
	}
	checkRepeatedProbes()
	// Losing forwarding must still fail the health check, and recovery must
	// reuse the same flow rather than adding another transparent reply socket.
	dropUDP := []string{"-m", "mark", "--mark", "0x40000000/0x40000000", "-p", "udp", "-j", "DROP"}
	if err = s.firewall.rule("iptables", "mangle", append([]string{"-I", s.firewall.prefix() + "_P", "1"}, dropUDP...)...); err != nil {
		t.Fatal(err)
	}
	if err = s.probe(); err == nil || !strings.Contains(err.Error(), "local UDP echo:") {
		t.Fatalf("broken UDP forwarding was not diagnosed: %v", err)
	}
	if err = s.firewall.rule("iptables", "mangle", append([]string{"-D", s.firewall.prefix() + "_P"}, dropUDP...)...); err != nil {
		t.Fatal(err)
	}
	checkRepeatedProbes()
	// A regular app starts on a physical route and receives its capture mark
	// in OUTPUT. Test that rerouting works without pre-marking the socket.
	child := exec.Command("bash", "-c", fmt.Sprintf(`exec 3<>/dev/tcp/203.0.113.254/%d; printf test >&3; IFS= read -r -n 4 -t 2 reply <&3; test "$reply" = test`, s.healthPort))
	child.SysProcAttr = &syscall.SysProcAttr{Credential: &syscall.Credential{Uid: 10124, Gid: 10124}}
	if output, e := child.CombinedOutput(); e != nil {
		t.Fatalf("ordinary app flow was blocked: %s %v", output, e)
	}
	// A forced physical interface must fail rather than fall through to netd.
	bound := net.Dialer{Timeout: time.Second, Control: func(_, _ string, raw syscall.RawConn) error {
		var socketErr error
		err := raw.Control(func(fd uintptr) {
			socketErr = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, CaptureMark)
			if socketErr == nil {
				socketErr = unix.SetsockoptString(int(fd), unix.SOL_SOCKET, unix.SO_BINDTODEVICE, "eth0")
			}
		})
		if err != nil {
			return err
		}
		return socketErr
	}}
	if conn, e := bound.Dial("udp4", "203.0.113.254:18081"); e == nil {
		_, writeErr := conn.Write([]byte("bound"))
		_ = conn.Close()
		if writeErr == nil {
			output, e := exec.Command("iptables", "-t", "mangle", "-nvxL", s.firewall.prefix()+"_E").CombinedOutput()
			dropped := false
			for _, line := range strings.Split(string(output), "\n") {
				fields := strings.Fields(line)
				if len(fields) > 2 && fields[2] == "DROP" {
					packets, _ := strconv.Atoi(fields[0])
					dropped = packets > 0
				}
			}
			if e != nil || !dropped {
				t.Fatalf("forced physical interface escaped capture policy: %s %v", output, e)
			}
		}
	}
	// A real UDP socket's connected tuple must resolve to its UID.
	udp, err := net.Dial("udp4", net.JoinHostPort("127.0.0.2", strconv.Itoa(s.healthPort)))
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	uid, err := ResolveOwner(17, udp.LocalAddr().String(), udp.RemoteAddr().String())
	if err != nil || uid != os.Getuid() {
		t.Fatalf("UDP owner=%d error=%v", uid, err)
	}
	if err = core.Close(); err != nil {
		t.Fatal(err)
	}
	if err = s.probe(); err == nil {
		t.Fatal("stopped core passed its health check")
	}
	if err = s.firewall.Cleanup(); err != nil {
		t.Fatal(err)
	}
	s.closeProbes()
	// Capture cleanup restores direct TCP and UDP connectivity.
	for _, network := range []string{"tcp4", "udp4"} {
		conn, e := net.Dial(network, net.JoinHostPort("127.0.0.2", strconv.Itoa(s.healthPort)))
		if e != nil {
			t.Fatal(e)
		}
		_, e = conn.Write([]byte{42})
		if e != nil {
			t.Fatal(e)
		}
		data := make([]byte, 1)
		_, e = conn.Read(data)
		_ = conn.Close()
		if e != nil || data[0] != 42 {
			t.Fatalf("direct connectivity not restored: %v", e)
		}
	}
}
