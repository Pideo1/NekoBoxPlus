package option

import (
	"encoding/json"
	"net"
	"testing"
	"time"
)

func TestMASQUEConfigSelectEndpointHTTP2FallsBackToIPv4(t *testing.T) {
	config := &MASQUEConfig{
		EndpointH2V4: "162.159.198.2",
	}

	endpoint, err := config.SelectEndpointFromConfig(true, false, 443)
	if err != nil {
		t.Fatal(err)
	}

	tcpEndpoint, ok := endpoint.(*net.TCPAddr)
	if !ok {
		t.Fatalf("endpoint type = %T, want *net.TCPAddr", endpoint)
	}
	if !tcpEndpoint.IP.Equal(net.ParseIP("162.159.198.2")) {
		t.Fatalf("endpoint IP = %s, want 162.159.198.2", tcpEndpoint.IP)
	}
}

func TestMASQUEOutboundOptionsCompatibility(t *testing.T) {
	var options MASQUEOutboundOptions
	err := json.Unmarshal([]byte(`{
		"use_http2": true,
		"udp_initial_packet_size": 1300,
		"transport": "h2",
		"h3_fallback_timeout": "3s",
		"mtu": 1400,
		"disable_path_mtu_discovery": true
	}`), &options)
	if err != nil {
		t.Fatal(err)
	}
	if !options.UseHTTP2 || options.Transport != "h2" || options.UDPInitialPacketSize != 1300 || options.MTU != 1400 || !options.DisablePathMTUDiscovery {
		t.Fatalf("unexpected decoded options: %#v", options)
	}
	if time.Duration(options.H3FallbackTimeout) != 3*time.Second {
		t.Fatalf("fallback timeout = %s", time.Duration(options.H3FallbackTimeout))
	}
}

func TestMASQUEConfigEndpointHost(t *testing.T) {
	config := MASQUEConfig{
		EndpointV4: "h3-v4.example", EndpointV6: "h3-v6.example",
		EndpointH2V4: "h2-v4.example", EndpointH2V6: "h2-v6.example",
	}
	for _, test := range []struct {
		http2 bool
		ipv6  bool
		want  string
	}{
		{false, false, "h3-v4.example"},
		{false, true, "h3-v6.example"},
		{true, false, "h2-v4.example"},
		{true, true, "h2-v6.example"},
	} {
		if host := config.EndpointHost(test.http2, test.ipv6); host != test.want {
			t.Fatalf("EndpointHost(%v, %v) = %q, want %q", test.http2, test.ipv6, host, test.want)
		}
	}
	config.EndpointH2V4 = ""
	config.EndpointH2V6 = ""
	for _, ipv6 := range []bool{false, true} {
		if host := config.EndpointHost(true, ipv6); host != config.EndpointHost(false, ipv6) {
			t.Fatalf("HTTP/2 fallback host = %q", host)
		}
	}
}
