package libcore

import (
	"errors"
	"testing"

	sblog "github.com/sagernet/sing-box/log"
)

func TestBoxPlatformLogWriterLevel(t *testing.T) {
	writer := newBoxPlatformLogWriter(sblog.LevelWarn)
	if !writer.allows(sblog.LevelError) {
		t.Fatal("warning threshold must allow errors")
	}
	if writer.allows(sblog.LevelInfo) {
		t.Fatal("warning threshold must reject info")
	}

	writer.SetLevel(sblog.LevelTrace)
	if !writer.allows(sblog.LevelTrace) {
		t.Fatal("updated trace threshold must allow trace")
	}
}

func TestParseLogLevel(t *testing.T) {
	if _, err := parseLogLevel("fatal"); err != nil {
		t.Fatal(err)
	}
	if _, err := parseLogLevel("none"); err == nil {
		t.Fatal("unknown log level must fail")
	}
}

type probeSocketPlatform struct {
	BoxPlatformInterface
	fd  int32
	err error
}

func (p *probeSocketPlatform) AutoDetectInterfaceControl(fd int32) error {
	p.fd = fd
	return p.err
}

func TestUIProbeSocketProtectionDoesNotUseRootSession(t *testing.T) {
	previousPlatform, previousBackground := intfBox, isBgProcess
	previousClient := currentTProxyClient()
	t.Cleanup(func() {
		intfBox, isBgProcess = previousPlatform, previousBackground
		tproxySession.Lock()
		tproxySession.client = previousClient
		tproxySession.Unlock()
	})
	// Even a present but unreachable root session must not block UI probes.
	if err := SetTProxySession(t.TempDir()+"/absent.sock", "01234567890123456789012345678901"); err != nil {
		t.Fatal(err)
	}
	platform := new(probeSocketPlatform)
	intfBox, isBgProcess = platform, false
	wrapper := &boxPlatformInterfaceWrapper{forTest: true, strictProtect: true}
	if err := wrapper.AutoDetectInterfaceControl(123); err != nil {
		t.Fatal(err)
	}
	if platform.fd != 123 {
		t.Fatalf("protected fd = %d", platform.fd)
	}
	platform.err = errors.New("physical network disappeared")
	if err := wrapper.AutoDetectInterfaceControl(124); !errors.Is(err, platform.err) {
		t.Fatalf("binding error = %v", err)
	}
}
