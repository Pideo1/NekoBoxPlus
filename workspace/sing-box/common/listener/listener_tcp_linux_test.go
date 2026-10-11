//go:build linux

package listener

import (
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/service"

	"golang.org/x/sys/unix"
)

type testTProxySocketFactory struct{}

func (testTProxySocketFactory) OpenTProxySocket(network, address string, reply bool) (int, error) {
	listener, err := net.Listen(network, address)
	if err != nil {
		return -1, err
	}
	defer listener.Close()
	file, err := listener.(*net.TCPListener).File()
	if err != nil {
		return -1, err
	}
	defer file.Close()
	return unix.Dup(int(file.Fd()))
}

func TestListenTCPKeepAlive(t *testing.T) {
	for _, brokered := range []bool{false, true} {
		name := "native"
		if brokered {
			name = "brokered"
		}
		t.Run(name, func(t *testing.T) {
			for _, test := range []struct {
				name     string
				options  option.ListenOptions
				enabled  int
				idle     int
				interval int
			}{
				{name: "defaults", enabled: 1, idle: 300, interval: 75},
				{
					name: "disabled",
					options: option.ListenOptions{
						DisableTCPKeepAlive:  true,
						TCPKeepAlive:         badoption.Duration(42 * time.Second),
						TCPKeepAliveInterval: badoption.Duration(9 * time.Second),
					},
				},
				{
					name: "custom",
					options: option.ListenOptions{
						TCPKeepAlive:         badoption.Duration(42 * time.Second),
						TCPKeepAliveInterval: badoption.Duration(9 * time.Second),
					},
					enabled: 1, idle: 42, interval: 9,
				},
				{
					name:    "custom_idle",
					options: option.ListenOptions{TCPKeepAlive: badoption.Duration(42 * time.Second)},
					enabled: 1, idle: 42, interval: 75,
				},
				{
					name:    "custom_interval",
					options: option.ListenOptions{TCPKeepAliveInterval: badoption.Duration(9 * time.Second)},
					enabled: 1, idle: 300, interval: 9,
				},
			} {
				t.Run(test.name, func(t *testing.T) {
					ctx := t.Context()
					if brokered {
						ctx = service.ContextWith[adapter.TProxySocketFactory](ctx, testTProxySocketFactory{})
					}
					l := New(Options{Context: ctx, Logger: logger.NOP(), Listen: test.options, TProxy: brokered})
					listener, err := l.ListenTCP()
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() { l.Close() })
					if l.TCPListener() != listener {
						t.Fatal("stored listener differs from returned listener")
					}
					// Bound both dial and accept so failures cannot hang the test.
					if err := listener.(interface{ SetDeadline(time.Time) error }).SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
						t.Fatal(err)
					}
					client, err := net.DialTimeout("tcp", listener.Addr().String(), 5*time.Second)
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() { client.Close() })
					conn, err := listener.Accept()
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() { conn.Close() })
					rawConn, err := conn.(*net.TCPConn).SyscallConn()
					if err != nil {
						t.Fatal(err)
					}
					if err := rawConn.Control(func(fd uintptr) {
						assertSocketOption(t, int(fd), unix.SOL_SOCKET, unix.SO_KEEPALIVE, test.enabled)
						if test.enabled != 0 {
							assertSocketOption(t, int(fd), unix.IPPROTO_TCP, unix.TCP_KEEPIDLE, test.idle)
							assertSocketOption(t, int(fd), unix.IPPROTO_TCP, unix.TCP_KEEPINTVL, test.interval)
						}
					}); err != nil {
						t.Fatal(err)
					}
				})
			}
		})
	}
}

func assertSocketOption(t *testing.T, fd, level, option, want int) {
	t.Helper()
	got, err := unix.GetsockoptInt(fd, level, option)
	if err != nil {
		t.Error(err)
	} else if got != want {
		t.Errorf("socket option %d: got %d, want %d", option, got, want)
	}
}
