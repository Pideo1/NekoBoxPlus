#!/usr/bin/env python3
"""Local Xray peers and sing-box config for ShadowsocksTransportAvdTest.

Usage: python3 shadowsocks_transport_server.py --xray /path/to/xray --sing-box /path/to/sing-box
Generates self-signed certificates and configs in --output (default /tmp/shadowsocks-transport-test).
Requires openssl and the matching libcronet.so beside the sing-box executable.
"""

import argparse, http.server, json, os, pathlib, subprocess, urllib.parse, socketserver, threading

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", default="/tmp/shadowsocks-transport-test")
parser.add_argument("--xray", required=True)
parser.add_argument("--sing-box", required=True)
parser.add_argument("--generate-only", action="store_true")
args = parser.parse_args()
root = pathlib.Path(args.output).resolve()
(root / "certs").mkdir(parents=True, exist_ok=True)
subprocess.run(
    [
        "openssl",
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        str(root / "certs/key.pem"),
        "-out",
        str(root / "certs/cert.pem"),
        "-days",
        "3",
        "-subj",
        "/CN=localhost",
        "-addext",
        "subjectAltName=DNS:localhost,IP:127.0.0.1",
    ],
    check=True,
    stdout=subprocess.DEVNULL,
    stderr=subprocess.DEVNULL,
)
cases = []
for transport in ["tcp", "ws", "grpc", "httpupgrade", "xhttp"]:
    for tls in [False, True]:
        for mode in (
            ["packet-up", "stream-up", "stream-one"]
            if transport == "xhttp"
            else ["auto"]
        ):
            if transport == "xhttp" and mode == "stream-one" and not tls:
                continue
            name = (
                transport
                + ("-tls" if tls else "")
                + ("-" + mode if transport == "xhttp" else "")
            )
            cases.append(
                dict(
                    name=name,
                    transport=transport,
                    tls=tls,
                    mode=mode,
                    port=21100 + len(cases),
                    socks_port=21200 + len(cases),
                )
            )
inbounds = []
outbounds = []
local_in = []
rules = []
for c in cases:
    t = c["transport"]
    ss = {
        "network": t if t != "tcp" else "raw",
        "security": "tls" if c["tls"] else "none",
    }
    opts = {
        "ws": {"path": "/ss", "host": "localhost"},
        "grpc": {"serviceName": "ss"},
        "httpupgrade": {"path": "/ss", "host": "localhost"},
        "xhttp": {"path": "/ss", "host": "localhost", "mode": c["mode"]},
    }
    if t != "tcp":
        ss[t + "Settings"] = opts[t]
    if c["tls"]:
        ss["tlsSettings"] = {
            "certificates": [
                {
                    "certificateFile": str(root / "certs/cert.pem"),
                    "keyFile": str(root / "certs/key.pem"),
                }
            ]
        }
    inbounds.append(
        {
            "tag": c["name"],
            "listen": "0.0.0.0",
            "port": c["port"],
            "protocol": "shadowsocks",
            "settings": {
                "method": "aes-128-gcm",
                "password": "transport-test-password",
                "network": "tcp,udp",
            },
            "streamSettings": ss,
        }
    )
    out = {
        "type": "shadowsocks",
        "tag": c["name"],
        "server": "127.0.0.1",
        "server_port": c["port"],
        "method": "aes-128-gcm",
        "password": "transport-test-password",
    }
    if c["tls"]:
        out["tls"] = {"enabled": True, "server_name": "localhost", "insecure": True}
    if t != "tcp":
        out["transport"] = {
            "type": t,
            **(
                {"service_name": "ss"}
                if t == "grpc"
                else {
                    "path": "/ss",
                    **(
                        {"headers": {"Host": "localhost"}}
                        if t == "ws"
                        else {"host": "localhost"}
                    ),
                }
            ),
            **({"mode": c["mode"]} if t == "xhttp" else {}),
        }
    outbounds.append(out)
    local_in.append(
        {
            "type": "mixed",
            "tag": c["name"] + "-in",
            "listen": "127.0.0.1",
            "listen_port": c["socks_port"],
        }
    )
    rules.append(
        {"inbound": [c["name"] + "-in"], "action": "route", "outbound": c["name"]}
    )
    q = {
        "type": t,
        "security": "tls" if c["tls"] else "none",
        "path": "/ss",
        "serviceName": "ss",
        "host": "localhost",
        "sni": "localhost",
        "mode": c["mode"],
        "allowInsecure": "1",
    }
    c["uri"] = (
        "ss://aes-128-gcm:transport-test-password@10.0.2.2:"
        + str(c["port"])
        + "?"
        + urllib.parse.urlencode(q)
        + "#"
        + c["name"]
    )
