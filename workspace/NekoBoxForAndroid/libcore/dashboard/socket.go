// SPDX-License-Identifier: GPL-3.0-or-later
package dashboard

import (
	"errors"
	"golang.org/x/sys/unix"
	"net"
	"os"
	"path/filepath"
	"time"
)

type privateListener struct {
	*net.UnixListener
	uid uint32
}

func (l *privateListener) Accept() (net.Conn, error) {
	for {
		conn, err := l.AcceptUnix()
		if err != nil {
			return nil, err
		}
		raw, err := conn.SyscallConn()
		var peer *unix.Ucred
		var credentialErr error
		if err == nil {
			err = raw.Control(func(fd uintptr) {
				peer, credentialErr = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
			})
		}
		if err == nil && credentialErr == nil && peer != nil && peer.Uid == l.uid {
			return conn, nil
		}
		_ = conn.Close()
	}
}

func listenPrivate(path string) (net.Listener, error) {
	if !filepath.IsAbs(path) {
		return nil, errors.New("dashboard socket path must be absolute")
	}
	parent := filepath.Dir(path)
	if err := os.MkdirAll(parent, 0700); err != nil {
		return nil, err
	}
	info, err := os.Stat(parent)
	if err != nil {
		return nil, err
	}
	if info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("dashboard socket directory must be private")
	}
	if info, err := os.Lstat(path); err == nil {
		if info.Mode()&os.ModeSocket == 0 {
			return nil, errors.New("dashboard socket path is occupied by a non-socket")
		}
		conn, err := net.DialTimeout("unix", path, 500*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			return nil, errors.New("dashboard socket already has an active listener")
		}
		if err = os.Remove(path); err != nil {
			return nil, err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	if err = os.Chmod(path, 0600); err != nil {
		_ = listener.Close()
		return nil, err
	}
	return &privateListener{UnixListener: listener, uid: uint32(os.Getuid())}, nil
}
