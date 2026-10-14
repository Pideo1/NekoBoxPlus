package shadowsocks

import (
	"context"
	"net"
	"slices"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/mux"
	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/sip003"
	"github.com/sagernet/sing-box/transport/v2ray"
	"github.com/sagernet/sing-shadowsocks2"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.ShadowsocksOutboundOptions](registry, C.TypeShadowsocks, NewOutbound)
}

var (
	_ adapter.OutboundWithMultiplex   = (*Outbound)(nil)
	_ adapter.InterfaceUpdateListener = (*Outbound)(nil)
	_ adapter.IdleConnectionKeeper    = (*Outbound)(nil)
	_ adapter.Lifecycle               = (*Outbound)(nil)
)

// Native UDP cannot be carried over a stream transport.
var errStreamUDP = E.New("native UDP is not available over tls or transport, enable udp_over_tcp or multiplex")

type Outbound struct {
	outbound.Adapter
	logger          logger.ContextLogger
	dialer          N.Dialer
	method          shadowsocks.Method
	serverAddr      M.Socksaddr
	plugin          sip003.Plugin
	tlsConfig       tls.Config
	tlsDialer       tls.Dialer
	transport       adapter.V2RayClientTransport
	streamOnly      bool
	uotClient       *uot.Client
	multiplexDialer *mux.Client
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.ShadowsocksOutboundOptions) (adapter.Outbound, error) {
	uotOptions := common.PtrValueOrDefault(options.UDPOverTCP)
	networks := options.Network.Build()
	streamOnly := (options.Transport != nil && options.Transport.Type != "") || (options.TLS != nil && options.TLS.Enabled)
	if streamOnly {
		if options.Plugin != "" {
			return nil, E.New("plugin cannot be combined with tls or transport")
		}
		if !uotOptions.Enabled && !common.PtrValueOrDefault(options.Multiplex).Enabled {
			// Native Shadowsocks UDP goes through a separate UDP socket and
			// cannot ride on a TLS or V2Ray transport stream. Accept TCP only,
			// unless UDP over TCP or multiplex carries the packets.
			if options.Network != "" && slices.Contains(networks, N.NetworkUDP) {
				return nil, E.New("udp requires udp_over_tcp or multiplex when tls or transport is used")
			}
			networks = []string{N.NetworkTCP}
		}
	}
	method, err := shadowsocks.CreateMethod(ctx, options.Method, shadowsocks.MethodOptions{
		Password: options.Password,
	})
	if err != nil {
		return nil, err
	}
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	outbound := &Outbound{
		Adapter:    outbound.NewAdapterWithDialerOptions(C.TypeShadowsocks, tag, networks, options.DialerOptions),
		logger:     logger,
		dialer:     outboundDialer,
		method:     method,
		serverAddr: options.ServerOptions.Build(),
		streamOnly: streamOnly,
	}
	if options.Plugin != "" {
		outbound.plugin, err = sip003.CreatePlugin(ctx, options.Plugin, options.PluginOptions, router, outbound.dialer, outbound.serverAddr)
		if err != nil {
			return nil, err
		}
	}
	if options.TLS != nil {
		outbound.tlsConfig, err = tls.NewClientWithOptions(tls.ClientOptions{
			Context:       ctx,
			Logger:        logger,
			ServerAddress: options.Server,
			Options:       common.PtrValueOrDefault(options.TLS),
		})
		if err != nil {
			return nil, err
		}
		if outbound.tlsConfig != nil {
			outbound.tlsDialer = tls.NewDialer(outboundDialer, outbound.tlsConfig)
		}
	}
	if options.Transport != nil {
		outbound.transport, err = v2ray.NewClientTransport(ctx, logger, outbound.dialer, outbound.serverAddr, common.PtrValueOrDefault(options.Transport), outbound.tlsConfig)
		if err != nil {
			return nil, E.Cause(err, "create client transport: ", options.Transport.Type)
		}
	}
	if !uotOptions.Enabled {
		outbound.multiplexDialer, err = mux.NewClientWithOptions((*shadowsocksDialer)(outbound), logger, common.PtrValueOrDefault(options.Multiplex))
		if err != nil {
			common.Close(outbound.transport)
			return nil, err
		}
	}
	if uotOptions.Enabled {
		outbound.uotClient = &uot.Client{
			Dialer:  (*shadowsocksDialer)(outbound),
			Version: uotOptions.Version,
		}
	}
	return outbound, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	if h.multiplexDialer == nil {
		switch N.NetworkName(network) {
		case N.NetworkTCP:
			h.logger.InfoContext(ctx, "outbound connection to ", destination)
		case N.NetworkUDP:
			if h.uotClient != nil {
				h.logger.InfoContext(ctx, "outbound UoT connect packet connection to ", destination)
				return h.uotClient.DialContext(ctx, network, destination)
			} else {
				h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
			}
		}
		return (*shadowsocksDialer)(h).DialContext(ctx, network, destination)
	} else {
		switch N.NetworkName(network) {
		case N.NetworkTCP:
			h.logger.InfoContext(ctx, "outbound multiplex connection to ", destination)
		case N.NetworkUDP:
			h.logger.InfoContext(ctx, "outbound multiplex packet connection to ", destination)
		}
		return h.multiplexDialer.DialContext(ctx, network, destination)
	}
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	if h.multiplexDialer == nil {
		if h.uotClient != nil {
			h.logger.InfoContext(ctx, "outbound UoT packet connection to ", destination)
			return h.uotClient.ListenPacket(ctx, destination)
		} else {
			h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
		}
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
		return (*shadowsocksDialer)(h).ListenPacket(ctx, destination)
	} else {
		h.logger.InfoContext(ctx, "outbound multiplex packet connection to ", destination)
		return h.multiplexDialer.ListenPacket(ctx, destination)
	}
}

