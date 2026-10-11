package libcore

import (
	"context"
	"errors"
	"libcore/dashboard"
)

// StartDashboard is lifecycle glue; transport and RPC implementation live in dashboard.
func (b *BoxInstance) StartDashboard(path string) error {
	b.access.Lock()
	defer b.access.Unlock()
	if b.forTest || b.state != boxStateStarted || b.Box == nil {
		return errors.New("dashboard requires a running production core")
	}
	if b.dashboard != nil {
		return nil
	}
	host, err := dashboard.Start(b.ctx, path, dashboard.Controls{
		SetMode:      func(mode string) error { return SetClashMode(b, mode) },
		ResetNetwork: b.ResetNetwork,
		TestOutbound: func(ctx context.Context, tag, link string, timeout, attempts, pause int32, hardened bool) (int32, error) {
			if err := ctx.Err(); err != nil {
				return -1, err
			}
			return b.urlTestContext(ctx, tag, link, timeout, attempts, pause, hardened)
		},
	})
	if err != nil {
		return err
	}
	b.dashboard = host
	return nil
}

type DashboardClient struct{ client *dashboard.Client }

func NewDashboardClient(path string) (*DashboardClient, error) {
	c, err := dashboard.NewClient(path)
	if err != nil {
		return nil, err
	}
	return &DashboardClient{client: c}, nil
}
func (c *DashboardClient) Call(method, request string, timeoutMillis int32) (string, error) {
	return c.client.Call(method, request, timeoutMillis)
}
func (c *DashboardClient) Close() error { return c.client.Close() }

type DashboardStreamHandler interface {
	OnMessage(string)
	OnClosed(string)
}
type DashboardStream struct{ stream *dashboard.Stream }

func (s *DashboardStream) Close() { s.stream.Close() }
func (c *DashboardClient) Stream(method, request string, handler DashboardStreamHandler) (*DashboardStream, error) {
	s, err := c.client.Stream(method, request, handler)
	if err != nil {
		return nil, err
	}
	return &DashboardStream{stream: s}, nil
}
