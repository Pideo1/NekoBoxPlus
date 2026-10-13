package main

import (
	"flag"
	"fmt"
	"libcore/tproxy"
	"os"
)

func main() {
	s := tproxy.Server{}
	flag.IntVar(&s.UID, "uid", 0, "owner app UID")
	flag.IntVar(&s.Parent, "parent", 0, "owner process PID")
	flag.StringVar(&s.Path, "socket", "", "private Unix socket")
	flag.StringVar(&s.Token, "token", "", "session token")
	flag.StringVar(&s.JournalPath, "journal", "", "ownership journal")
	cleanup := flag.Bool("cleanup", false, "remove journaled capture state")
	flag.Parse()
	if os.Getuid() != 0 || s.JournalPath == "" || s.UID < 10000 {
		fmt.Fprintln(os.Stderr, "root and valid owner/journal required")
		os.Exit(1)
	}
	var err error
	if *cleanup {
		firewall := tproxy.NewFirewall(s.UID, s.JournalPath)
		firewall.Generation = s.Token
		err = firewall.Cleanup()
	} else {
		err = s.Run()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
