package adapter

// TProxySocketFactory optionally supplies already bound privileged transparent
// sockets. Ownership of each returned descriptor transfers to the caller.
// Android uses a root broker; desktop implementations keep their native path.
type TProxySocketFactory interface {
	OpenTProxySocket(network, address string, reply bool) (int, error)
}
