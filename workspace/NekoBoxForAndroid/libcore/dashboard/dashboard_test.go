package dashboard

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/sagernet/sing-box/daemon"
	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/emptypb"
)

func TestPrivateSocket(t *testing.T) {
	path := filepath.Join(t.TempDir(), "private", "api.sock")
	listener, err := listenPrivate(path)
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatalf("socket permissions: %v %v", info, err)
	}
	if duplicate, err := listenPrivate(path); err == nil {
		duplicate.Close()
		t.Fatal("replaced a live socket")
	}
	listener.Close()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("socket was not removed")
	}
	if err := os.WriteFile(path, []byte("keep"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := listenPrivate(path); err == nil {
		t.Fatal("replaced non-socket file")
	}
	if data, _ := os.ReadFile(path); string(data) != "keep" {
		t.Fatal("occupied file changed")
	}
}
func TestStaleSocket(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "api.sock")
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	listener.SetUnlinkOnClose(false)
	listener.Close()
	recovered, err := listenPrivate(path)
	if err != nil {
		t.Fatal(err)
	}
	recovered.Close()
}
func TestRejectPublicDirectory(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "public")
	os.Mkdir(dir, 0755)
	if _, err := listenPrivate(filepath.Join(dir, "api.sock")); err == nil {
		t.Fatal("accepted public directory")
	}
}
func TestMethodSurface(t *testing.T) {
	for method := range allowedMethods {
		if _, _, _, _, err := messages(method); err != nil {
			t.Errorf("%s: %v", method, err)
		}
	}
	for _, method := range []string{"SubscribeTraffic", "ResetNetwork", "TestOutbound"} {
		if _, _, _, _, err := messages(method); err != nil {
			t.Fatal(err)
		}
	}
	for _, method := range []string{"StartOrReloadService", "SubscribeLog", "StartTailscaleSSHSession", "../ResetNetwork"} {
		if _, _, _, _, err := messages(method); err == nil {
			t.Fatalf("exposed %s", method)
		}
	}
	if client, err := NewClient("127.0.0.1:9090"); err == nil {
		client.Close()
		t.Fatal("accepted TCP endpoint")
	}
}

type testService struct {
	daemon.UnimplementedStartedServiceServer
}

func (testService) GetClashModeStatus(context.Context, *emptypb.Empty) (*daemon.ClashModeStatus, error) {
	return &daemon.ClashModeStatus{ModeList: []string{"rule", "direct"}, CurrentMode: "rule"}, nil
}
func (testService) SubscribeStatus(_ *daemon.SubscribeStatusRequest, stream grpc.ServerStreamingServer[daemon.Status]) error {
	if err := stream.Send(&daemon.Status{Memory: 123, Goroutines: 7}); err != nil {
		return err
	}
	<-stream.Context().Done()
	return stream.Context().Err()
}

type testHandler struct {
	messages chan string
	closed   chan string
}

func (h *testHandler) OnMessage(s string) { h.messages <- s }
func (h *testHandler) OnClosed(s string)  { h.closed <- s }
func TestClientCallsAndCancellation(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "api.sock")
	listener, err := listenPrivate(path)
	if err != nil {
		t.Fatal(err)
	}
	server := grpc.NewServer()
	daemon.RegisterStartedServiceServer(server, testService{})
	go server.Serve(listener)
	defer server.Stop()
	client, err := NewClient(path)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	result, err := client.Call("GetClashModeStatus", "{}", 1000)
	if err != nil || result == "" {
		t.Fatalf("call: %s %v", result, err)
	}
	if _, err := client.Call("SelectOutbound", `{"unknown":true}`, 1000); err == nil {
		t.Fatal("accepted invalid protobuf request")
	}
	handler := &testHandler{messages: make(chan string, 1), closed: make(chan string, 1)}
	stream, err := client.Stream("SubscribeStatus", `{"interval":"1000000000"}`, handler)
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-handler.messages:
	case <-time.After(time.Second):
		t.Fatal("no initial snapshot")
	}
	stream.Close()
	stream.Close()
	select {
	case message := <-handler.closed:
		if message != "" {
			t.Fatalf("cancel reported failure: %s", message)
		}
	case <-time.After(time.Second):
		t.Fatal("stream cancellation leaked")
	}
}
func TestPeerUIDRejection(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "api.sock")
	listener, err := listenPrivate(path)
	if err != nil {
		t.Fatal(err)
	}
	listener.(*privateListener).uid++
	server := grpc.NewServer()
	daemon.RegisterStartedServiceServer(server, testService{})
	go server.Serve(listener)
	defer server.Stop()
	client, err := NewClient(path)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if _, err := client.Call("GetClashModeStatus", "{}", 150); err == nil {
		t.Fatal("allowed a different UID")
	}
}

func privateTestDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.Chmod(dir, 0700); err != nil {
		t.Fatal(err)
	}
	return dir
}