func (h *Outbound) MultiplexEnabled() bool {
	if h.multiplexDialer != nil {
		return true
	}
	transport, ok := h.transport.(adapter.V2RayMultiplexClientTransport)
	return ok && transport.MultiplexEnabled()
}

func (h *Outbound) InterfaceUpdated(ctx context.Context) {
	if h.transport != nil {
		if err := adapter.ResetV2RayClientTransport(h.transport); err != nil {
			h.logger.Warn(err)
		}
	}
	if h.multiplexDialer != nil {
		h.multiplexDialer.Reset()
	}
}

func (h *Outbound) SetKeepIdleConnections(keep bool) {
	if keeper, ok := h.transport.(adapter.IdleConnectionKeeper); ok {
		keeper.SetKeepIdleConnections(keep)
	}
	if h.multiplexDialer != nil {
		h.multiplexDialer.SetKeepIdleConnections(keep)
	}
}

func (h *Outbound) CloseIdleConnections() {
	if keeper, ok := h.transport.(adapter.IdleConnectionKeeper); ok {
		keeper.CloseIdleConnections()
	}
	if h.multiplexDialer != nil {
		h.multiplexDialer.CloseIdleConnections()
	}
}

func (h *Outbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	if h.transport != nil {
		scope.Add(h.transport.Close)
	}
	if h.multiplexDialer != nil {
		scope.Add(h.multiplexDialer.Close)
	}
	return nil
}

var _ N.Dialer = (*shadowsocksDialer)(nil)

type shadowsocksDialer Outbound

func (h *shadowsocksDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		var outConn net.Conn
		var err error
		switch {
		case h.plugin != nil:
			outConn, err = h.plugin.DialContext(ctx)
		case h.transport != nil:
			outConn, err = h.transport.DialContext(ctx)
		case h.tlsDialer != nil:
			outConn, err = h.tlsDialer.DialTLSContext(ctx, h.serverAddr)
		default:
			outConn, err = h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
		}
		if err != nil {
			return nil, err
		}
		return h.method.DialEarlyConn(outConn, destination), nil
	case N.NetworkUDP:
		if h.streamOnly {
			return nil, errStreamUDP
		}
		outConn, err := h.dialer.DialContext(ctx, N.NetworkUDP, h.serverAddr)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(h.method.DialPacketConn(outConn), destination), nil
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
}

func (h *shadowsocksDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	if h.streamOnly {
		return nil, errStreamUDP
	}
	outConn, err := h.dialer.DialContext(ctx, N.NetworkUDP, h.serverAddr)
	if err != nil {
		return nil, err
	}
	return h.method.DialPacketConn(outConn), nil
}
