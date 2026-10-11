package tproxy

import (
	"bufio"
	"net/netip"
	"strings"
	"testing"
)

func TestOwnerRequiresRemoteEndpointAndRejectsAmbiguity(t *testing.T) {
	src := netip.MustParseAddrPort("127.0.0.1:12345")
	dst := netip.MustParseAddrPort("8.8.8.8:443")
	rows := ` 0: 0100007F:3039 08080808:01BB 01 0:0 0:0 0 10123 0 123
 1: 0100007F:3039 01010101:01BB 01 0:0 0:0 0 10456 0 456`
	uid, err := selectOwner(readOwners(bufio.NewScanner(strings.NewReader(rows)), src, dst, false))
	if err != nil || uid != 10123 {
		t.Fatalf("owner=%d, error=%v", uid, err)
	}
	rows += "\n 2: 0100007F:3039 08080808:01BB 01 0:0 0:0 0 10789 0 789"
	if _, err = selectOwner(readOwners(bufio.NewScanner(strings.NewReader(rows)), src, dst, false)); err == nil {
		t.Fatal("ambiguous UID must not be guessed")
	}
}

func TestWildcardUDPAndMappedIPv6(t *testing.T) {
	src := netip.MustParseAddrPort("192.0.2.1:12345")
	dst := netip.MustParseAddrPort("8.8.8.8:53")
	rows := ` 0: 00000000:3039 00000000:0000 07 0:0 0:0 0 10123 0 123`
	uid, err := selectOwner(readOwners(bufio.NewScanner(strings.NewReader(rows)), src, dst, true))
	if err != nil || uid != 10123 {
		t.Fatalf("owner=%d, error=%v", uid, err)
	}
	if _, err = selectOwner(readOwners(bufio.NewScanner(strings.NewReader(rows)), src, dst, false)); err == nil {
		t.Fatal("unconnected TCP must not match UDP fallback")
	}
	endpoint, err := procEndpoint("0000000000000000FFFF0000010200C0:3039")
	if err != nil || endpoint != src {
		t.Fatalf("mapped endpoint=%v, error=%v", endpoint, err)
	}
}
