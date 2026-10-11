package masque

import (
	"context"
	"errors"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
)

func TestParseMASQUEAddresses(t *testing.T) {
	tests := []struct {
		name      string
		config    option.MASQUEConfig
		want      []netip.Prefix
		wantError bool
	}{
		{
			name:   "valid",
			config: option.MASQUEConfig{IPv4: "192.0.2.1", IPv6: "2001:db8::1"},
			want:   []netip.Prefix{netip.MustParsePrefix("192.0.2.1/32"), netip.MustParsePrefix("2001:db8::1/128")},
		},
		{name: "missing IPv4", config: option.MASQUEConfig{IPv6: "2001:db8::1"}, wantError: true},
		{name: "missing IPv6", config: option.MASQUEConfig{IPv4: "192.0.2.1"}, wantError: true},
		{name: "malformed IPv4", config: option.MASQUEConfig{IPv4: "invalid", IPv6: "2001:db8::1"}, wantError: true},
		{name: "malformed IPv6", config: option.MASQUEConfig{IPv4: "192.0.2.1", IPv6: "invalid"}, wantError: true},
		{name: "IPv6 in IPv4 field", config: option.MASQUEConfig{IPv4: "2001:db8::1", IPv6: "2001:db8::1"}, wantError: true},
		{name: "IPv4 in IPv6 field", config: option.MASQUEConfig{IPv4: "192.0.2.1", IPv6: "192.0.2.1"}, wantError: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			addresses, err := parseMASQUEAddresses(&test.config)
			if test.wantError {
				if err == nil {
					t.Fatal("expected error")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if len(addresses) != len(test.want) {
				t.Fatalf("got %d addresses, want %d", len(addresses), len(test.want))
			}
			for index := range addresses {
				if addresses[index] != test.want[index] {
					t.Fatalf("address %d is %s, want %s", index, addresses[index], test.want[index])
				}
			}
		})
	}
}

func TestResolveTransportOptions(t *testing.T) {
	tests := []struct {
		name          string
		options       option.MASQUEOutboundOptions
		wantTransport string
		wantMTU       uint32
		wantTimeout   time.Duration
		wantError     bool
	}{
		{name: "defaults", wantTransport: "auto", wantMTU: 1280, wantTimeout: 5 * time.Second},
		{name: "legacy h2", options: option.MASQUEOutboundOptions{UseHTTP2: true}, wantTransport: "h2", wantMTU: 1280, wantTimeout: 5 * time.Second},
		{name: "explicit h3", options: option.MASQUEOutboundOptions{Transport: "h3", MTU: 64000}, wantTransport: "h3", wantMTU: 64000, wantTimeout: 5 * time.Second},
		{name: "custom", options: option.MASQUEOutboundOptions{Transport: "auto", MTU: 1400, H3FallbackTimeout: badoption.Duration(2 * time.Second)}, wantTransport: "auto", wantMTU: 1400, wantTimeout: 2 * time.Second},
		{name: "conflict", options: option.MASQUEOutboundOptions{Transport: "h3", UseHTTP2: true}, wantError: true},
		{name: "invalid transport", options: option.MASQUEOutboundOptions{Transport: "tcp"}, wantError: true},
		{name: "h2 mtu", options: option.MASQUEOutboundOptions{Transport: "h2", MTU: 16001}, wantError: true},
		{name: "negative timeout", options: option.MASQUEOutboundOptions{H3FallbackTimeout: badoption.Duration(-time.Second)}, wantError: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			transport, mtu, timeout, err := resolveTransportOptions(test.options)
			if test.wantError {
				if err == nil {
					t.Fatal("expected error")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if transport != test.wantTransport || mtu != test.wantMTU || timeout != test.wantTimeout {
				t.Fatalf("got (%q, %d, %s), want (%q, %d, %s)", transport, mtu, timeout, test.wantTransport, test.wantMTU, test.wantTimeout)
			}
		})
	}
}

func TestResolveEndpointAddress(t *testing.T) {
	lookupError := errors.New("DNS unavailable")
	tests := []struct {
		name        string
		host        string
		useIPv6     bool
		addresses   []netip.Addr
		lookupError error
		want        netip.Addr
		wantLookup  bool
		wantError   string
	}{
		{name: "IPv4 literal", host: "192.0.2.1", want: netip.MustParseAddr("192.0.2.1")},
		{name: "IPv6 literal", host: "2001:db8::1", useIPv6: true, want: netip.MustParseAddr("2001:db8::1")},
		{name: "hostname IPv4", host: "endpoint.example", addresses: []netip.Addr{netip.MustParseAddr("2001:db8::1"), netip.MustParseAddr("192.0.2.1")}, want: netip.MustParseAddr("192.0.2.1"), wantLookup: true},
		{name: "hostname IPv6", host: "endpoint.example", useIPv6: true, addresses: []netip.Addr{netip.MustParseAddr("192.0.2.1"), netip.MustParseAddr("2001:db8::1")}, want: netip.MustParseAddr("2001:db8::1"), wantLookup: true},
		{name: "mapped IPv4", host: "endpoint.example", addresses: []netip.Addr{netip.MustParseAddr("::ffff:192.0.2.1")}, want: netip.MustParseAddr("192.0.2.1"), wantLookup: true},
		{name: "missing", wantError: "missing endpoint"},
		{name: "empty response", host: "endpoint.example", wantLookup: true, wantError: "no IPv4 address"},
		{name: "no IPv6", host: "endpoint.example", useIPv6: true, addresses: []netip.Addr{netip.MustParseAddr("192.0.2.1")}, wantLookup: true, wantError: "no IPv6 address"},
		{name: "DNS error", host: "endpoint.example", lookupError: lookupError, wantLookup: true, wantError: "DNS unavailable"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			lookedUp := false
			address, err := resolveEndpointAddress(test.host, test.useIPv6, func(host string) ([]netip.Addr, error) {
				lookedUp = true
				if host != test.host {
					t.Fatalf("lookup host = %q, want %q", host, test.host)
				}
				return test.addresses, test.lookupError
			})
			if lookedUp != test.wantLookup {
				t.Fatalf("lookup called = %v, want %v", lookedUp, test.wantLookup)
			}
			if test.wantError != "" {
				if err == nil || !strings.Contains(err.Error(), test.wantError) {
					t.Fatalf("error = %v, want %q", err, test.wantError)
				}
				if test.lookupError != nil && !errors.Is(err, test.lookupError) {
					t.Fatalf("error does not wrap DNS error: %v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if address != test.want {
				t.Fatalf("address = %s, want %s", address, test.want)
			}
		})
	}
}

func TestScopeCloseBeforeMASQUEInitializerStarts(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	outbound := &Outbound{ctx: ctx, cancel: cancel, await: make(chan struct{})}
	scope := adapter.NewScope(t.Context(), log.NewNOPFactory().Logger())
	if err := outbound.Start(adapter.StartStateInitialize, scope); err != nil {
		t.Fatal(err)
	}
	if err := scope.Close(); err != nil {
		t.Fatal(err)
	}
	if ctx.Err() == nil {
		t.Fatal("initializer context was not canceled")
	}
}

func TestScopeCloseCancelsMASQUEInitializer(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	outbound := &Outbound{ctx: ctx, cancel: cancel, await: make(chan struct{})}
	outbound.startHandler = func() { <-ctx.Done(); close(outbound.await) }
	scope := adapter.NewScope(t.Context(), log.NewNOPFactory().Logger())
	if err := outbound.Start(adapter.StartStateInitialize, scope); err != nil {
		t.Fatal(err)
	}
	if err := outbound.Start(adapter.StartStatePostStart, scope); err != nil {
		t.Fatal(err)
	}
	if err := scope.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-outbound.await:
	default:
		t.Fatal("initializer is still running after scope close")
	}
}
