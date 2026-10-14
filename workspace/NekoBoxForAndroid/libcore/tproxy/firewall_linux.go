package tproxy

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"os/exec"
	"slices"
	"strconv"
	"strings"
	"time"
)

type commandRunner func(string, ...string) (string, error)

// The app's IPv6 FakeIP range is fc00::/18. Keep it captured even when
// bypassing private networks: a FakeIP has no meaning outside the core.
var privateIPv6Networks = []string{
	"fc00:4000::/18", "fc00:8000::/17", "fc01::/16", "fc02::/15",
	"fc04::/14", "fc08::/13", "fc10::/12", "fc20::/11", "fc40::/10",
	"fc80::/9", "fd00::/8",
}

func runCommand(name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	data, err := exec.CommandContext(ctx, name, args...).CombinedOutput()
	if err != nil {
		return string(data), fmt.Errorf("%s: %w: %s", name, err, strings.TrimSpace(string(data)))
	}
	return string(data), nil
}

type journal struct {
	Generation string            `json:"generation"`
	Config     Config            `json:"config"`
	Offload    map[string]string `json:"offload,omitempty"`
	BPFLimits  map[uint32]uint64 `json:"bpf_limits,omitempty"`
}

type Firewall struct {
	Generation     string
	HealthPort     int
	UID            int
	JournalPath    string
	run            commandRunner
	openOffloadMap func() (offloadLimitMap, error)
	state          journal
}

func NewFirewall(uid int, path string) *Firewall {
	return &Firewall{UID: uid, JournalPath: path, run: runCommand, openOffloadMap: openOffloadLimitMap}
}
func (f *Firewall) prefix() string        { return "NB4A_" + strconv.Itoa(f.UID) }
func (f *Firewall) table() string         { return strconv.Itoa(30000 + f.UID%10000) }
func (f *Firewall) priority() string      { return "9000" }
func (f *Firewall) guardPriority() string { return "9001" }
func (f *Firewall) ip(family string, args ...string) error {
	_, err := f.run("ip", append([]string{family}, args...)...)
	return err
}
func (f *Firewall) rule(bin, table string, args ...string) error {
	_, err := f.run(bin, append([]string{"-w", "2", "-t", table}, args...)...)
	return err
}

func (f *Firewall) save() error {
	data, err := json.Marshal(f.state)
	if err != nil {
		return err
	}
	tmp := f.JournalPath + ".tmp"
	if err = os.WriteFile(tmp, data, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, f.JournalPath)
}

func (f *Firewall) Validate(c Config) error {
	if c.UID != f.UID || !c.IPv4 && !c.IPv6 {
		return errors.New("invalid capture configuration")
	}
	for _, p := range []int{c.Port4, c.Port6, c.TetherPort4, c.TetherPort6} {
		if p < 1024 || p > 65535 {
			return errors.New("invalid transparent listener port")
		}
	}
	base := c.UID / 100000 * 100000
	for _, uid := range c.UIDs {
		if uid < base || uid >= base+100000 {
			return errors.New("capture UID outside current Android user")
		}
	}
	return nil
}

func (f *Firewall) Prepare(c Config) (err error) {
	if err = f.Validate(c); err != nil {
		return err
	}
	if err = f.Cleanup(); err != nil {
		return fmt.Errorf("stale capture cleanup: %w", err)
	}
	// Conflicts are checked before recording ownership. Otherwise rollback
	// could mistake somebody else's identical route for ours.
	if err = f.preflight(c); err != nil {
		return err
	}
	f.state = journal{Generation: f.Generation, Config: c, Offload: map[string]string{}}
	// Record intent before touching the device, including preparation failures.
	if err = f.save(); err != nil {
		return err
	}
	defer func() {
		if err != nil {
			err = errors.Join(err, f.Cleanup())
		}
	}()
	if c.Tether {
		if err = f.disableOffload(); err != nil {
			return err
		}
	}
	for _, family := range []string{"-4", "-6"} {
		bin := "iptables"
		enabled, port := c.IPv4, c.Port4
		if family == "-6" {
			bin, enabled, port = "ip6tables", c.IPv6, c.Port6
		}
		// Disabled families still need a leak guard; inability to install it is fatal.
		if err = f.prepareFamily(bin, family, c, enabled, port); err != nil {
			return err
		}
	}
	return nil
}

