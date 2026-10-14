package tproxy

import (
	"errors"
	"maps"
	"os"
	"slices"
	"strings"
	"testing"

	"golang.org/x/sys/unix"
)

type fakeOffloadLimitMap struct {
	values map[uint32]uint64
	fail   error
}

func (m *fakeOffloadLimitMap) Entries() (map[uint32]uint64, error) {
	return maps.Clone(m.values), nil
}
func (m *fakeOffloadLimitMap) Update(key uint32, value uint64) error {
	if m.fail != nil {
		return m.fail
	}
	if _, exists := m.values[key]; !exists {
		return unix.ENOENT
	}
	m.values[key] = value
	return nil
}
func (m *fakeOffloadLimitMap) Close() error { return nil }

func tetherFixture(t *testing.T) (*Firewall, *firewallFixture, Config, *fakeOffloadLimitMap) {
	f, x, c := fixture(t)
	c.Tether = true
	m := &fakeOffloadLimitMap{values: map[uint32]uint64{7: 1<<63 - 1, 8: 4096}}
	f.openOffloadMap = func() (offloadLimitMap, error) { return m, nil }
	f.run = func(name string, args ...string) (string, error) {
		switch name {
		case "dumpsys":
			return "Hardware offload:\n  Offload disabled\nBPF offload:\n  mIsBpfEnabled: true\n", nil
		case "settings":
			x.commands = append(x.commands, name+" "+strings.Join(args, " "))
			if args[0] == "get" {
				if len(f.state.Offload) == 0 {
					return "null", nil
				}
				return "1", nil
			}
			return "", nil
		default:
			return x.run(name, args...)
		}
	}
	return f, x, c, m
}

func TestTetheringStartsWithLatchedBPFAndNoHotspot(t *testing.T) {
	f, x, c, m := tetherFixture(t)
	if err := f.Prepare(c); err != nil {
		t.Fatal(err)
	}
	for _, value := range m.values {
		if value != 0 {
			t.Fatal("BPF forwarding still enabled")
		}
	}
	if err := f.Activate(); err != nil {
		t.Fatal(err)
	}
	// Android can change upstream quotas while the broker is running.
	m.values[7] = 2222
	if err := f.verifyOffload(); err != nil {
		t.Fatal(err)
	}
	if m.values[7] != 0 {
		t.Fatal("new quota re-enabled BPF forwarding")
	}
	m.values[8] = 600 // A subsequent change must survive cleanup.
	if err := f.Cleanup(); err != nil {
		t.Fatal(err)
	}
	if m.values[7] != 2222 || m.values[8] != 600 {
		t.Fatalf("cleanup restored stale quotas: %v", m.values)
	}
	for _, cmd := range x.commands {
		if strings.Contains(cmd, "device_config") {
			t.Fatal("changed the latched BPF feature flag")
		}
	}
}

func TestTetheringPreparationFailureRestoresOffload(t *testing.T) {
	f, x, c, m := tetherFixture(t)
	x.fail = "iptables -w 2 -t mangle -N"
	if err := f.Prepare(c); err == nil {
		t.Fatal("injected firewall failure ignored")
	}
	if m.values[7] != 1<<63-1 || m.values[8] != 4096 {
		t.Fatalf("failed startup stranded disabled offload: %v", m.values)
	}
	if _, err := os.Stat(f.JournalPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("completed rollback retained journal")
	}
}

func TestBPFLimitsAreJournaledBeforeMutation(t *testing.T) {
	f, _, _, m := tetherFixture(t)
	f.JournalPath = t.TempDir() // Renaming a file over a directory must fail.
	if err := f.suppressBPFOffload(); err == nil {
		t.Fatal("journal failure ignored")
	}
	if m.values[7] != 1<<63-1 || m.values[8] != 4096 {
		t.Fatal("offload changed without a durable journal")
	}
}

func TestTetheringFailsWhenBPFMapCannotBeControlled(t *testing.T) {
	f, _, c, _ := tetherFixture(t)
	f.openOffloadMap = func() (offloadLimitMap, error) { return nil, unix.EPERM }
	if err := f.Prepare(c); !errors.Is(err, unix.EPERM) {
		t.Fatalf("uncontrolled BPF offload accepted: %v", err)
	}
}

func TestBPFRestorationFailureRetainsJournalForRetry(t *testing.T) {
	f, _, c, m := tetherFixture(t)
	if err := f.Prepare(c); err != nil {
		t.Fatal(err)
	}
	m.fail = unix.EPERM
	if err := f.Cleanup(); !errors.Is(err, unix.EPERM) {
		t.Fatalf("restoration failure ignored: %v", err)
	}
	if _, err := os.Stat(f.JournalPath); err != nil {
		t.Fatal("lost ownership journal before restoring offload", err)
	}
	m.fail = nil
	if err := f.Cleanup(); err != nil {
		t.Fatal("retry failed:", err)
	}
	if m.values[7] != 1<<63-1 || m.values[8] != 4096 {
		t.Fatalf("retry did not restore quotas: %v", m.values)
	}
}

func TestDownstreamsIgnoreHistoricalTetheringLogs(t *testing.T) {
	f, _, _ := fixture(t)
	f.run = func(string, ...string) (string, error) {
		return `  Tether state:
    wlan0 - TetheredState - lastError = 0
    rndis0 - TetheredState - lastError = 0
    wlan0 - TetheredState - lastError = 0
    wlan1 - AvailableState - lastError = 0
    Upstream wanted: true
    Current upstream interface(s): [eth0]
  Log:
    2026-10-10T20:00:26.235684 - [wlan0] TetheredState got CMD_TETHER_CONNECTION_CHANGED
    2026-10-10T20:00:26.239815 - [wlan0] TetheredState got CMD_TETHER_CONNECTION_CHANGED
`, nil
	}
	interfaces, err := f.downstreams()
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(interfaces, []string{"rndis0", "wlan0"}) {
		t.Fatalf("unexpected tether interfaces: %v", interfaces)
	}
}
