package shadowsocks

import (
	"context"
	"net"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/stretchr/testify/require"
)

func TestStreamNetworkCompatibility(t *testing.T) {
	for _, test := range []struct {
		name      string
		tls       bool
		transport string
		network   option.NetworkList
		uot, mux  bool
		plugin    string
		wantError bool
		networks  []string
	}{
		{name: "plain", networks: []string{"tcp", "udp"}},
		{name: "plain-udp", network: "udp", networks: []string{"udp"}},
		{name: "disabled-tls", networks: []string{"tcp", "udp"}},
		{name: "tls", tls: true, networks: []string{"tcp"}},
		{name: "ws", transport: "ws", networks: []string{"tcp"}},
		{name: "grpc", transport: "grpc", networks: []string{"tcp"}},
		{name: "httpupgrade", transport: "httpupgrade", networks: []string{"tcp"}},
		{name: "xhttp", transport: "xhttp", networks: []string{"tcp"}},
		{name: "tls-udp", tls: true, network: "udp", wantError: true},
		{name: "ws-udp", transport: "ws", network: "tcp\nudp", wantError: true},
		{name: "tls-uot", tls: true, uot: true, networks: []string{"tcp", "udp"}},
		{name: "ws-mux", transport: "ws", mux: true, networks: []string{"tcp", "udp"}},
		{name: "tls-plugin", tls: true, plugin: "obfs-local", wantError: true},
		{name: "ws-plugin", transport: "ws", plugin: "v2ray-plugin", wantError: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			options := option.ShadowsocksOutboundOptions{ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: 12345}, Method: "aes-128-gcm", Password: "test-password", Network: test.network, Plugin: test.plugin}
			options.TLS = &option.OutboundTLSOptions{Enabled: test.tls, Insecure: true}
			if test.transport != "" {
				options.Transport = &option.V2RayTransportOptions{Type: test.transport}
			}
			if test.uot {
				options.UDPOverTCP = &option.UDPOverTCPOptions{Enabled: true}
			}
			if test.mux {
				options.Multiplex = &option.OutboundMultiplexOptions{Enabled: true}
			}
			logger := log.NewNOPFactory().NewLogger("test")
			created, err := NewOutbound(t.Context(), nil, logger, "test", options)
			if test.wantError {
				require.Error(t, err)
				return
			}
			require.NoError(t, err)
			h := created.(*Outbound)
			scope := adapter.NewScope(t.Context(), logger)
			require.NoError(t, h.Start(adapter.StartStateInitialize, scope))
			t.Cleanup(func() { require.NoError(t, scope.Close()) })
			require.Equal(t, test.networks, h.Network())
			if h.streamOnly && !test.uot && !test.mux {
				_, err = h.DialContext(t.Context(), "udp", M.ParseSocksaddr("127.0.0.1:53"))
				require.ErrorIs(t, err, errStreamUDP)
				_, err = h.ListenPacket(t.Context(), M.ParseSocksaddr("127.0.0.1:53"))
				require.ErrorIs(t, err, errStreamUDP)
			}
		})
	}
}

type trackedTransport struct{ closed, reset, idle, keep bool }

func (h *trackedTransport) DialContext(context.Context) (net.Conn, error) { return nil, nil }
func (h *trackedTransport) Close() error                                  { h.closed = true; return nil }
func (h *trackedTransport) Reset() error                                  { h.reset = true; return nil }
func (h *trackedTransport) SetKeepIdleConnections(keep bool)              { h.keep = keep }
func (h *trackedTransport) CloseIdleConnections()                         { h.idle = true }
func TestTransportLifecycle(t *testing.T) {
	transport := &trackedTransport{}
	logger := log.NewNOPFactory().NewLogger("test")
	h := &Outbound{logger: logger, transport: transport}
	scope := adapter.NewScope(t.Context(), logger)
	require.NoError(t, h.Start(adapter.StartStateInitialize, scope))
	h.SetKeepIdleConnections(true)
	h.CloseIdleConnections()
	h.InterfaceUpdated(t.Context())
	require.True(t, transport.keep)
	require.True(t, transport.idle)
	require.True(t, transport.reset)
	require.False(t, transport.closed)
	require.NoError(t, scope.Close())
	require.True(t, transport.closed)
}
