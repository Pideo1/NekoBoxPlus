package io.nekohasekai.sagernet.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

internal enum class InterfaceKind { WIFI, ETHERNET, CELLULAR, VPN, OTHER }
internal enum class HotspotKind { NONE, POSSIBLE, CONFIRMED }

internal data class InterfaceIp(
    val address: String,
    val prefixLength: Int? = null,
    val broadcast: String? = null,
) {
    val ipv6: Boolean get() = address.contains(':')
    val netmask: String?
        get() {
            val prefix = prefixLength ?: return null
            if (ipv6 || prefix !in 0..32) return null
            val bits = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            return (3 downTo 0).joinToString(".") { ((bits shr (it * 8)) and 255).toString() }
        }
}

internal data class InterfaceRoute(val destination: InterfaceIp, val gateway: String?)

internal data class NetworkInterfaceInfo(
    val name: String,
    val index: Int? = null,
    val up: Boolean? = null,
    val mtu: Int? = null,
    val loopback: Boolean = false,
    val pointToPoint: Boolean? = null,
    val multicast: Boolean? = null,
    val virtual: Boolean? = null,
    val mac: String? = null,
    val kind: InterfaceKind = InterfaceKind.OTHER,
    val hotspot: HotspotKind = HotspotKind.NONE,
    val addresses: List<InterfaceIp> = emptyList(),
    val routes: List<InterfaceRoute> = emptyList(),
    val dns: List<String> = emptyList(),
)

internal data class NetworkInterfacesSnapshot(
    val interfaces: List<NetworkInterfaceInfo> = emptyList(),
    val partial: Boolean = false,
)

internal data class ProxyListenerInfo(
    val type: String,
    val tag: String,
    val listen: String,
    val port: Int,
    val tls: Boolean = false,
    val authentication: Boolean = false,
    val bindInterface: String = "",
    val networkNamespace: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("tag", tag).put("listen", listen).put("port", port)
        .put("tls", tls).put("authentication", authentication)
        .put("bind_interface", bindInterface).put("netns", networkNamespace)
}

internal object NetworkInterfacesPolicy {
    // Parse only IP literals: this viewer must never perform DNS lookups.
    private fun literal(value: String): InetAddress? {
        val address = value.substringBefore('%')
        if (!address.contains(':') && !address.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) return null
        if (!address.matches(Regex("[0-9a-fA-F:.]+"))) return null
        return runCatching { InetAddress.getByName(address) }.getOrNull()
    }

    fun listenersFromConfig(config: String): List<ProxyListenerInfo> =
        parseListeners(JSONObject(config).optJSONArray("inbounds") ?: JSONArray(), configFormat = true)

    fun listenersFromJson(json: String): List<ProxyListenerInfo> =
        parseListeners(JSONArray(json), configFormat = false)

    private fun parseListeners(array: JSONArray, configFormat: Boolean): List<ProxyListenerInfo> =
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val type = item.optString("type")
            val port = item.optInt(if (configFormat) "listen_port" else "port")
            if (type !in listOf("mixed", "http") || port !in 1..65535) return@mapNotNull null
            val listen = item.optString("listen").ifBlank { "127.0.0.1" }
            if (literal(listen) == null) return@mapNotNull null
            ProxyListenerInfo(
                type = type,
                tag = item.optString("tag"),
                listen = listen,
                port = port,
                tls = if (configFormat) item.optJSONObject("tls")?.optBoolean("enabled") == true
                    else item.optBoolean("tls"),
                authentication = if (configFormat) (item.optJSONArray("users")?.length() ?: 0) > 0
                    else item.optBoolean("authentication"),
                bindInterface = item.optString("bind_interface"),
                networkNamespace = item.optString("netns"),
            )
        }

    fun listenersJson(listeners: List<ProxyListenerInfo>): String =
        JSONArray().apply { listeners.forEach { put(it.toJson()) } }.toString()

    fun matches(listener: ProxyListenerInfo, info: NetworkInterfaceInfo, ip: InterfaceIp): Boolean {
        if (info.up != true || listener.networkNamespace.isNotBlank()) return false
        if (listener.bindInterface.isNotBlank() && listener.bindInterface != info.name) return false
        val address = literal(ip.address) ?: return false
        if (address.isAnyLocalAddress || address.isMulticastAddress) return false
        val bind = literal(listener.listen) ?: return false
        val zone = listener.listen.substringAfter('%', "")
        if (zone.isNotEmpty() && zone != info.name && zone != info.index?.toString()) return false
        // sing-box selects tcp4/tcp6 according to the bind address; :: is IPv6-only.
        if ((bind is Inet4Address) != (address is Inet4Address)) return false
        return bind.isAnyLocalAddress || bind.address.contentEquals(address.address)
    }

    fun endpoint(address: String, port: Int): String =
        if (address.contains(':')) "[$address]:$port" else "$address:$port"

    fun isLinkLocal(address: String): Boolean = literal(address)?.isLinkLocalAddress == true
    fun isLoopback(address: String): Boolean = literal(address)?.isLoopbackAddress == true

    fun canShare(info: NetworkInterfaceInfo, ip: InterfaceIp): Boolean =
        info.up == true && !info.loopback && info.kind !in listOf(InterfaceKind.VPN, InterfaceKind.CELLULAR) &&
            literal(ip.address)?.let { !it.isLoopbackAddress && !it.isLinkLocalAddress &&
                !it.isAnyLocalAddress && !it.isMulticastAddress } == true

    fun classifyHotspot(name: String, tethered: Set<String>?): HotspotKind = when {
        tethered != null -> if (name in tethered) HotspotKind.CONFIRMED else HotspotKind.NONE
        name.matches(Regex("(?i)(ap\\d+|softap.*|swlan.*|wlan\\d+|rndis.*|usb\\d+|bnep.*)")) -> HotspotKind.POSSIBLE
        else -> HotspotKind.NONE
    }

    fun inferKind(name: String): InterfaceKind = when {
        name.matches(Regex("(?i)(tun.*|tap.*|ppp.*|wg\\d+|ipsec.*|vti.*)")) -> InterfaceKind.VPN
        name.matches(Regex("(?i)(v4-)?(rmnet.*|ccmni.*|pdp.*|wwan.*)")) -> InterfaceKind.CELLULAR
        else -> InterfaceKind.OTHER
    }

    fun mergeAddresses(first: List<InterfaceIp>, second: List<InterfaceIp>): List<InterfaceIp> =
        (first + second).groupBy { it.address.substringBefore('%') }.values.map { candidates ->
            val primary = candidates.first()
            primary.copy(
                prefixLength = candidates.firstNotNullOfOrNull { it.prefixLength },
                broadcast = candidates.firstNotNullOfOrNull { it.broadcast },
            )
        }.sortedWith(compareBy<InterfaceIp> { it.ipv6 }.thenBy { it.address })
}

