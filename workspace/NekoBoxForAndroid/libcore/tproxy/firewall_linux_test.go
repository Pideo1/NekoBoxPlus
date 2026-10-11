package tproxy

import (
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

type firewallFixture struct {
	commands []string
	chains   map[string]bool
	hooks    map[string]bool
	routes   map[string]bool
	rules    map[string]bool
	fail     string
}

func TestLANBypassPreservesIPv6FakeIP(t *testing.T) {
	for _, address := range []string{"fc00::1", "fc00:3fff:ffff::1"} {
		for _, cidr := range privateIPv6Networks {
			if netip.MustParsePrefix(cidr).Contains(netip.MustParseAddr(address)) {
				t.Fatalf("FakeIP %s bypassed by %s", address, cidr)
			}
		}
	}
	for _, address := range []string{"fc00:4000::1", "fc00:ffff::1", "fc01::1", "fdff:ffff::1"} {
		if !slices.ContainsFunc(privateIPv6Networks, func(cidr string) bool {
			return netip.MustParsePrefix(cidr).Contains(netip.MustParseAddr(address))
		}) {
			t.Fatalf("private address %s no longer bypassed", address)
		}
	}
}

func (x *firewallFixture) run(name string, args ...string) (string, error) {
	cmd := name + " " + strings.Join(args, " ")
	x.commands = append(x.commands, cmd)
	if x.fail != "" && strings.Contains(cmd, x.fail) {
		x.fail = ""
		return "", errors.New("injected failure")
	}
	if name == "ip" {
		family := args[0]
		if slices.Equal(args[1:3], []string{"rule", "show"}) {
			output := "0: from all lookup local"
			if x.rules[family] {
				output += "\n9000: from all fwmark 0x40000000/0x40000000 lookup 30123"
			}
			if x.rules[family+"guard"] {
				output += "\n9001: from all fwmark 0x40000000/0x40000000 unreachable"
			}
			return output, nil
		}
		if args[1] == "route" && args[2] == "show" {
			if x.routes[family] {
				return "local default dev lo scope host", nil
			}
			return "", nil
		}
		if args[1] == "route" {
			x.routes[family] = args[2] == "add"
		}
		if args[1] == "rule" {
			key := family
			if args[len(args)-1] == "unreachable" {
				key += "guard"
			}
			x.rules[key] = args[2] == "add"
		}
		return "", nil
	}
	if name == "iptables" || name == "ip6tables" {
		args = args[4:]
		if slices.Equal(args, []string{"-S"}) {
			for _, state := range []map[string]bool{x.chains, x.hooks} {
				for key := range state {
					if strings.HasPrefix(key, name) {
						return "NB4A_10123_stale", nil
					}
				}
			}
			return "", nil
		}
		op, chain := args[0], args[1]
		key := name + chain
		switch op {
		case "-N":
			x.chains[key] = true
		case "-X":
			delete(x.chains, key)
		case "-S":
			if chain == "FORWARD" {
				return "", nil
			}
			if !x.chains[key] {
				return "", errors.New("missing chain")
			}
		case "-I":
			x.hooks[name+chain+args[len(args)-1]] = true
		case "-C":
			if !x.hooks[name+chain+args[len(args)-1]] {
				return "", errors.New("missing hook")
			}
		case "-D":
			delete(x.hooks, name+chain+args[len(args)-1])
		}
		return "", nil
	}
	return "", errors.New("unexpected command: " + cmd)
}

func fixture(t *testing.T) (*Firewall, *firewallFixture, Config) {
	x := &firewallFixture{chains: map[string]bool{}, hooks: map[string]bool{}, routes: map[string]bool{}, rules: map[string]bool{}}
	f := NewFirewall(10123, filepath.Join(t.TempDir(), "journal"))
	f.run = x.run
	c := Config{UID: 10123, Port4: 32080, Port6: 32081, TetherPort4: 32082, TetherPort6: 32083, IPv4: true, IPv6: true, AllApps: true}
	return f, x, c
}

func TestCaptureAttachesOnlyAfterPreparationAndCleansBeforeRoutes(t *testing.T) {
	f, x, c := fixture(t)
	if err := f.Prepare(c); err != nil {
		t.Fatal(err)
	}
	for _, cmd := range x.commands {
		if strings.Contains(cmd, "-I OUTPUT") {
			t.Fatal("traffic diverted before activation")
		}
	}
	if err := f.Activate(); err != nil {
		t.Fatal(err)
	}
	x.commands = nil
	if err := f.Cleanup(); err != nil {
		t.Fatal(err)
	}
	firstDetach, lastRoute := -1, -1
	for i, cmd := range x.commands {
		if strings.Contains(cmd, "-D OUTPUT") && firstDetach < 0 {
			firstDetach = i
		}
		if strings.Contains(cmd, "rule del") {
			lastRoute = i
		}
	}
	if firstDetach < 0 || lastRoute <= firstDetach {
		t.Fatal("routes removed before capture detached")
	}
	if len(x.chains) != 0 || len(x.hooks) != 0 || x.routes["-4"] || x.routes["-6"] || x.rules["-4"] || x.rules["-6"] || x.rules["-4guard"] || x.rules["-6guard"] {
		t.Fatal("stale networking state")
	}
	if err := f.Cleanup(); err != nil {
		t.Fatal("cleanup is not idempotent:", err)
	}
}

func TestPartialActivationRollsBackBothFamilies(t *testing.T) {
	f, x, c := fixture(t)
	if err := f.Prepare(c); err != nil {
		t.Fatal(err)
	}
	x.fail = "ip6tables -w 2 -t mangle -I OUTPUT"
	if err := f.Activate(); err == nil {
		t.Fatal("failure ignored")
	}
	if len(x.hooks) != 0 || len(x.chains) != 0 || x.rules["-4"] || x.rules["-6"] {
		t.Fatal("failed activation stranded rules")
	}
	if _, err := os.Stat(f.JournalPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("completed rollback retained journal")
	}
}

func TestConflictIsNotClaimedOrRemoved(t *testing.T) {
	f, x, c := fixture(t)
	x.routes["-4"], x.rules["-4"] = true, true
	if err := f.Prepare(c); err == nil {
		t.Fatal("accepted occupied routing table")
	}
	if !x.routes["-4"] || !x.rules["-4"] {
		t.Fatal("removed unowned routes")
	}
	if _, err := os.Stat(f.JournalPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("claimed unowned state")
	}
}

func TestDisabledFamilyRejectsAndPreservesNetdMarks(t *testing.T) {
	f, x, c := fixture(t)
	c.IPv6 = false
	if err := f.Prepare(c); err != nil {
		t.Fatal(err)
	}
	if x.routes["-6"] {
		t.Fatal("routed disabled family")
	}
	found := false
	for _, cmd := range x.commands {
		if strings.Contains(cmd, "--set-mark") {
			t.Fatal("clobbered Android netd marks")
		}
		if strings.HasPrefix(cmd, "ip6tables") && strings.Contains(cmd, "_F -j REJECT") {
			found = true
		}
	}
	if !found {
		t.Fatal("disabled family can escape")
	}
}
