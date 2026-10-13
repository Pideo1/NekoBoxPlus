package endpoint

import (
	"context"
	"slices"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service"
)

var _ adapter.EndpointManager = (*Manager)(nil)

type Manager struct {
	ctx           context.Context
	registry      adapter.EndpointRegistry
	access        sync.Mutex
	scope         *adapter.Scope
	endpoints     []adapter.Endpoint
	endpointByTag map[string]adapter.Endpoint
}

func NewManager(ctx context.Context, registry adapter.EndpointRegistry) *Manager {
	return &Manager{
		ctx:           ctx,
		registry:      registry,
		endpointByTag: make(map[string]adapter.Endpoint),
	}
}

func (m *Manager) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	m.access.Lock()
	if stage == adapter.StartStateInitialize {
		m.scope = scope
	}
	endpoints := slices.Clone(m.endpoints)
	m.access.Unlock()
	if stage == adapter.StartStateStart {
		return nil
	}
	if stage == adapter.StartStatePostStart {
		// Domain peers may resolve through another endpoint at post-start. Follow
		// detour dependencies, including those hidden behind an outbound/selector.
		var err error
		endpoints, err = orderEndpointDependencies(endpoints, service.FromContext[adapter.OutboundManager](m.ctx))
		if err != nil {
			return err
		}
	}
	// Do not hold access while calling endpoints: bootstrap DNS can look up a
	// detour through OutboundManager, which calls back into Manager.Get.
	for _, endpoint := range endpoints {
		name := "endpoint/" + endpoint.Type() + "[" + endpoint.Tag() + "]"
		err := scope.Start(name, endpoint, stage)
		if err != nil {
			return err
		}
	}
	return nil
}

func orderEndpointDependencies(endpoints []adapter.Endpoint, manager adapter.OutboundManager) ([]adapter.Endpoint, error) {
	byTag := make(map[string]adapter.Endpoint, len(endpoints))
	for _, endpoint := range endpoints {
		byTag[endpoint.Tag()] = endpoint
	}
	state := make(map[string]uint8)
	ordered := make([]adapter.Endpoint, 0, len(endpoints))
	var visit func(adapter.Outbound) error
	visit = func(outbound adapter.Outbound) error {
		tag := outbound.Tag()
		switch state[tag] {
		case 1:
			return E.New("circular endpoint dependency: ", tag)
		case 2:
			return nil
		}
		state[tag] = 1
		for _, dependency := range outbound.Dependencies() {
			var next adapter.Outbound = byTag[dependency]
			if next == nil && manager != nil {
				next, _ = manager.Outbound(dependency)
			}
			if next == nil {
				return E.New("endpoint dependency not found: ", dependency)
			}
			if err := visit(next); err != nil {
				return err
			}
		}
		state[tag] = 2
		if endpoint, loaded := byTag[tag]; loaded {
			ordered = append(ordered, endpoint)
		}
		return nil
	}
	for _, endpoint := range endpoints {
		if err := visit(endpoint); err != nil {
			return nil, err
		}
	}
	return ordered, nil
}

func (m *Manager) StartEndpoint(endpoint adapter.Endpoint) error {
	return m.scope.Start("endpoint/"+endpoint.Type()+"["+endpoint.Tag()+"]", endpoint, adapter.StartStateStart)
}

func (m *Manager) Endpoints() []adapter.Endpoint {
	m.access.Lock()
	defer m.access.Unlock()
	return m.endpoints
}

func (m *Manager) Get(tag string) (adapter.Endpoint, bool) {
	m.access.Lock()
	defer m.access.Unlock()
	endpoint, found := m.endpointByTag[tag]
	return endpoint, found
}

func (m *Manager) Create(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, outboundType string, options any) error {
	endpoint, err := m.registry.Create(ctx, router, logger, tag, outboundType, options)
	if err != nil {
		return err
	}
	m.access.Lock()
	defer m.access.Unlock()
	_, loaded := m.endpointByTag[tag]
	if loaded {
		return E.New("duplicate endpoint tag: ", tag)
	}
	m.endpoints = append(m.endpoints, endpoint)
	m.endpointByTag[tag] = endpoint
	return nil
}
