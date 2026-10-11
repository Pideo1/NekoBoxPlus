// SPDX-License-Identifier: GPL-3.0-or-later
package dashboard

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing/service"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/emptypb"
	"google.golang.org/protobuf/types/known/structpb"
)

type Controls struct {
	SetMode      func(string) error
	ResetNetwork func() error
	TestOutbound func(context.Context, string, string, int32, int32, int32, bool) (int32, error)
}
type Host struct {
	*daemon.StartedService
	ctx       context.Context
	controls  Controls
	server    *grpc.Server
	closeOnce sync.Once
}

func Start(ctx context.Context, path string, controls Controls) (*Host, error) {
	listener, err := listenPrivate(path)
	if err != nil {
		return nil, err
	}
	// All required services must exist before attaching; temporary test boxes do not qualify.
	if service.PtrFromContext[trafficcontrol.Manager](ctx) == nil || service.PtrFromContext[urltest.HistoryStorage](ctx) == nil {
		_ = listener.Close()
		return nil, errors.New("dashboard tracking services are unavailable")
	}
	host := &Host{StartedService: daemon.NewAttachedService(ctx), ctx: ctx, controls: controls}
	check := func(method string) error {
		if method == controlPrefix+"TestOutbound" || method == controlPrefix+"ResetNetwork" || method == controlPrefix+"SubscribeTraffic" {
			return nil
		}
		for name := range allowedMethods {
			if method == daemonPrefix+name {
				return nil
			}
		}
		return status.Error(codes.PermissionDenied, "method is not exposed by dashboard")
	}
	host.server = grpc.NewServer(
		grpc.UnaryInterceptor(func(ctx context.Context, req any, info *grpc.UnaryServerInfo, next grpc.UnaryHandler) (any, error) {
			if err := check(info.FullMethod); err != nil {
				return nil, err
			}
			return next(ctx, req)
		}),
		grpc.StreamInterceptor(func(srv any, stream grpc.ServerStream, info *grpc.StreamServerInfo, next grpc.StreamHandler) error {
			if err := check(info.FullMethod); err != nil {
				return err
			}
			return next(srv, stream)
		}),
	)
	daemon.RegisterStartedServiceServer(host.server, host)
	host.server.RegisterService(&controlService, host)
	go func() { _ = host.server.Serve(listener) }()
	return host, nil
}
func (h *Host) Close() { h.closeOnce.Do(func() { h.server.Stop(); h.StartedService.Close() }) }
func (h *Host) SetClashMode(ctx context.Context, req *daemon.ClashMode) (*emptypb.Empty, error) {
	if err := h.controls.SetMode(req.Mode); err != nil {
		return nil, status.Error(codes.InvalidArgument, err.Error())
	}
	return &emptypb.Empty{}, nil
}
func (h *Host) CloseAllConnections(ctx context.Context, req *emptypb.Empty) (*emptypb.Empty, error) {
	if err := h.controls.ResetNetwork(); err != nil {
		return nil, err
	}
	return &emptypb.Empty{}, nil
}

// Struct keeps the small NB4A-only controls independent of upstream generated code.
// All standard dashboard RPCs retain sing-box's original protobuf contracts.
type controlServer interface {
	control(context.Context, string, *structpb.Struct) (*structpb.Struct, error)
	traffic(grpc.ServerStream) error
}

func (h *Host) control(ctx context.Context, name string, req *structpb.Struct) (*structpb.Struct, error) {
	if name == "ResetNetwork" {
		if err := h.controls.ResetNetwork(); err != nil {
			return nil, err
		}
		return &structpb.Struct{}, nil
	}
	fields := req.GetFields()
	tag, link := fields["tag"].GetStringValue(), fields["url"].GetStringValue()
	timeout := int32(fields["timeout"].GetNumberValue())
	attempts, pause := int32(fields["attempts"].GetNumberValue()), int32(fields["pause"].GetNumberValue())
	if tag == "" || timeout <= 0 || timeout > 120000 || attempts < 1 || attempts > 10 || pause < 0 || pause > 10000 {
		return nil, status.Error(codes.InvalidArgument, "invalid latency test options")
	}
	latency, err := h.controls.TestOutbound(ctx, tag, link, timeout, attempts, pause, fields["hardened"].GetBoolValue())
	history := service.PtrFromContext[urltest.HistoryStorage](h.ctx)
	if err != nil {
		history.DeleteURLTestHistory(tag)
		return nil, status.Error(codes.Unavailable, err.Error())
	}
	history.StoreURLTestHistory(tag, &adapter.URLTestHistory{Time: time.Now(), Delay: uint16(latency)})
	return structpb.NewStruct(map[string]any{"latency": latency})
}
func controlMethod(name string) grpc.MethodDesc {
	return grpc.MethodDesc{MethodName: name, Handler: func(srv any, ctx context.Context, decode func(any) error, interceptor grpc.UnaryServerInterceptor) (any, error) {
		req := &structpb.Struct{}
		if err := decode(req); err != nil {
			return nil, err
		}
		next := func(ctx context.Context, req any) (any, error) {
			return srv.(controlServer).control(ctx, name, req.(*structpb.Struct))
		}
		if interceptor == nil {
			return next(ctx, req)
		}
		return interceptor(ctx, req, &grpc.UnaryServerInfo{Server: srv, FullMethod: controlPrefix + name}, next)
	}}
}

var controlService = grpc.ServiceDesc{ServiceName: "dashboard.Control", HandlerType: (*controlServer)(nil), Methods: []grpc.MethodDesc{controlMethod("TestOutbound"), controlMethod("ResetNetwork")}, Streams: []grpc.StreamDesc{{StreamName: "SubscribeTraffic", ServerStreams: true, Handler: func(srv any, stream grpc.ServerStream) error {
	req := &structpb.Struct{}
	if err := stream.RecvMsg(req); err != nil {
		return err
	}
	return srv.(controlServer).traffic(stream)
}}}}

type trafficCounter struct{ up, down int64 }

func (h *Host) traffic(stream grpc.ServerStream) error {
	manager := service.PtrFromContext[trafficcontrol.Manager](h.ctx)
	previous := make(map[string]trafficCounter)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	last := time.Now()
	initial := true
	for {
		now := time.Now()
		elapsed := now.Sub(last).Seconds()
		last = now
		direct, proxy := trafficCounter{}, trafficCounter{}
		next := make(map[string]trafficCounter)
		for _, item := range append(manager.ClosedConnections(), manager.Connections()...) {
			id := item.ID.String()
			current := trafficCounter{item.Upload.Load(), item.Download.Load()}
			next[id] = current
			old := previous[id]
			if initial {
				old = current
			}
			delta := trafficCounter{max(0, current.up-old.up), max(0, current.down-old.down)}
			target := &proxy
			if item.OutboundType == "direct" {
				target = &direct
			}
			target.up += delta.up
			target.down += delta.down
		}
		previous = next
		initial = false
		if elapsed <= 0 {
			elapsed = 1
		}
		message, _ := structpb.NewStruct(map[string]any{"proxyUp": float64(proxy.up) / elapsed, "proxyDown": float64(proxy.down) / elapsed, "directUp": float64(direct.up) / elapsed, "directDown": float64(direct.down) / elapsed})
		if err := stream.SendMsg(message); err != nil {
			return err
		}
		select {
		case <-stream.Context().Done():
			return stream.Context().Err()
		case <-h.ctx.Done():
			return h.ctx.Err()
		case <-ticker.C:
		}
	}
}
