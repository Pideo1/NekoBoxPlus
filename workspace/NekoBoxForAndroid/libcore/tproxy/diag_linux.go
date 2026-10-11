package tproxy

import (
	"encoding/binary"
	"errors"
	"net/netip"
	"syscall"

	"golang.org/x/sys/unix"
)

// inet_diag avoids scanning a potentially large procfs file for each flow.
// Some Android kernels restrict it even for su; procfs remains a fallback.
func diagnosticOwners(protocol int, src, dst netip.AddrPort) ([]ownerCandidate, error) {
	fd, err := unix.Socket(unix.AF_NETLINK, unix.SOCK_RAW|unix.SOCK_CLOEXEC, unix.NETLINK_INET_DIAG)
	if err != nil {
		return nil, err
	}
	defer unix.Close(fd)
	_ = unix.SetsockoptTimeval(fd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &unix.Timeval{Usec: 300000})
	if err = unix.Bind(fd, &unix.SockaddrNetlink{Family: unix.AF_NETLINK}); err != nil {
		return nil, err
	}
	var result []ownerCandidate
	for _, family := range []int{unix.AF_INET, unix.AF_INET6} {
		request := make([]byte, 72)
		binary.NativeEndian.PutUint32(request, uint32(len(request)))
		binary.NativeEndian.PutUint16(request[4:], 20) // SOCK_DIAG_BY_FAMILY
		binary.NativeEndian.PutUint16(request[6:], unix.NLM_F_REQUEST|unix.NLM_F_DUMP)
		binary.NativeEndian.PutUint32(request[8:], uint32(family))
		request[16], request[17] = byte(family), byte(protocol)
		binary.NativeEndian.PutUint32(request[20:], 0xffffffff)
		// Filter dumps by source port; still verify all endpoints in responses.
		binary.BigEndian.PutUint16(request[24:], src.Port())
		binary.NativeEndian.PutUint32(request[64:], 0xffffffff)
		binary.NativeEndian.PutUint32(request[68:], 0xffffffff)
		if err = unix.Sendto(fd, request, 0, &unix.SockaddrNetlink{Family: unix.AF_NETLINK}); err != nil {
			return nil, err
		}
		done := false
		for !done {
			buffer := make([]byte, 65536)
			n, _, e := unix.Recvfrom(fd, buffer, 0)
			if e != nil {
				return nil, e
			}
			messages, e := syscall.ParseNetlinkMessage(buffer[:n])
			if e != nil {
				return nil, e
			}
			for _, message := range messages {
				if message.Header.Seq != uint32(family) {
					continue
				}
				if message.Header.Type == unix.NLMSG_DONE {
					done = true
					break
				}
				if message.Header.Type == unix.NLMSG_ERROR {
					return nil, errors.New("inet_diag unavailable")
				}
				data := message.Data
				if len(data) < 72 {
					continue
				}
				addr := func(offset int) netip.Addr {
					if family == unix.AF_INET {
						return netip.AddrFrom4([4]byte(data[offset : offset+4]))
					}
					return netip.AddrFrom16([16]byte(data[offset : offset+16])).Unmap()
				}
				local, remote := addr(8), addr(24)
				port, remotePort := binary.BigEndian.Uint16(data[4:]), binary.BigEndian.Uint16(data[6:])
				if port != src.Port() || (!local.IsUnspecified() && local != src.Addr().Unmap()) {
					continue
				}
				score := 2
				if remotePort == dst.Port() && remote == dst.Addr().Unmap() {
					score += 2
				} else if protocol != 17 || !remote.IsUnspecified() || remotePort != 0 {
					continue
				}
				if local.IsUnspecified() {
					score--
				}
				result = append(result, ownerCandidate{int(binary.NativeEndian.Uint32(data[64:])), score})
			}
		}
	}
	return result, nil
}
