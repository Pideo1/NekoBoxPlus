//go:build with_quic

package shadowsocks

import (
	"bytes"
	"context"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2ray"
	"github.com/sagernet/sing-box/transport/v2rayquic"
	ss "github.com/sagernet/sing-shadowsocks"
	"github.com/sagernet/sing-shadowsocks/shadowaead"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/stretchr/testify/require"
)

type streamTestHandler struct{ service ss.Service }

func (h *streamTestHandler) NewConnectionEx(ctx context.Context, conn net.Conn, source, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	defer conn.Close()
	_ = h.service.NewConnection(ctx, conn, M.Metadata{Source: source})
}
func (h *streamTestHandler) NewError(context.Context, error) {}

// These peers terminate the outer transport and then parse an actual encrypted
// Shadowsocks stream. No external processes or network access are needed.
func TestShadowsocksTransportRoundTrip(t *testing.T) {
	v2ray.RegisterQUICConstructor(v2rayquic.NewServer, v2rayquic.NewClient)
	certificateServer := httptest.NewTLSServer(http.NotFoundHandler())
	cert := certificateServer.TLS.Certificates[0]
	certificateServer.Close()
	key, err := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
	require.NoError(t, err)
	certPEM := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]}))
	keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))
	for _, transportType := range []string{"", "http", "ws", "grpc", "httpupgrade", "xhttp", "quic", "kcp"} {
		for _, secure := range []bool{false, true} {
			if transportType == "quic" && !secure {
				continue
			}
			name := transportType
			if name == "" {
				name = "tcp"
			}
			if secure {
				name += "-tls"
			}
			t.Run(name, func(t *testing.T) {
				ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
				defer cancel()
				logger := log.NewNOPFactory().NewLogger("test")
				handler := &streamTestHandler{}
				handler.service, err = shadowaead.NewService("aes-128-gcm", nil, "transport-password", 60,
					adapter.NewLegacyUpstreamHandler(adapter.InboundContext{}, func(ctx context.Context, conn net.Conn, metadata adapter.InboundContext) error {
						defer conn.Close()
						_, err := io.Copy(conn, conn)
						return err
					}, nil, handler))
				require.NoError(t, err)
				var tlsConfig tls.ServerConfig
				if secure {
					tlsConfig, err = tls.NewServer(ctx, logger, option.InboundTLSOptions{Enabled: true, Certificate: []string{certPEM}, Key: []string{keyPEM}})
					require.NoError(t, err)
					require.NoError(t, tlsConfig.Start())
					defer tlsConfig.Close()
				}
				transportOptions := option.V2RayTransportOptions{Type: transportType}
				transportOptions.WebsocketOptions.Path = "/ss"
				transportOptions.HTTPOptions.Path = "/ss"
				transportOptions.GRPCOptions.ServiceName = "ss"
				transportOptions.HTTPUpgradeOptions.Path = "/ss"
				transportOptions.XHTTPOptions.Path = "/ss"
				var address net.Addr
				if transportType != "" {
					server, err := v2ray.NewServerTransport(ctx, logger, transportOptions, tlsConfig, handler)
					require.NoError(t, err)
					defer server.Close()
					if slices.Contains(server.Network(), "udp") {
						listener, err := net.ListenPacket("udp", "127.0.0.1:0")
						require.NoError(t, err)
						defer listener.Close()
						address = listener.LocalAddr()
						go server.ServePacket(listener)
					} else {
						listener, err := net.Listen("tcp", "127.0.0.1:0")
						require.NoError(t, err)
						defer listener.Close()
						address = listener.Addr()
						go server.Serve(listener)
					}
				} else {
					listener, err := net.Listen("tcp", "127.0.0.1:0")
					require.NoError(t, err)
					defer listener.Close()
					address = listener.Addr()
					go func() {
						for {
							conn, err := listener.Accept()
							if err != nil {
								return
							}
							go func() {
								if tlsConfig != nil {
									wrapped, err := tls.ServerHandshake(ctx, conn, tlsConfig)
									if err != nil {
										conn.Close()
										return
									}
									conn = wrapped
								}
								handler.NewConnectionEx(ctx, conn, M.Socksaddr{}, M.Socksaddr{}, nil)
							}()
						}
					}()
				}
				serverAddress := M.SocksaddrFromNet(address)
				options := option.ShadowsocksOutboundOptions{ServerOptions: option.ServerOptions{Server: serverAddress.Addr.String(), ServerPort: serverAddress.Port}, Method: "aes-128-gcm", Password: "transport-password"}
				if secure {
					options.TLS = &option.OutboundTLSOptions{Enabled: true, Insecure: true}
				}
				if transportType != "" {
					options.Transport = &transportOptions
				}
				created, err := NewOutbound(ctx, nil, logger, "test", options)
				require.NoError(t, err)
				h := created.(*Outbound)
				scope := adapter.NewScope(ctx, logger)
				require.NoError(t, h.Start(adapter.StartStateInitialize, scope))
				defer scope.Close()
				for attempt := range 3 {
					if attempt == 2 {
						h.InterfaceUpdated(ctx)
					}
					conn, err := h.DialContext(ctx, "tcp", M.ParseSocksaddr("127.0.0.1:443"))
					require.NoError(t, err)
					// HTTP-backed streams may not expose socket deadlines.
					if err := conn.SetDeadline(time.Now().Add(10 * time.Second)); !errors.Is(err, os.ErrInvalid) {
						require.NoError(t, err)
					}
					stopClose := context.AfterFunc(ctx, func() { conn.Close() })
					payload := bytes.Repeat([]byte("shadowsocks-transport-payload"), 2048)
					_, err = conn.Write(payload)
					require.NoError(t, err)
					response := make([]byte, len(payload))
					_, err = io.ReadFull(conn, response)
					require.NoError(t, err)
					require.Equal(t, payload, response)
					require.NoError(t, conn.Close())
					stopClose()
				}
			})
		}
	}
}
