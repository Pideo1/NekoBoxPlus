package tproxy

import (
	"errors"
	"fmt"
	"net/netip"
	"os"
	"runtime"
	"strings"

	"golang.org/x/sys/unix"
)

// SELinux checks the socket's label when SCM_RIGHTS reaches the app. Create
// the socket with that authenticated peer's label while retaining root's
// privilege to set transparent options and bind the original destination.
func transparentSocketForPeer(network, address string, reply bool, label string) (fd int, err error) {
	if label == "" {
		return transparentSocket(network, address, reply)
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	const sockcreate = "/proc/thread-self/attr/sockcreate"
	if err = os.WriteFile(sockcreate, []byte(label), 0); err != nil {
		return -1, fmt.Errorf("set peer socket label: %w", err)
	}
	defer func() {
		if resetErr := os.WriteFile(sockcreate, []byte("\n"), 0); resetErr != nil {
			if fd >= 0 {
				_ = unix.Close(fd)
				fd = -1
			}
			err = errors.Join(err, fmt.Errorf("reset socket label: %w", resetErr))
		}
	}()
	return transparentSocket(network, address, reply)
}

func transparentSocket(network, address string, reply bool) (fd int, err error) {
	endpoint, err := netip.ParseAddrPort(address)
	if err != nil {
		return -1, err
	}
	endpoint = netip.AddrPortFrom(endpoint.Addr().Unmap(), endpoint.Port())
	if endpoint.Port() == 0 {
		return -1, errors.New("transparent socket needs a port")
	}
	is6 := endpoint.Addr().Is6()
	if network == "tcp" || network == "udp" {
		if is6 {
			network += "6"
		} else {
			network += "4"
		}
	}
	if (network != "tcp4" && network != "tcp6" && network != "udp4" && network != "udp6") || is6 != strings.HasSuffix(network, "6") {
		return -1, errors.New("invalid socket family")
	}
	isUDP := strings.HasPrefix(network, "udp")
	if reply && !isUDP {
		return -1, errors.New("only UDP reply sockets are supported")
	}
	family, kind := unix.AF_INET, unix.SOCK_STREAM
	if is6 {
		family = unix.AF_INET6
	}
	if isUDP {
		kind = unix.SOCK_DGRAM
	}
	fd, err = unix.Socket(family, kind|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return -1, err
	}
	defer func() {
		if err != nil {
			_ = unix.Close(fd)
			fd = -1
		}
	}()
	for _, option := range [][3]int{{unix.SOL_SOCKET, unix.SO_REUSEADDR, 1}, {unix.SOL_SOCKET, unix.SO_MARK, BypassMark}} {
		if err = unix.SetsockoptInt(fd, option[0], option[1], option[2]); err != nil {
			return fd, err
		}
	}
	level, transparent, orig := unix.SOL_IP, unix.IP_TRANSPARENT, unix.IP_RECVORIGDSTADDR
	if is6 {
		level, transparent, orig = unix.SOL_IPV6, unix.IPV6_TRANSPARENT, unix.IPV6_RECVORIGDSTADDR
		if err = unix.SetsockoptInt(fd, unix.SOL_IPV6, unix.IPV6_V6ONLY, 1); err != nil {
			return fd, err
		}
	}
	if err = unix.SetsockoptInt(fd, level, transparent, 1); err != nil {
		return fd, err
	}
	if isUDP && !reply {
		if err = unix.SetsockoptInt(fd, level, orig, 1); err != nil {
			return fd, err
		}
	}
	var sockaddr unix.Sockaddr
	if is6 {
		sockaddr = &unix.SockaddrInet6{Port: int(endpoint.Port()), Addr: endpoint.Addr().As16()}
	} else {
		sockaddr = &unix.SockaddrInet4{Port: int(endpoint.Port()), Addr: endpoint.Addr().As4()}
	}
	if err = unix.Bind(fd, sockaddr); err != nil {
		return fd, fmt.Errorf("bind transparent socket: %w", err)
	}
	if !isUDP {
		err = unix.Listen(fd, 128)
	}
	return fd, err
}

func protectFD(fd int) error {
	mark, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_MARK)
	if err != nil {
		return err
	}
	return unix.SetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_MARK, (mark & ^CaptureMark)|BypassMark)
}