# For the remaining core transports and UDP stream wrappers, use Trojan's
# transparent fallback to a plain Shadowsocks inbound. The outer transport is
# terminated before forwarding the unchanged encrypted Shadowsocks stream.
backend = {
    "type": "shadowsocks",
    "tag": "ss-backend",
    "listen": "127.0.0.1",
    "listen_port": 21500,
    "method": "aes-128-gcm",
    "password": "transport-test-password",
    "multiplex": {"enabled": True},
}
local_in.append(backend)
rules.append({"inbound": ["ss-backend"], "action": "route", "outbound": "test-direct"})
for transport, secure, wrapper in [
    ("http", False, ""),
    ("http", True, ""),
    ("quic", True, ""),
    ("kcp", False, ""),
    ("kcp", True, ""),
    ("ws", False, "uot"),
    ("ws", False, "mux"),
]:
    name = transport + ("-tls" if secure else "") + ("-" + wrapper if wrapper else "")
    case = {
        "name": name,
        "transport": transport,
        "tls": secure,
        "port": 21100 + len(cases),
        "socks_port": 21200 + len(cases),
    }
    transport_options = {"type": transport}
    if transport in ("http", "ws"):
        transport_options["path"] = "/ss"
    inbound = {
        "type": "trojan",
        "tag": name + "-server",
        "listen": "0.0.0.0",
        "listen_port": case["port"],
        "users": [{"password": "unused-trojan-password"}],
        "fallback": {"server": "127.0.0.1", "server_port": 21500},
        "transport": transport_options,
    }
    outbound = {
        "type": "shadowsocks",
        "tag": name,
        "server": "127.0.0.1",
        "server_port": case["port"],
        "method": "aes-128-gcm",
        "password": "transport-test-password",
        "transport": transport_options,
    }
    if secure:
        inbound["tls"] = {
            "enabled": True,
            "certificate_path": str(root / "certs/cert.pem"),
            "key_path": str(root / "certs/key.pem"),
        }
        outbound["tls"] = {
            "enabled": True,
            "server_name": "localhost",
            "insecure": True,
        }
    if wrapper == "uot":
        outbound["udp_over_tcp"] = True
    if wrapper == "mux":
        outbound["multiplex"] = {"enabled": True}
    case["outbound"] = dict(outbound, server="10.0.2.2")
    case["udp"] = bool(wrapper)
    cases.append(case)
    local_in.extend(
        [
            inbound,
            {
                "type": "mixed",
                "tag": name + "-in",
                "listen": "127.0.0.1",
                "listen_port": case["socks_port"],
            },
        ]
    )
    rules.extend(
        [
            {
                "inbound": [name + "-server"],
                "action": "route",
                "outbound": "test-direct",
            },
            {"inbound": [name + "-in"], "action": "route", "outbound": name},
        ]
    )
    outbounds.append(outbound)
outbounds.append({"type": "direct", "tag": "test-direct"})
local_in.append(
    {
        "type": "direct",
        "tag": "plain-udp-in",
        "listen": "127.0.0.1",
        "listen_port": 21600,
        "network": "udp",
        "override_address": "127.0.0.1",
        "override_port": 21301,
    }
)
rules.append({"inbound": ["plain-udp-in"], "action": "route", "outbound": "tcp"})
(root / "cases.json").write_text(json.dumps(cases, indent=2))
(root / "xray.json").write_text(
    json.dumps(
        {
            "log": {"loglevel": "warning"},
            "inbounds": inbounds,
            "outbounds": [{"protocol": "freedom", "tag": "direct"}],
        },
        indent=2,
    )
)
(root / "sing-box.json").write_text(
    json.dumps(
        {
            "log": {"level": "info"},
            "inbounds": local_in,
            "outbounds": outbounds,
            "route": {"rules": rules},
        },
        indent=2,
    )
)

if args.generate_only:
    raise SystemExit(0)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/cases":
            body = (root / "cases.json").read_bytes()
        elif self.path.startswith("/payload"):
            body = b"shadowsocks-transport-ok\n" * 8192
        else:
            body = b"shadowsocks-transport-ok\n"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class EchoUDP(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        sock.sendto(data, self.client_address)


udp_server = socketserver.UDPServer(("0.0.0.0", 21301), EchoUDP)
threading.Thread(target=udp_server.serve_forever, daemon=True).start()
processes = []
try:
    for name, command in [
        ("xray", [args.xray, "run", "-c", str(root / "xray.json")]),
        ("sing-box", [args.sing_box, "run", "-c", str(root / "sing-box.json")]),
    ]:
        with open(root / (name + "-runtime.log"), "w") as log:
            env = dict(
                os.environ,
                LD_LIBRARY_PATH=str(pathlib.Path(args.sing_box).resolve().parent),
            )
            processes.append(subprocess.Popen(command, stdout=log, stderr=log, env=env))
    print(
        f"{len(cases)} cases; configs in {root}; HTTP target and case manifest on port 21300",
        flush=True,
    )
    http.server.ThreadingHTTPServer(("0.0.0.0", 21300), Handler).serve_forever()
finally:
    udp_server.shutdown()
    udp_server.server_close()
    for process in processes:
        process.terminate()
    for process in processes:
        process.wait()
