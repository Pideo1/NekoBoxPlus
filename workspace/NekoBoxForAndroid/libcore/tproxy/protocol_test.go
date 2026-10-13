package tproxy

import (
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/unix"
)

func TestClientReceivesWorkingDescriptor(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broker")
	listener, err := net.ListenUnix("unixpacket", &net.UnixAddr{Name: path, Net: "unixpacket"})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	defer w.Close()
	result := make(chan error, 1)
	go func() {
		conn, e := listener.AcceptUnix()
		if e != nil {
			result <- e
			return
		}
		defer conn.Close()
		data := make([]byte, maxMessage)
		n, _, _, _, e := conn.ReadMsgUnix(data, nil)
		var req Request
		if e == nil {
			e = json.Unmarshal(data[:n], &req)
		}
		if e == nil && (req.Version != Version || req.Token != "secret") {
			t.Error("client did not authenticate")
		}
		if e == nil {
			_, _, e = conn.WriteMsgUnix([]byte(`{"uid":123}`), unix.UnixRights(int(r.Fd())), nil)
		}
		result <- e
	}()
	response, fd, err := (Client{Path: path, Token: "secret"}).Call(Request{Op: "status"}, -1)
	if err != nil {
		t.Fatal(err)
	}
	if response.UID != 123 || fd < 0 {
		t.Fatalf("invalid response: %+v fd=%d", response, fd)
	}
	received := os.NewFile(uintptr(fd), "received")
	defer received.Close()
	if _, err = w.Write([]byte{42}); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, 1)
	if _, err = received.Read(data); err != nil || data[0] != 42 {
		t.Fatalf("descriptor does not work: %v", err)
	}
	if err = <-result; err != nil {
		t.Fatal(err)
	}
}

func TestMultipleDescriptorsAreClosedAndRejected(t *testing.T) {
	fds := make([]int, 2)
	if err := unix.Pipe2(fds, unix.O_CLOEXEC); err != nil {
		t.Fatal(err)
	}
	if fd, err := receivedFD(unix.UnixRights(fds...)); err == nil || fd != -1 {
		t.Fatal("multiple descriptors accepted")
	}
	for _, fd := range fds {
		if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != unix.EBADF {
			t.Fatalf("leaked descriptor %d: %v", fd, err)
		}
	}
}

func TestDescriptorMayAccompanyCredentials(t *testing.T) {
	fds := make([]int, 2)
	if err := unix.Pipe2(fds, unix.O_CLOEXEC); err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[0])
	defer unix.Close(fds[1])
	oob := append(unix.UnixCredentials(&unix.Ucred{Pid: int32(os.Getpid()), Uid: uint32(os.Getuid()), Gid: uint32(os.Getgid())}), unix.UnixRights(fds[0])...)
	fd, err := receivedFD(oob)
	if err != nil || fd != fds[0] {
		t.Fatalf("descriptor with credentials rejected: fd=%d error=%v", fd, err)
	}
}
