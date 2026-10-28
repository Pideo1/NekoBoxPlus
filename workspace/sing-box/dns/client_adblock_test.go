package dns

import (
	"context"
	"errors"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

type cachedResponseAdblock struct {
	adapter.AdblockService
	calls int
}

func (s *cachedResponseAdblock) CheckDNSResponse(_ context.Context, _, response *mDNS.Msg) {
	s.calls++
	response.Answer = nil
	response.Rcode = mDNS.RcodeNameError
}

func TestCachedResponseValidationBeforeAdblock(t *testing.T) {
	ctx := service.ContextWithDefaultRegistry(t.Context())
	client := NewClient(ClientOptions{Context: ctx, Logger: log.NewNOPFactory().NewLogger("dns")})
	transport := &fakeDNSTransport{tag: "test", address: netip.MustParseAddr("192.0.2.1")}
	message := new(mDNS.Msg).SetQuestion("example.com.", mDNS.TypeA)
	if _, err := client.Exchange(ctx, transport, message.Copy(), adapter.DNSQueryOptions{}, nil); err != nil {
		t.Fatal(err)
	}
	adblock := new(cachedResponseAdblock)
	ctx = service.ContextWith[adapter.AdblockService](ctx, adblock)
	response, err := client.Exchange(ctx, transport, message.Copy(), adapter.DNSQueryOptions{}, func(response *mDNS.Msg) bool {
		return response.Rcode == mDNS.RcodeSuccess && len(response.Answer) == 1
	})
	if err != nil || response == nil || response.Rcode != mDNS.RcodeNameError || adblock.calls != 1 {
		t.Fatalf("cached response was not filtered: response=%v error=%v calls=%d", response, err, adblock.calls)
	}
	_, err = client.Exchange(ctx, transport, message.Copy(), adapter.DNSQueryOptions{}, func(*mDNS.Msg) bool { return false })
	if !errors.Is(err, ErrResponseRejectedCached) || adblock.calls != 1 {
		t.Fatalf("rejected cache hit reached adblock: error=%v calls=%d", err, adblock.calls)
	}
	if transport.queryCount.Load() != 1 {
		t.Fatal("cache hits unexpectedly queried the transport")
	}
}
