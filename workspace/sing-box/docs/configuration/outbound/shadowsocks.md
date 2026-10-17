### Structure

```json
{
  "type": "shadowsocks",
  "tag": "ss-out",
  
  "server": "127.0.0.1",
  "server_port": 1080,
  "method": "2022-blake3-aes-128-gcm",
  "password": "8JCsPssfgS8tiRwiMlhARg==",
  "plugin": "",
  "plugin_opts": "",
  "network": "udp",
  "udp_over_tcp": false | {},
  "multiplex": {},
  "tls": {},
  "transport": {},

  ... // Dial Fields
}
```

### Fields

#### server

==Required==

The server address.

#### server_port

==Required==

The server port.

#### method

==Required==

Encryption methods:

* `2022-blake3-aes-128-gcm`
* `2022-blake3-aes-256-gcm`
* `2022-blake3-chacha20-poly1305`
* `none`
* `aes-128-gcm`
* `aes-192-gcm`
* `aes-256-gcm`
* `chacha20-ietf-poly1305`
* `xchacha20-ietf-poly1305`

Legacy encryption methods:

* `aes-128-ctr`
* `aes-192-ctr`
* `aes-256-ctr`
* `aes-128-cfb`
* `aes-192-cfb`
* `aes-256-cfb`
* `rc4-md5`
* `chacha20-ietf`
* `xchacha20`

#### password

==Required==

The shadowsocks password.

#### plugin

Shadowsocks SIP003 plugin, implemented in internal.

Only `obfs-local` and `v2ray-plugin` are supported.

#### plugin_opts

Shadowsocks SIP003 plugin options.

#### network

Enabled network

One of `tcp` `udp`.

Both is enabled by default.

#### udp_over_tcp

UDP over TCP configuration.

See [UDP Over TCP](/configuration/shared/udp-over-tcp/) for details.

Conflict with `multiplex`.

#### multiplex

See [Multiplex](/configuration/shared/multiplex#outbound) for details.

#### tls

TLS configuration, see [TLS](/configuration/shared/tls/#outbound).

Runs the Shadowsocks stream inside TLS. Cannot be combined with `plugin`.

#### transport

V2Ray Transport configuration, see [V2Ray Transport](/configuration/shared/v2ray-transport/).

Carries the Shadowsocks stream over WebSocket, gRPC, HTTPUpgrade, XHTTP and the
other V2Ray transports. Cannot be combined with `plugin`.

!!! note ""

    Native Shadowsocks UDP is sent through a separate UDP socket and cannot be
    carried by `tls` or `transport`. When either is set, the outbound only
    accepts TCP unless `udp_over_tcp` or `multiplex` is enabled, and an explicit
    `network` that lists `udp` is rejected.

### Dial Fields

See [Dial Fields](/configuration/shared/dial/) for details.
