package tproxy

import (
	"errors"
	"fmt"
	"runtime"
	"unsafe"

	"golang.org/x/sys/unix"
)

const tetherLimitMapPath = "/sys/fs/bpf/tethering/map_offload_tether_limit_map"

type offloadLimitMap interface {
	Entries() (map[uint32]uint64, error)
	Update(uint32, uint64) error
	Close() error
}

type kernelOffloadLimitMap struct {
	fd         int
	maxEntries uint32
}

func bpfCall(command int, attr unsafe.Pointer, size uintptr) (uintptr, error) {
	result, _, errno := unix.Syscall(unix.SYS_BPF, uintptr(command), uintptr(attr), size)
	runtime.KeepAlive(attr)
	if errno != 0 {
		return 0, errno
	}
	return result, nil
}

func openOffloadLimitMap() (offloadLimitMap, error) {
	path, err := unix.BytePtrFromString(tetherLimitMapPath)
	if err != nil {
		return nil, err
	}
	var pinned runtime.Pinner
	defer pinned.Unpin()
	pinned.Pin(path)
	get := struct {
		Pathname uint64
		FD       uint32
		Flags    uint32
	}{Pathname: uint64(uintptr(unsafe.Pointer(path)))}
	fd, err := bpfCall(unix.BPF_OBJ_GET, unsafe.Pointer(&get), unsafe.Sizeof(get))
	runtime.KeepAlive(path)
	if err != nil {
		return nil, fmt.Errorf("open tethering BPF limit map: %w", err)
	}
	info := struct {
		Type, ID, KeySize, ValueSize, MaxEntries, Flags uint32
	}{}
	pinned.Pin(&info)
	getInfo := struct {
		FD, Length uint32
		Info       uint64
	}{uint32(fd), uint32(unsafe.Sizeof(info)), uint64(uintptr(unsafe.Pointer(&info)))}
	_, err = bpfCall(unix.BPF_OBJ_GET_INFO_BY_FD, unsafe.Pointer(&getInfo), unsafe.Sizeof(getInfo))
	runtime.KeepAlive(&info)
	if err != nil || info.Type != unix.BPF_MAP_TYPE_HASH || info.KeySize != 4 || info.ValueSize != 8 || info.MaxEntries == 0 || info.MaxEntries > 1024 {
		_ = unix.Close(int(fd))
		return nil, fmt.Errorf("unsupported tethering BPF limit map: %w", errors.Join(err, errors.New("expected uint32/uint64 hash map")))
	}
	return &kernelOffloadLimitMap{fd: int(fd), maxEntries: info.MaxEntries}, nil
}

// bpfMapElement matches the map-element variant of Linux's union bpf_attr,
// including its 64-bit pointer slots on 32-bit Android architectures.
type bpfMapElement struct {
	FD    uint32
	Pad   uint32
	Key   uint64
	Value uint64
	Flags uint64
}

func (m *kernelOffloadLimitMap) Entries() (map[uint32]uint64, error) {
	entries := make(map[uint32]uint64)
	var key, next uint32
	var value uint64
	// bpf_attr stores pointers as integers; pin their storage so a Go stack
	// growth between constructing the attribute and the syscall cannot move it.
	var pinned runtime.Pinner
	defer pinned.Unpin()
	pinned.Pin(&key)
	pinned.Pin(&next)
	pinned.Pin(&value)
	attr := bpfMapElement{FD: uint32(m.fd), Value: uint64(uintptr(unsafe.Pointer(&next)))}
	for range m.maxEntries + 1 {
		_, err := bpfCall(unix.BPF_MAP_GET_NEXT_KEY, unsafe.Pointer(&attr), unsafe.Sizeof(attr))
		runtime.KeepAlive(&key)
		runtime.KeepAlive(&next)
		if errors.Is(err, unix.ENOENT) {
			return entries, nil
		}
		if err != nil {
			return nil, err
		}
		key = next
		lookup := bpfMapElement{FD: uint32(m.fd), Key: uint64(uintptr(unsafe.Pointer(&key))), Value: uint64(uintptr(unsafe.Pointer(&value)))}
		_, err = bpfCall(unix.BPF_MAP_LOOKUP_ELEM, unsafe.Pointer(&lookup), unsafe.Sizeof(lookup))
		runtime.KeepAlive(&key)
		runtime.KeepAlive(&value)
		if err != nil && !errors.Is(err, unix.ENOENT) {
			return nil, err
		}
		if err == nil {
			entries[key] = value
		}
		attr.Key = uint64(uintptr(unsafe.Pointer(&key)))
	}
	return nil, errors.New("tethering BPF limit map changed during enumeration")
}

func (m *kernelOffloadLimitMap) Update(key uint32, value uint64) error {
	var pinned runtime.Pinner
	defer pinned.Unpin()
	pinned.Pin(&key)
	pinned.Pin(&value)
	attr := bpfMapElement{FD: uint32(m.fd), Key: uint64(uintptr(unsafe.Pointer(&key))), Value: uint64(uintptr(unsafe.Pointer(&value))), Flags: unix.BPF_EXIST}
	_, err := bpfCall(unix.BPF_MAP_UPDATE_ELEM, unsafe.Pointer(&attr), unsafe.Sizeof(attr))
	runtime.KeepAlive(&key)
	runtime.KeepAlive(&value)
	return err
}

func (m *kernelOffloadLimitMap) Close() error { return unix.Close(m.fd) }

func (f *Firewall) suppressBPFOffload() error {
	m, err := f.openOffloadMap()
	if err != nil {
		return err
	}
	defer m.Close()
	entries, err := m.Entries()
	if err != nil {
		return err
	}
	for key, previous := range entries {
		if previous == 0 {
			continue
		}
		if f.state.BPFLimits == nil {
			f.state.BPFLimits = make(map[uint32]uint64)
		}
		// Record before mutation, also preserving quota updates made by Android
		// during this session. Never restore a quota older than the latest one.
		f.state.BPFLimits[key] = previous
		if err = f.save(); err != nil {
			return err
		}
		// Both IPv4 and IPv6 offload programs punt to the normal stack when
		// the byte limit is zero. Netfilter/TPROXY then sees every packet,
		// without restarting Android's tethering service or replacing TC hooks.
		if err = m.Update(key, 0); err != nil && !errors.Is(err, unix.ENOENT) {
			return err
		}
	}
	return nil
}

func (f *Firewall) restoreBPFOffload() error {
	if len(f.state.BPFLimits) == 0 {
		return nil
	}
	m, err := f.openOffloadMap()
	if errors.Is(err, unix.ENOENT) {
		return nil // The old map no longer exists, e.g. after a reboot.
	}
	if err != nil {
		return err
	}
	defer m.Close()
	entries, err := m.Entries()
	if err != nil {
		return err
	}
	for key, previous := range f.state.BPFLimits {
		if current, exists := entries[key]; !exists || current != 0 {
			continue // Preserve subsequent Android/user changes.
		}
		if err = m.Update(key, previous); err != nil && !errors.Is(err, unix.ENOENT) {
			return err
		}
	}
	return nil
}
