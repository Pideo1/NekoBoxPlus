package tproxy

import (
	"bufio"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"strconv"
	"strings"
)

// ResolveOwner uses the root process's procfs view. It deliberately does not
// reuse libcore/procfs: that implementation ignores the remote endpoint.
func ResolveOwner(protocol int, source, destination string) (int, error) {
	src, err := netip.ParseAddrPort(source)
	if err != nil {
		return -1, err
	}
	dst, err := netip.ParseAddrPort(destination)
	if err != nil {
		return -1, err
	}
	name := "tcp"
	if protocol == 17 {
		name = "udp"
	} else if protocol != 6 {
		return -1, errors.New("unsupported owner protocol")
	}
	if candidates, err := diagnosticOwners(protocol, src, dst); err == nil {
		if uid, err := selectOwner(candidates); err == nil {
			return uid, nil
		}
	}
	var candidates []ownerCandidate
	for _, suffix := range []string{"", "6"} {
		file, e := os.Open("/proc/net/" + name + suffix)
		if e != nil {
			continue
		}
		candidates = append(candidates, readOwners(bufio.NewScanner(file), src, dst, protocol == 17)...)
		_ = file.Close()
	}
	return selectOwner(candidates)
}

type ownerCandidate struct{ uid, score int }

func readOwners(scanner *bufio.Scanner, src, dst netip.AddrPort, udp bool) []ownerCandidate {
	var result []ownerCandidate
	for scanner.Scan() {
		columns := strings.Fields(scanner.Text())
		if len(columns) < 8 {
			continue
		}
		local, e := procEndpoint(columns[1])
		if e != nil {
			continue
		}
		remote, e := procEndpoint(columns[2])
		if e != nil {
			continue
		}
		if local.Port() != src.Port() || (!local.Addr().IsUnspecified() && local.Addr().Unmap() != src.Addr().Unmap()) {
			continue
		}
		score := 2
		if remote.Port() == dst.Port() && remote.Addr().Unmap() == dst.Addr().Unmap() {
			score += 2
		} else if !udp || !remote.Addr().IsUnspecified() || remote.Port() != 0 {
			continue
		}
		if local.Addr().IsUnspecified() {
			score--
		}
		uid, e := strconv.Atoi(columns[7])
		if e != nil {
			continue
		}
		result = append(result, ownerCandidate{uid, score})
	}
	return result
}

func selectOwner(candidates []ownerCandidate) (int, error) {
	uid, score := -1, -1
	ambiguous := false
	for _, c := range candidates {
		if c.score > score {
			uid, score, ambiguous = c.uid, c.score, false
		} else if c.score == score && c.uid != uid {
			ambiguous = true
		}
	}
	if uid < 0 || ambiguous {
		return -1, errors.New("socket owner missing or ambiguous")
	}
	return uid, nil
}

func procEndpoint(raw string) (netip.AddrPort, error) {
	ip, port, ok := strings.Cut(raw, ":")
	if !ok {
		return netip.AddrPort{}, errors.New("invalid proc endpoint")
	}
	bytes, err := hex.DecodeString(ip)
	if err != nil || (len(bytes) != 4 && len(bytes) != 16) {
		return netip.AddrPort{}, errors.New("invalid proc address")
	}
	for i := 0; i < len(bytes); i += 4 {
		binary.BigEndian.PutUint32(bytes[i:], binary.NativeEndian.Uint32(bytes[i:]))
	}
	addr, ok := netip.AddrFromSlice(bytes)
	if !ok {
		return netip.AddrPort{}, fmt.Errorf("invalid address %q", raw)
	}
	p, err := strconv.ParseUint(port, 16, 16)
	if err != nil {
		return netip.AddrPort{}, err
	}
	return netip.AddrPortFrom(addr.Unmap(), uint16(p)), nil
}