func (f *Firewall) preflight(c Config) error {
	for _, family := range []string{"-4", "-6"} {
		bin, enabled := "iptables", c.IPv4
		if family == "-6" {
			bin, enabled = "ip6tables", c.IPv6
		}
		for _, suffix := range []string{"_O", "_P", "_E", "_F", "_L", "_I", "_T"} {
			table := "mangle"
			if suffix == "_F" || suffix == "_L" || suffix == "_I" || suffix == "_T" {
				table = "filter"
			}
			if f.rule(bin, table, "-S", f.prefix()+suffix) == nil {
				return errors.New("unowned TPROXY chain already exists")
			}
		}
		if !enabled {
			continue
		}
		routes, err := f.run("ip", family, "route", "show", "table", f.table())
		if err != nil && !strings.Contains(routes, "FIB table does not exist") || err == nil && strings.TrimSpace(routes) != "" {
			return errors.New("TPROXY routing table unavailable")
		}
		rules, err := f.run("ip", family, "rule", "show")
		if err != nil {
			return err
		}
		for line := range strings.SplitSeq(rules, "\n") {
			if strings.HasPrefix(strings.TrimSpace(line), f.priority()+":") || strings.HasPrefix(strings.TrimSpace(line), f.guardPriority()+":") || strings.Contains(line, "0x40000000") || strings.Contains(line, "0x20000000") {
				return errors.New("TPROXY policy/mark conflict")
			}
		}
	}
	return nil
}

