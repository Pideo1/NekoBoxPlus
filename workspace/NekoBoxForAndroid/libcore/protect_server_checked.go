package libcore

import (
	"errors"
	"io"
	"net"
	"os"
	"time"

	"golang.org/x/sys/unix"
)

// Unlike the legacy protect server, acknowledge only after socket protection
// succeeds. This also protects callers in the UI process and external plugins.
func serveCheckedProtect(path string) (io.Closer, error) {
	_ = os.Remove(path)
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	if err = os.Chmod(path, 0600); err != nil {
		_ = listener.Close()
		return nil, err
	}
	go func() {
		for {
			conn, err := listener.AcceptUnix()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
				raw, err := conn.SyscallConn()
				if err != nil {
					return
				}
				var peer *unix.Ucred
				_ = raw.Control(func(fd uintptr) { peer, _ = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED) })
				if peer == nil || int(peer.Uid) != os.Getuid() && peer.Uid != 0 {
					return
				}
				data, oob := make([]byte, 1), make([]byte, 512)
				_, n, flags, _, err := conn.ReadMsgUnix(data, oob)
				if err != nil {
					return
				}
				messages, err := unix.ParseSocketControlMessage(oob[:n])
				if err != nil {
					return
				}
				var descriptors []int
				for _, message := range messages {
					if message.Header.Level != unix.SOL_SOCKET || message.Header.Type != unix.SCM_RIGHTS {
						continue
					}
					fds, e := unix.ParseUnixRights(&message)
					err = errors.Join(err, e)
					descriptors = append(descriptors, fds...)
				}
				defer func() {
					for _, fd := range descriptors {
						_ = unix.Close(fd)
					}
				}()
				ok := err == nil && flags&unix.MSG_CTRUNC == 0 && len(descriptors) == 1
				if ok {
					ok = intfBox.AutoDetectInterfaceControl(int32(descriptors[0])) == nil
				}
				ack := byte(0)
				if ok {
					ack = 1
				}
				_, _ = conn.Write([]byte{ack})
			}()
		}
	}()
	return listener, nil
}
