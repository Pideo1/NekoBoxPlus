package libcore

import (
	"encoding/json"
	"errors"
	"libcore/tproxy"
	"net"
	"net/netip"
	"os"
	"sync"
)

var tproxySession struct {
	sync.RWMutex
	client *tproxy.Client
}

// SetTProxySession must precede construction of a TPROXY BoxInstance.
func SetTProxySession(path, token string) error {
	tproxySession.Lock()
	defer tproxySession.Unlock()
	if path == "" {
		tproxySession.client = nil
		return nil
	}
	if len(token) < 32 {
		return errors.New("invalid root session token")
	}
	tproxySession.client = &tproxy.Client{Path: path, Token: token}
	return nil
}

func currentTProxyClient() *tproxy.Client {
	tproxySession.RLock()
	defer tproxySession.RUnlock()
	return tproxySession.client
}

func TProxyRequest(requestJSON string) (string, error) {
	client := currentTProxyClient()
	if client == nil {
		return "", errors.New("no root broker session")
	}
	var request tproxy.Request
	if err := json.Unmarshal([]byte(requestJSON), &request); err != nil {
		return "", err
	}
	if request.Op != "status" && request.Op != "prepare" && request.Op != "activate" && request.Op != "cleanup" && request.Op != "shutdown" {
		return "", errors.New("unsupported lifecycle operation")
	}
	response, fd, err := client.Call(request, -1)
	if fd >= 0 {
		_ = os.NewFile(uintptr(fd), "unexpected-tproxy-fd").Close()
	}
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(response)
	return string(data), err
}

func TProxyProtect(fd int32) error {
	client := currentTProxyClient()
	if client == nil {
		return errors.New("no root broker session")
	}
	_, unexpected, err := client.Call(tproxy.Request{Op: "protect"}, int(fd))
	if unexpected >= 0 {
		_ = os.NewFile(uintptr(unexpected), "unexpected-tproxy-fd").Close()
	}
	return err
}

type tproxySocketFactory struct{ client tproxy.Client }

func (f *tproxySocketFactory) OpenTProxySocket(network, address string, reply bool) (int, error) {
	_, fd, err := f.client.Call(tproxy.Request{Op: "socket", Network: network, Address: address, Reply: reply}, -1)
	if err == nil && fd < 0 {
		err = errors.New("root broker returned no transparent socket")
	}
	return fd, err
}

func tproxyConnectionOwner(client *tproxy.Client, protocol int32, src string, srcPort int32, dst string, dstPort int32) (int32, error) {
	if srcPort < 0 || srcPort > 65535 || dstPort < 0 || dstPort > 65535 {
		return -1, errors.New("invalid connection port")
	}
	source, err := netip.ParseAddr(src)
	if err != nil {
		return -1, err
	}
	destination, err := netip.ParseAddr(dst)
	if err != nil {
		return -1, err
	}
	response, fd, err := client.Call(tproxy.Request{Op: "owner", Protocol: int(protocol), Source: netip.AddrPortFrom(source, uint16(srcPort)).String(), Destination: netip.AddrPortFrom(destination, uint16(dstPort)).String()}, -1)
	if fd >= 0 {
		_ = os.NewFile(uintptr(fd), "unexpected-tproxy-fd").Close()
	}
	return int32(response.UID), err
}

// ValidateTProxyConfig rejects custom configuration that would reintroduce TUN
// or redirect traffic to a listener the firewall does not own.
func ValidateTProxyConfig(configJSON string, port4, port6, tether4, tether6 int32, ipv4, ipv6, tether bool) error {
	var config struct {
		Inbounds []struct {
			Type, Tag, Listen string
			ListenPort        int `json:"listen_port"`
			Network           string
			Detour            string
		} `json:"inbounds"`
	}
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		return err
	}
	expected := map[string]struct {
		address string
		port    int
	}{}
	if ipv4 {
		expected["tproxy-in4"] = struct {
			address string
			port    int
		}{"0.0.0.0", int(port4)}
	}
	if ipv6 {
		expected["tproxy-in6"] = struct {
			address string
			port    int
		}{"::", int(port6)}
	}
	if tether && ipv4 {
		expected["tproxy-tether-in4"] = struct {
			address string
			port    int
		}{"0.0.0.0", int(tether4)}
	}
	if tether && ipv6 {
		expected["tproxy-tether-in6"] = struct {
			address string
			port    int
		}{"::", int(tether6)}
	}
	for _, inbound := range config.Inbounds {
		if inbound.Type == "tun" || inbound.Type == "redirect" {
			return errors.New("TUN/redirect inbounds are incompatible with TPROXY mode")
		}
		if inbound.Type != "tproxy" {
			continue
		}
		wanted, ok := expected[inbound.Tag]
		if !ok || inbound.Listen != wanted.address || inbound.ListenPort != wanted.port || inbound.Network != "" || inbound.Detour != "" {
			return errors.New("custom config changed a managed TPROXY listener")
		}
		delete(expected, inbound.Tag)
	}
	if len(expected) != 0 {
		return errors.New("custom config removed a managed TPROXY listener")
	}
	return nil
}

// ReserveTProxyPort asks the OS for a free unprivileged port. The broker binds
// the actual TCP/UDP sockets before capture and reports any intervening race.
func ReserveTProxyPort() (int32, error) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer listener.Close()
	return int32(listener.Addr().(*net.TCPAddr).Port), nil
}