internal enum class SharingAction { SAVE, RESTART, UNAVAILABLE }
internal enum class SharingMode { PROXY, VPN, TPROXY }
internal enum class SharingWarning { LOCAL_PROXY_DISABLED, LAN_DISABLED, PERMISSION, TPROXY_DISABLED, IPV4_DISABLED, CUSTOM_CONFIG, NOT_APPLIED }

internal data class NetworkSharingSettings(
    val mode: SharingMode,
    val allowAccess: Boolean,
    val requireProxy: Boolean,
    val mixedListener: String,
    val tproxyTethering: Boolean,
) {
    val enabled: Boolean
        get() = when (mode) {
            SharingMode.TPROXY -> tproxyTethering
            SharingMode.PROXY -> allowAccess || !NetworkInterfacesPolicy.isLoopback(mixedListener)
            SharingMode.VPN -> requireProxy && (allowAccess || !NetworkInterfacesPolicy.isLoopback(mixedListener))
        }
}

internal object NetworkSharingPolicy {
    fun action(connected: Boolean, transitioning: Boolean, customConfig: Boolean): SharingAction = when {
        transitioning || customConfig -> SharingAction.UNAVAILABLE
        connected -> SharingAction.RESTART
        else -> SharingAction.SAVE
    }

    fun needsPermission(sdkInt: Int, granted: Boolean): Boolean = sdkInt >= 37 && !granted

    fun permissionRequired(mode: SharingMode, enabled: Boolean, sdkInt: Int, granted: Boolean): Boolean =
        mode != SharingMode.TPROXY && enabled && needsPermission(sdkInt, granted)

    fun change(settings: NetworkSharingSettings, enabled: Boolean): NetworkSharingSettings =
        if (settings.mode == SharingMode.TPROXY) settings.copy(tproxyTethering = enabled)
        else settings.copy(
            allowAccess = enabled,
            requireProxy = settings.requireProxy || (enabled && settings.mode == SharingMode.VPN),
            mixedListener = if (!enabled && !NetworkInterfacesPolicy.isLoopback(settings.mixedListener)) "127.0.0.1"
                else settings.mixedListener,
        )

    fun hasExternalListener(listeners: List<ProxyListenerInfo>): Boolean = listeners.any {
        it.networkNamespace.isEmpty() && !NetworkInterfacesPolicy.isLoopback(it.listen)
    }

    fun applied(mode: SharingMode, enabled: Boolean, listeners: List<ProxyListenerInfo>, port: Int, tproxyActive: Boolean): Boolean =
        if (mode == SharingMode.TPROXY) tproxyActive == enabled
        else if (enabled) applied(listeners, port) else !hasExternalListener(listeners)

    fun warnings(
        settings: NetworkSharingSettings,
        localNetworkAllowed: Boolean,
        customConfig: Boolean,
        connected: Boolean,
        active: Boolean,
        ipv4Disabled: Boolean,
    ): List<SharingWarning> = buildList {
        if (customConfig) add(SharingWarning.CUSTOM_CONFIG)
        if (settings.mode == SharingMode.TPROXY) {
            if (!settings.tproxyTethering) add(SharingWarning.TPROXY_DISABLED)
            if (settings.tproxyTethering && ipv4Disabled) add(SharingWarning.IPV4_DISABLED)
        } else {
            if (!customConfig) {
                if (settings.mode == SharingMode.VPN && !settings.requireProxy) add(SharingWarning.LOCAL_PROXY_DISABLED)
                if (!settings.allowAccess && NetworkInterfacesPolicy.isLoopback(settings.mixedListener)) add(SharingWarning.LAN_DISABLED)
            }
            if (!localNetworkAllowed) add(SharingWarning.PERMISSION)
        }
        if (connected && settings.enabled != active) add(SharingWarning.NOT_APPLIED)
    }

    fun applied(listeners: List<ProxyListenerInfo>, port: Int): Boolean = listeners.any {
        it.type == "mixed" && it.listen == "0.0.0.0" && it.port == port &&
            it.bindInterface.isEmpty() && it.networkNamespace.isEmpty()
    }
}
