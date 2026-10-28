package xhttp

import (
	"context"
	"errors"
	"net"
	"net/http"
	"testing"

	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/net/http2"
)

type sessionContextDialer struct {
	ctx context.Context
}

func (d *sessionContextDialer) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	d.ctx = ctx
	return nil, errors.New("stop before connecting")
}

func (d *sessionContextDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	panic("unexpected packet listener")
}

func TestSharedTransportClearsRequestRoutingContext(t *testing.T) {
	for _, proto := range []string{"http/1.1", http2.NextProtoTLS, http3.NextProtoH3} {
		t.Run(proto, func(t *testing.T) {
			dialer := new(sessionContextDialer)
			client := createHTTPClient(t.Context(), M.ParseSocksaddr("example.com:443"), dialer,
				&option.V2RayXHTTPBaseOptions{}, &fakeTLSConfig{nextProtos: []string{proto}}).(*DefaultDialerClient)
			t.Cleanup(func() { _ = client.Close() })
			ctx := adapter.WithContext(t.Context(), &adapter.InboundContext{Outbound: "proxy", Domain: "request.example"})
			ctx = adapter.ContextWithDNSTransportTag(ctx, "dns")
			ctx = interrupt.ContextWithIsExternalConnection(ctx, true)
			request, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://example.com/", nil)
			if err != nil {
				t.Fatal(err)
			}
			_, err = client.client.Do(request)
			if err == nil || dialer.ctx == nil {
				t.Fatalf("expected dial failure, got %v", err)
			}
			if interrupt.IsExternalConnectionFromContext(dialer.ctx) {
				t.Fatal("shared transport inherited external connection interruption")
			}
			if tag, _ := adapter.DNSTransportTagFromContext(dialer.ctx); tag != "" {
				t.Fatalf("shared transport inherited DNS transport %q", tag)
			}
			metadata := adapter.ContextFrom(dialer.ctx)
			if metadata == nil || metadata.Outbound != "proxy" || metadata.Domain != "" {
				t.Fatalf("unexpected shared transport metadata: %+v", metadata)
			}
		})
	}
}
