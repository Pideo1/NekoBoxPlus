package tproxy

import (
	"bytes"
	"errors"
	"net"
	"strings"
	"testing"
	"time"
)

func probeEcho(t *testing.T, network string, reply func([]byte) [][]byte) net.Conn {
	t.Helper()
	address := "127.0.0.1:0"
	if network == "udp6" {
		address = "[::1]:0"
	}
	server, err := net.ListenPacket(network, address)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		data := make([]byte, 128)
		for {
			n, addr, err := server.ReadFrom(data)
			if err != nil {
				return
			}
			for _, payload := range reply(bytes.Clone(data[:n])) {
				if _, err := server.WriteTo(payload, addr); err != nil {
					return
				}
			}
		}
	}()
	t.Cleanup(func() {
		_ = server.Close()
		<-done
	})
	conn, err := net.Dial(network, server.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

func TestUDPProbeRetainsFlowAcrossTimeoutAndIgnoresLateReply(t *testing.T) {
	for _, network := range []string{"udp4", "udp6"} {
		t.Run(network, func(t *testing.T) {
			var stale []byte
			conn := probeEcho(t, network, func(payload []byte) [][]byte {
				if stale == nil {
					stale = payload
					return nil
				}
				return [][]byte{stale, payload}
			})
			p := udpProbe{conn: conn}
			err := p.check(network, conn.RemoteAddr().String())
			if timeout, ok := errors.AsType[net.Error](err); !ok || !timeout.Timeout() {
				t.Fatalf("expected timeout, got %v", err)
			}
			if p.conn != conn {
				t.Fatal("timeout discarded the reusable flow")
			}
			for range 30 {
				if err := p.check(network, conn.RemoteAddr().String()); err != nil {
					t.Fatal(err)
				}
				if p.conn != conn {
					t.Fatal("probe replaced its socket")
				}
			}
			p.close()
			p.close()
			if p.conn != nil {
				t.Fatal("closed probe retained its socket")
			}
			if _, err := conn.Write([]byte("closed")); !errors.Is(err, net.ErrClosed) {
				t.Fatalf("socket still open: %v", err)
			}
		})
	}
}

func TestUDPProbeRejectsInvalidRepliesWithinDeadline(t *testing.T) {
	for _, kind := range []string{"short", "oversized", "wrong nonce"} {
		t.Run(kind, func(t *testing.T) {
			conn := probeEcho(t, "udp4", func(payload []byte) [][]byte {
				switch kind {
				case "short":
					payload = payload[:len(payload)-1]
				case "oversized":
					payload = append(payload, 0)
				case "wrong nonce":
					payload[0] ^= 1
				}
				return [][]byte{payload}
			})
			err := exchangeChallenge(conn, true, 100*time.Millisecond)
			if err == nil || !strings.Contains(err.Error(), "invalid or stale") {
				t.Fatalf("invalid reply accepted or not diagnosed: %v", err)
			}
			if timeout, ok := errors.AsType[net.Error](err); !ok || !timeout.Timeout() {
				t.Fatalf("invalid reply lost deadline error: %v", err)
			}
		})
	}
}

func TestUDPProbeDiscardsUnusableSocket(t *testing.T) {
	conn := probeEcho(t, "udp4", func(payload []byte) [][]byte { return [][]byte{payload} })
	_ = conn.Close()
	p := udpProbe{conn: conn}
	if err := p.check("udp4", conn.RemoteAddr().String()); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("expected closed socket error, got %v", err)
	}
	if p.conn != nil {
		t.Fatal("unusable socket retained")
	}
}

func TestCloseProbesReleasesBothFamilies(t *testing.T) {
	s := new(Server)
	for i, network := range []string{"udp4", "udp6"} {
		s.udpProbes[i].conn = probeEcho(t, network, func(payload []byte) [][]byte { return [][]byte{payload} })
	}
	s.closeProbes()
	for _, probe := range s.udpProbes {
		if probe.conn != nil {
			t.Fatal("probe socket retained")
		}
	}
}

func TestLifecycleRequestsCloseProbeSockets(t *testing.T) {
	for _, operation := range []string{"prepare", "invalid config", "cleanup", "shutdown"} {
		t.Run(operation, func(t *testing.T) {
			firewall, _, config := fixture(t)
			s := &Server{firewall: firewall}
			conn := probeEcho(t, "udp4", func(payload []byte) [][]byte { return [][]byte{payload} })
			s.udpProbes[0].conn = conn
			request := Request{Op: operation}
			if operation == "prepare" || operation == "invalid config" {
				request.Op, request.Config = "prepare", &config
				if operation == "invalid config" {
					config.UID = -1
				}
			}
			response, _ := s.dispatch(request, -1, "")
			if (response.Error != "") != (operation == "invalid config") {
				t.Fatalf("unexpected lifecycle result: %s", response.Error)
			}
			if s.udpProbes[0].conn != nil {
				t.Fatal("lifecycle request retained a probe")
			}
			if _, err := conn.Write([]byte("closed")); !errors.Is(err, net.ErrClosed) {
				t.Fatalf("lifecycle request did not close socket: %v", err)
			}
		})
	}
}

type failingEchoConn struct {
	net.PacketConn
	writeFailed chan struct{}
	readAgain   chan struct{}
}

func (c *failingEchoConn) WriteTo([]byte, net.Addr) (int, error) {
	close(c.writeFailed)
	return 0, errors.New("injected write failure")
}

func (c *failingEchoConn) ReadFrom(data []byte) (int, net.Addr, error) {
	select {
	case <-c.writeFailed:
		close(c.readAgain)
	default:
	}
	return c.PacketConn.ReadFrom(data)
}

func TestEchoLoopReportsWriteFailures(t *testing.T) {
	tcp, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer tcp.Close()
	udp, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	stats := new(echoStats)
	conn := &failingEchoConn{PacketConn: udp, writeFailed: make(chan struct{}), readAgain: make(chan struct{})}
	startEchoLoops(tcp, conn, stats)
	client, err := net.Dial("udp4", udp.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if _, err := client.Write([]byte("echo")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-conn.readAgain:
	case <-time.After(time.Second):
		t.Fatal("echo loop did not continue after write failure")
	}
	if got := stats.snapshot(); got != `received=1 sent=0 errors=1 last_error="injected write failure"` {
		t.Fatal(got)
	}
}
