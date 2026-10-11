// Package tproxy implements the bounded, app-UID-authenticated root broker protocol.
package tproxy

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"time"

	"golang.org/x/sys/unix"
)

const Version = 1
const CaptureMark = 0x40000000
const BypassMark = 0x20000000
const maxMessage = 65536
const maxAncillary = 512 // Android may also deliver peer credentials/security labels.

type Config struct {
	UID         int   `json:"uid"`
	Port4       int   `json:"port4"`
	Port6       int   `json:"port6"`
	TetherPort4 int   `json:"tether_port4"`
	TetherPort6 int   `json:"tether_port6"`
	IPv4        bool  `json:"ipv4"`
	IPv6        bool  `json:"ipv6"`
	AllApps     bool  `json:"all_apps"`
	Exclude     bool  `json:"exclude"`
	UIDs        []int `json:"uids"`
	BypassLAN   bool  `json:"bypass_lan"`
	Tether      bool  `json:"tether"`
}

type Request struct {
	Version     int     `json:"version"`
	Token       string  `json:"token"`
	Op          string  `json:"op"`
	Network     string  `json:"network,omitempty"`
	Address     string  `json:"address,omitempty"`
	Reply       bool    `json:"reply,omitempty"`
	Protocol    int     `json:"protocol,omitempty"`
	Source      string  `json:"source,omitempty"`
	Destination string  `json:"destination,omitempty"`
	Config      *Config `json:"config,omitempty"`
}

type Response struct {
	Error      string `json:"error,omitempty"`
	UID        int    `json:"uid"`
	HealthPort int    `json:"health_port"`
	Active     bool   `json:"active"`
}

type Client struct{ Path, Token string }

func (c Client) Call(request Request, fd int) (Response, int, error) {
	request.Version, request.Token = Version, c.Token
	conn, err := net.DialUnix("unixpacket", nil, &net.UnixAddr{Name: c.Path, Net: "unixpacket"})
	if err != nil {
		return Response{}, -1, err
	}
	defer conn.Close()
	timeout := 3 * time.Second
	if request.Op == "prepare" || request.Op == "activate" || request.Op == "cleanup" || request.Op == "shutdown" {
		timeout = 20 * time.Second
	}
	_ = conn.SetDeadline(time.Now().Add(timeout))
	data, err := json.Marshal(request)
	if err != nil || len(data) > maxMessage {
		return Response{}, -1, errors.New("invalid broker request")
	}
	var rights []byte
	if fd >= 0 {
		rights = unix.UnixRights(fd)
	}
	if _, _, err = conn.WriteMsgUnix(data, rights, nil); err != nil {
		return Response{}, -1, err
	}
	data = make([]byte, maxMessage)
	oob := make([]byte, maxAncillary)
	n, on, flags, _, err := conn.ReadMsgUnix(data, oob)
	if err != nil {
		return Response{}, -1, err
	}
	gotFD, rightsErr := receivedFD(oob[:on])
	if flags&(unix.MSG_TRUNC|unix.MSG_CTRUNC) != 0 {
		rightsErr = fmt.Errorf("truncated broker response (flags=%#x ancillary=%d)", flags, on)
	}
	var response Response
	if err = json.Unmarshal(data[:n], &response); err == nil {
		err = rightsErr
	}
	if err == nil && response.Error != "" {
		err = fmt.Errorf("tproxy: %s", response.Error)
	}
	if err != nil && gotFD >= 0 {
		_ = unix.Close(gotFD)
		gotFD = -1
	}
	return response, gotFD, err
}

func receivedFD(oob []byte) (int, error) {
	messages, err := unix.ParseSocketControlMessage(oob)
	if err != nil {
		return -1, err
	}
	var fds []int
	for _, message := range messages {
		if message.Header.Level != unix.SOL_SOCKET || message.Header.Type != unix.SCM_RIGHTS {
			continue
		}
		rights, e := unix.ParseUnixRights(&message)
		if e != nil {
			for _, fd := range fds {
				_ = unix.Close(fd)
			}
			return -1, e
		}
		fds = append(fds, rights...)
	}
	if len(fds) > 1 {
		for _, fd := range fds {
			_ = unix.Close(fd)
		}
		return -1, errors.New("expected at most one descriptor")
	}
	if len(fds) == 0 {
		return -1, nil
	}
	unix.CloseOnExec(fds[0])
	return fds[0], nil
}