func (f *Firewall) prepareFamily(bin, family string, c Config, enabled bool, port int) error {
	p := f.prefix()
	for _, chain := range []string{p + "_O", p + "_P", p + "_E", p + "_F", p + "_L", p + "_I", p + "_T"} {
		table := "mangle"
		if strings.HasSuffix(chain, "_F") || strings.HasSuffix(chain, "_L") || strings.HasSuffix(chain, "_I") || strings.HasSuffix(chain, "_T") {
			table = "filter"
		}
		if err := f.rule(bin, table, "-N", chain); err != nil {
			return err
		}
	}
	appendRule := func(table, chain string, args ...string) error {
		return f.rule(bin, table, append([]string{"-A", chain}, args...)...)
	}
	// POSTROUTING sees the final interface after OUTPUT's mark reroutes the
	// packet. Never let a captured packet leave on a physical interface.
	if err := appendRule("mangle", p+"_E", "-m", "mark", "--mark", "0x40000000/0x40000000", "!", "-o", "lo", "-j", "DROP"); err != nil {
		return err
	}
	if enabled {
		ports := strconv.Itoa(port)
		if f.HealthPort > 0 {
			ports += "," + strconv.Itoa(f.HealthPort)
		}
		if c.Tether {
			tetherPort := c.TetherPort4
			if family == "-6" {
				tetherPort = c.TetherPort6
			}
			ports += "," + strconv.Itoa(tetherPort)
		}
		if err := appendRule("filter", p+"_L", "-m", "owner", "--uid-owner", strconv.Itoa(c.UID), "-j", "RETURN"); err != nil {
			return err
		}
		if err := appendRule("filter", p+"_L", "-m", "owner", "--uid-owner", "0", "-j", "RETURN"); err != nil {
			return err
		}
		for _, chain := range []string{p + "_L", p + "_I"} {
			if err := appendRule("filter", chain, "-m", "mark", "--mark", "0x20000000/0x20000000", "-j", "RETURN"); err != nil {
				return err
			}
			if err := appendRule("filter", chain, "-m", "mark", "--mark", "0x40000000/0x40000000", "-j", "RETURN"); err != nil {
				return err
			}
			for _, proto := range []string{"tcp", "udp"} {
				args := []string{"-p", proto, "-m", "multiport", "--dports", ports, "-j", "REJECT"}
				if chain == p+"_L" {
					args = append([]string{"-o", "lo"}, args...)
				}
				if err := appendRule("filter", chain, args...); err != nil {
					return err
				}
			}
		}
	}
	for _, chain := range []string{p + "_O", p + "_F"} {
		table := "mangle"
		if strings.HasSuffix(chain, "_F") {
			table = "filter"
		}
		for _, args := range [][]string{{"-m", "mark", "--mark", "0x20000000/0x20000000", "-j", "RETURN"}, {"-m", "mark", "--mark", "0x40000000/0x40000000", "-j", "RETURN"}, {"-m", "owner", "--uid-owner", "0", "-j", "RETURN"}} {
			if err := appendRule(table, chain, args...); err != nil {
				return err
			}
		}
		// Network.bindSocket sets Android's explicitlySelected network mark.
		// Let our UI probes use that physical network without a root broker;
		// other apps must still be captured even when they bind their sockets.
		if err := appendRule(table, chain, "-m", "owner", "--uid-owner", strconv.Itoa(c.UID), "-m", "mark", "--mark", "0x10000/0x10000", "-j", "RETURN"); err != nil {
			return err
		}
		base := c.UID / 100000 * 100000
		if err := appendRule(table, chain, "-m", "owner", "!", "--uid-owner", fmt.Sprintf("%d-%d", base, base+99999), "-j", "RETURN"); err != nil {
			return err
		}
		// Inclusion/exclusion is at UID granularity, as with Android's VPN builder.
		if !c.AllApps && c.Exclude {
			for _, uid := range slices.Compact(slices.Sorted(slices.Values(c.UIDs))) {
				if err := appendRule(table, chain, "-m", "owner", "--uid-owner", strconv.Itoa(uid), "-j", "RETURN"); err != nil {
					return err
				}
			}
		}
		// Local services, multicast and link-local control traffic never leave through a proxy.
		locals := []string{"127.0.0.0/8", "224.0.0.0/3", "169.254.0.0/16"}
		if family == "-6" {
			locals = []string{"::1/128", "ff00::/8", "fe80::/10"}
		}
		if c.BypassLAN {
			if family == "-4" {
				locals = append(locals, "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
			} else {
				locals = append(locals, privateIPv6Networks...)
			}
		}
		for _, cidr := range locals {
			if err := appendRule(table, chain, "-d", cidr, "-j", "RETURN"); err != nil {
				return err
			}
		}
		if family == "-6" {
			for _, kind := range []string{"destination-unreachable", "packet-too-big", "time-exceeded", "parameter-problem", "router-solicitation", "router-advertisement", "neighbour-solicitation", "neighbour-advertisement"} {
				if err := appendRule(table, chain, "-p", "ipv6-icmp", "--icmpv6-type", kind, "-j", "RETURN"); err != nil {
					return err
				}
			}
		}
		var selectors [][]string
		if c.AllApps || c.Exclude {
			selectors = [][]string{{}}
		} else {
			uids := append(slices.Clone(c.UIDs), 1051)
			for _, uid := range slices.Compact(slices.Sorted(slices.Values(uids))) {
				selectors = append(selectors, []string{"-m", "owner", "--uid-owner", strconv.Itoa(uid)})
			}
		}
		for _, selector := range selectors {
			if table == "mangle" && enabled {
				for _, proto := range []string{"tcp", "udp"} {
					args := append(slices.Clone(selector), "-p", proto, "-j", "MARK", "--set-xmark", "0x40000000/0x40000000")
					if err := appendRule(table, chain, args...); err != nil {
						return err
					}
				}
			} else if table == "filter" {
				// Captured TCP/UDP has returned above with CaptureMark. Everything else
				// is rejected, including disabled families and force-bound route escapes.
				if err := appendRule(table, chain, append(slices.Clone(selector), "-j", "REJECT")...); err != nil {
					return err
				}
			}
		}
	}
	if !enabled {
		return nil
	}
	var err error
	network := "0.0.0.0/0"
	if family == "-6" {
		network = "::/0"
	}
	if err = f.ip(family, "route", "add", "local", network, "dev", "lo", "table", f.table()); err != nil {
		return err
	}
	if err = f.ip(family, "rule", "add", "priority", f.priority(), "fwmark", "0x40000000/0x40000000", "lookup", f.table()); err != nil {
		return err
	}
	// SO_BINDTODEVICE can make the local route ineligible. Never fall through
	// to Android's physical-network rules in that case. An OUTPUT -o test
	// cannot do this: its hook state still names the interface before rerouting.
	if err = f.ip(family, "rule", "add", "priority", f.guardPriority(), "fwmark", "0x40000000/0x40000000", "unreachable"); err != nil {
		return err
	}
	for _, proto := range []string{"tcp", "udp"} {
		if err = appendRule("mangle", p+"_P", "-m", "mark", "--mark", "0x40000000/0x40000000", "-p", proto, "-j", "TPROXY", "--on-port", strconv.Itoa(port), "--tproxy-mark", "0x40000000/0x40000000"); err != nil {
			return err
		}
	}
	return f.rule(bin, "mangle", "-I", "PREROUTING", "1", "-j", p+"_P")
}

func (f *Firewall) Activate() (err error) {
	defer func() {
		if err != nil {
			err = errors.Join(err, f.Cleanup())
		}
	}()
	c := f.state.Config
	for _, bin := range []string{"iptables", "ip6tables"} {
		if c.Tether {
			if err = f.activateTether(bin, c); err != nil {
				return err
			}
		}
		if err = f.rule(bin, "filter", "-I", "INPUT", "1", "-j", f.prefix()+"_I"); err != nil {
			return err
		}
		if err = f.rule(bin, "filter", "-I", "OUTPUT", "1", "-j", f.prefix()+"_L"); err != nil {
			return err
		}
		if err = f.rule(bin, "filter", "-I", "OUTPUT", "1", "-j", f.prefix()+"_F"); err != nil {
			return err
		}
		if err = f.rule(bin, "mangle", "-I", "POSTROUTING", "1", "-j", f.prefix()+"_E"); err != nil {
			return err
		}
		if err = f.rule(bin, "mangle", "-I", "OUTPUT", "1", "-j", f.prefix()+"_O"); err != nil {
			return err
		}
	}
	return nil
}

func (f *Firewall) activateTether(bin string, c Config) error {
	interfaces, err := f.downstreams()
	if err != nil {
		return err
	}
	port, enabled := c.TetherPort4, c.IPv4
	if bin == "ip6tables" {
		port, enabled = c.TetherPort6, c.IPv6
	}
	for _, iface := range interfaces {
		if enabled {
			gateways, addressErr := f.localAddresses(iface, bin == "ip6tables")
			if addressErr != nil {
				return addressErr
			}
			locals := []string{"127.0.0.0/8", "224.0.0.0/3", "169.254.0.0/16"}
			if bin == "ip6tables" {
				locals = []string{"::1/128", "ff00::/8", "fe80::/10"}
			}
			if c.BypassLAN {
				if bin == "iptables" {
					locals = append(locals, "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
				} else {
					locals = append(locals, privateIPv6Networks...)
				}
			}
			for _, cidr := range locals {
				if err = f.rule(bin, "mangle", "-A", f.prefix()+"_P", "-i", iface, "-d", cidr, "-j", "RETURN"); err != nil {
					return err
				}
				if err = f.rule(bin, "filter", "-A", f.prefix()+"_T", "-i", iface, "-d", cidr, "-j", "RETURN"); err != nil {
					return err
				}
			}
			for _, gateway := range gateways {
				for _, proto := range []string{"tcp", "udp"} {
					if err = f.rule(bin, "mangle", "-A", f.prefix()+"_P", "-i", iface, "-d", gateway, "-p", proto, "!", "--dport", "53", "-j", "RETURN"); err != nil {
						return err
					}
				}
			}
			for _, proto := range []string{"tcp", "udp"} {
				if err = f.rule(bin, "mangle", "-A", f.prefix()+"_P", "-i", iface, "-p", proto, "-j", "TPROXY", "--on-port", strconv.Itoa(port), "--tproxy-mark", "0x40000000/0x40000000"); err != nil {
					return err
				}
			}
		}
		// Anything that escaped transparent delivery must not be forwarded directly.
		if err = f.rule(bin, "filter", "-A", f.prefix()+"_T", "-i", iface, "-j", "REJECT"); err != nil {
			return err
		}
		if err = f.rule(bin, "filter", "-I", "FORWARD", "1", "-i", iface, "-m", "comment", "--comment", f.prefix(), "-j", f.prefix()+"_T"); err != nil {
			return err
		}
	}
	return nil
}

func (f *Firewall) localAddresses(iface string, ipv6 bool) ([]string, error) {
	output, err := f.run("ip", "-o", "address", "show", "dev", iface)
	if err != nil {
		return nil, err
	}
	var addresses []string
	for _, line := range strings.Split(output, "\n") {
		fields := strings.Fields(line)
		for i := 0; i+1 < len(fields); i++ {
			if fields[i] != "inet" && fields[i] != "inet6" {
				continue
			}
			prefix, parseErr := netip.ParsePrefix(fields[i+1])
			if parseErr != nil {
				return nil, parseErr
			}
			if prefix.Addr().Is6() == ipv6 {
				addresses = append(addresses, prefix.Addr().String())
			}
		}
	}
	return addresses, nil
}

func (f *Firewall) downstreams() ([]string, error) {
	output, err := f.run("dumpsys", "tethering")
	if err != nil {
		return nil, err
	}
	var interfaces []string
	for line := range strings.SplitSeq(output, "\n") {
		// IpServer's current-state record uses "iface - TetheredState".
		// The dump also includes timestamped historical log messages containing
		// TetheredState. Those are not interface records.
		fields := strings.Fields(line)
		if len(fields) < 3 || fields[1] != "-" || fields[2] != "TetheredState" {
			continue
		}
		iface := fields[0]
		if strings.ContainsAny(iface, " /:\t") || len(iface) > 15 {
			return nil, errors.New("invalid tether interface")
		}
		interfaces = append(interfaces, iface)
	}
	return slices.Compact(slices.Sorted(slices.Values(interfaces))), nil
}

func (f *Firewall) disableOffload() error {
	settings := []struct {
		key      string
		get, set []string
	}{
		{"hardware", []string{"settings", "get", "global", "tether_offload_disabled"}, []string{"settings", "put", "global", "tether_offload_disabled", "1"}},
	}
	for _, setting := range settings {
		previous, err := f.run(setting.get[0], setting.get[1:]...)
		if err != nil {
			return err
		}
		f.state.Offload[setting.key] = strings.TrimSpace(previous)
		if err = f.save(); err != nil {
			return err
		}
		if _, err = f.run(setting.set[0], setting.set[1:]...); err != nil {
			return err
		}
	}
	return f.verifyOffload()
}

func (f *Firewall) verifyOffload() error {
	dump, err := f.run("dumpsys", "tethering")
	if err != nil {
		return err
	}
	if !strings.Contains(dump, "Offload disabled") {
		return errors.New("hardware tethering offload is still active or unverifiable; restart hotspot before connecting")
	}
	// mIsBpfEnabled is a capability latched at service construction, not a
	// live switch. Changing device_config and restarting the hotspot cannot
	// turn it off. Suppress forwarding in the live offload map instead.
	if strings.Contains(dump, "mIsBpfEnabled: false") {
		return nil
	}
	if !strings.Contains(dump, "mIsBpfEnabled: true") {
		return errors.New("unable to verify tethering BPF offload state")
	}
	return f.suppressBPFOffload()
}

func (f *Firewall) Cleanup() error {
	data, readErr := os.ReadFile(f.JournalPath)
	if errors.Is(readErr, os.ErrNotExist) {
		return nil
	}
	if readErr != nil {
		return readErr
	}
	if err := json.Unmarshal(data, &f.state); err != nil {
		return fmt.Errorf("invalid TPROXY ownership journal: %w", err)
	}
	if f.state.Config.UID != f.UID {
		return errors.New("TPROXY journal owner mismatch")
	}
	// An old supervisor must never remove a newer session's firewall.
	if f.Generation != "" && f.state.Generation != f.Generation {
		return nil
	}
	var errs []error
	for _, family := range []string{"-4", "-6"} {
		bin := "iptables"
		if family == "-6" {
			bin = "ip6tables"
		}
		for _, hook := range []struct{ table, chain, target string }{{"mangle", "OUTPUT", "_O"}, {"filter", "OUTPUT", "_F"}, {"filter", "OUTPUT", "_L"}, {"filter", "INPUT", "_I"}, {"mangle", "PREROUTING", "_P"}, {"mangle", "POSTROUTING", "_E"}} {
			for {
				if f.rule(bin, hook.table, "-C", hook.chain, "-j", f.prefix()+hook.target) != nil {
					break
				}
				if err := f.rule(bin, hook.table, "-D", hook.chain, "-j", f.prefix()+hook.target); err != nil {
					errs = append(errs, err)
					break
				}
			}
		}
		// Remove only our exact commented FORWARD entries, including stale interfaces.
		if output, err := f.run(bin, "-w", "2", "-t", "filter", "-S", "FORWARD"); err == nil {
			for _, line := range strings.Split(output, "\n") {
				fields := strings.Fields(line)
				for i := 0; i+1 < len(fields); i++ {
					if fields[i] == "--comment" && strings.Trim(fields[i+1], "\"") == f.prefix() {
						fields[0] = "-D"
						fields[i+1] = f.prefix()
						if err = f.rule(bin, "filter", fields...); err != nil {
							errs = append(errs, err)
						}
						break
					}
				}
			}
		} else {
			errs = append(errs, err)
		}
		for _, chain := range []string{"_O", "_P", "_E", "_F", "_L", "_I", "_T"} {
			table := "mangle"
			if chain == "_F" || chain == "_L" || chain == "_I" || chain == "_T" {
				table = "filter"
			}
			if f.rule(bin, table, "-S", f.prefix()+chain) == nil {
				if err := f.rule(bin, table, "-F", f.prefix()+chain); err != nil {
					errs = append(errs, err)
				}
				if err := f.rule(bin, table, "-X", f.prefix()+chain); err != nil {
					errs = append(errs, err)
				}
			}
		}
		// Exact matches, never a table flush or deletion by priority alone.
		if rules, err := f.run("ip", family, "rule", "show"); err == nil {
			for _, line := range strings.Split(rules, "\n") {
				if strings.HasPrefix(strings.TrimSpace(line), f.guardPriority()+":") && strings.Contains(line, "fwmark 0x40000000/0x40000000") && strings.Contains(line, "unreachable") {
					if err = f.ip(family, "rule", "del", "priority", f.guardPriority(), "fwmark", "0x40000000/0x40000000", "unreachable"); err != nil {
						errs = append(errs, err)
					}
				}
				if strings.HasPrefix(strings.TrimSpace(line), f.priority()+":") && strings.Contains(line, "fwmark 0x40000000/0x40000000") && strings.Contains(line, "lookup "+f.table()) {
					if err = f.ip(family, "rule", "del", "priority", f.priority(), "fwmark", "0x40000000/0x40000000", "lookup", f.table()); err != nil {
						errs = append(errs, err)
					}
				}
			}
		} else {
			errs = append(errs, err)
		}
		network := "0.0.0.0/0"
		if family == "-6" {
			network = "::/0"
		}
		// A missing route is normal after reboot or partial preparation.
		if output, err := f.run("ip", family, "route", "show", "table", f.table()); err == nil {
			if strings.Contains(output, "local default dev lo") {
				if err = f.ip(family, "route", "del", "local", network, "dev", "lo", "table", f.table()); err != nil {
					errs = append(errs, err)
				}
			}
		} else if !strings.Contains(output, "FIB table does not exist") {
			errs = append(errs, err)
		}
		// A failed query is not proof of absence. Keep the journal until both
		// tables confirm that no owned chain or hook survived the teardown.
		for _, table := range []string{"mangle", "filter"} {
			output, err := f.run(bin, "-w", "2", "-t", table, "-S")
			if err != nil {
				errs = append(errs, err)
			} else if strings.Contains(output, f.prefix()+"_") {
				errs = append(errs, fmt.Errorf("%s %s: stale TPROXY rules", bin, table))
			}
		}
	}
	if err := f.restoreBPFOffload(); err != nil {
		errs = append(errs, err)
	}
	for key, previous := range f.state.Offload {
		name, args, ours := "settings", []string{"get", "global", "tether_offload_disabled"}, "1"
		if key == "bpf" {
			name, args, ours = "device_config", []string{"get", "connectivity", "override_tether_enable_bpf_offload"}, "false"
		}
		current, err := f.run(name, args...)
		if err != nil {
			errs = append(errs, err)
			continue
		}
		if strings.TrimSpace(current) != ours {
			continue
		} // Preserve subsequent user changes.
		args[0] = "put"
		if previous == "null" || previous == "" {
			args[0] = "delete"
		} else {
			args = append(args, previous)
		}
		if _, err = f.run(name, args...); err != nil {
			errs = append(errs, err)
		}
	}
	if err := errors.Join(errs...); err != nil {
		return err
	}
	f.state = journal{}
	return os.Remove(f.JournalPath)
}
