package libcore

import (
	"context"
	"io"
	"log"
	"net"
	"sync"
	"time"
)

var (
	protectAccess sync.Mutex
	protectCloser io.Closer
	protectMain   bool
	protectUsers  int
)

func ensureProtectLocked() {
	if protectCloser != nil {
		return
	}
	if !isBgProcess && protectServerAvailable() {
		return
	}
	var err error
	protectCloser, err = serveCheckedProtect(protectPath)
	if err != nil {
		log.Println("start protect server:", err)
	}
}

func protectServerAvailable() bool {
	netDialer := net.Dialer{Timeout: 100 * time.Millisecond}
	conn, err := netDialer.DialContext(context.Background(), "unix", protectPath)
	if err != nil {
		return false
	}
	if err := conn.Close(); err != nil {
		return false
	}
	return true
}

func closeProtectLocked() {
	if protectCloser == nil {
		return
	}
	if err := protectCloser.Close(); err != nil {
		log.Println("close protect server:", err)
	}
	protectCloser = nil
}

func acquireProtect() {
	protectAccess.Lock()
	defer protectAccess.Unlock()
	protectUsers++
	ensureProtectLocked()
}

func releaseProtect() {
	protectAccess.Lock()
	defer protectAccess.Unlock()
	if protectUsers > 0 {
		protectUsers--
	}
	if protectUsers == 0 && !protectMain {
		closeProtectLocked()
	}
}

func goServeProtect(start bool) {
	protectAccess.Lock()
	defer protectAccess.Unlock()
	protectMain = start
	if start {
		ensureProtectLocked()
		return
	}
	if protectUsers == 0 {
		closeProtectLocked()
	}
}
