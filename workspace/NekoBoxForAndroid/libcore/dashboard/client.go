// SPDX-License-Identifier: GPL-3.0-or-later
// Dashboard transport follows Husi's coreclient bridge architecture.
package dashboard

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/daemon"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protoreflect"
	"google.golang.org/protobuf/types/dynamicpb"
	"google.golang.org/protobuf/types/known/structpb"
)

const controlPrefix = "/dashboard.Control/"
const daemonPrefix = "/daemon.StartedService/"

// Only this surface is reachable, even by another client of the same UID.
var allowedMethods = map[string]bool{
	"SubscribeStatus": true, "SubscribeConnections": true,
	"SubscribeGroups": true, "SubscribeOutbounds": true, "SelectOutbound": true,
	"SetGroupExpand": true, "GetClashModeStatus": true, "SubscribeClashMode": true,
	"SetClashMode": true, "CloseConnection": true, "CloseAllConnections": true,
	"SubscribeOpenVPNStatus": true, "SubmitOpenVPNChallengeResponse": true,
	"CancelOpenVPNChallenge": true, "SubscribeOpenConnectStatus": true,
	"SubmitOpenConnectAuthResponse": true, "CancelOpenConnectAuthChallenge": true,
}

func messages(method string) (string, proto.Message, proto.Message, bool, error) {
	switch method {
	case "TestOutbound", "ResetNetwork", "SubscribeTraffic":
		return controlPrefix + method, &structpb.Struct{}, &structpb.Struct{}, method == "SubscribeTraffic", nil
	}
	if !allowedMethods[method] {
		return "", nil, nil, false, errors.New("dashboard method is not allowed")
	}
	desc := daemon.File_daemon_started_service_proto.Services().ByName("StartedService").Methods().ByName(protoreflect.Name(method))
	if desc == nil {
		return "", nil, nil, false, errors.New("dashboard method is unavailable")
	}
	return daemonPrefix + method, dynamicpb.NewMessage(desc.Input()), dynamicpb.NewMessage(desc.Output()), desc.IsStreamingServer(), nil
}

type Client struct{ conn *grpc.ClientConn }

func NewClient(path string) (*Client, error) {
	if !strings.HasPrefix(path, "/") {
		return nil, errors.New("dashboard requires an absolute filesystem socket path")
	}
	conn, err := grpc.NewClient("passthrough:///dashboard", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", path)
	}))
	if err != nil {
		return nil, err
	}
	return &Client{conn: conn}, nil
}

func (c *Client) Call(method, request string, timeoutMillis int32) (string, error) {
	path, input, output, streaming, err := messages(method)
	if err != nil {
		return "", err
	}
	if streaming {
		return "", errors.New("method requires a stream")
	}
	if err = protojson.Unmarshal([]byte(request), input); err != nil {
		return "", err
	}
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	if err = c.conn.Invoke(ctx, path, input, output); err != nil {
		return "", err
	}
	data, err := protojson.Marshal(output)
	return string(data), err
}

type Handler interface {
	OnMessage(string)
	OnClosed(string)
}
type Stream struct {
	cancel context.CancelFunc
	done   chan struct{}
	once   sync.Once
}

func (s *Stream) Close() { s.once.Do(s.cancel); <-s.done }

func (c *Client) Stream(method, request string, handler Handler) (*Stream, error) {
	path, input, output, streaming, err := messages(method)
	if err != nil {
		return nil, err
	}
	if !streaming {
		return nil, errors.New("method requires a unary call")
	}
	if handler == nil {
		return nil, errors.New("missing stream handler")
	}
	if err = protojson.Unmarshal([]byte(request), input); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	stream, err := c.conn.NewStream(ctx, &grpc.StreamDesc{ServerStreams: true}, path)
	if err != nil {
		cancel()
		return nil, err
	}
	if err = stream.SendMsg(input); err == nil {
		err = stream.CloseSend()
	}
	if err != nil {
		cancel()
		return nil, err
	}
	call := &Stream{cancel: cancel, done: make(chan struct{})}
	go func() {
		defer close(call.done)
		var message string
		for {
			current := proto.Clone(output)
			if err := stream.RecvMsg(current); err != nil {
				if ctx.Err() == nil {
					message = err.Error()
				}
				break
			}
			data, err := protojson.Marshal(current)
			if err != nil {
				message = err.Error()
				break
			}
			handler.OnMessage(string(data))
		}
		handler.OnClosed(message)
	}()
	return call, nil
}
func (c *Client) Close() error { return c.conn.Close() }
