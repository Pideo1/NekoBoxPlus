# NekoBoxForAndroid

Can be built using gradle or Android Studio.

# libcore

Should be built in Docker using:
```bash
bash buildScript/lib/core.docker.sh
```

It uses patched Go runtime which fixes `PersistentKeepalive` functionality for both Wireguard and AmneziaWG on old kernels (4.17 and older) which is very important in this project.

The core build also packages the TPROXY root broker as `libnb4atproxy.so` for
each Android ABI. Rebuild libcore after changing `libcore/tproxy`, its broker
command, or the transparent socket integration in the sibling sing-box checkout;
an AAR-only replacement does not update the broker executable.

TPROXY is selected under Settings → Connection → Service Mode. It needs granted
root access and kernel support for transparent TCP/UDP sockets, TPROXY, packet
marks and policy routing in every enabled IP family. It uses an ordinary
foreground service without creating a VPN network or TUN interface. Other apps
may still detect root or the installed app.

The broker journals its own firewall changes, removes interception before core
shutdown, and checks TCP/UDP through the core independently of the app process.
Startup reconciles abandoned sessions. Tethering is optional and requires active
downstream interfaces with hardware and BPF offload verifiably disabled;
unsupported configurations are rejected before activating interception. Android
package rules apply to local apps; tethered clients use network routing rules.

# sing-box

Uses patched `amneziawg-go`. Read `patches` directory there and proceed using instructions there.
