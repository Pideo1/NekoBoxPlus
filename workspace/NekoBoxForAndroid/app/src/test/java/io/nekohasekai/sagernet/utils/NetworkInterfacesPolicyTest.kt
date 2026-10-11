package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInterfacesPolicyTest {
    private val hotspot = NetworkInterfaceInfo("ap0", index = 12, up = true)
    private val ipv4 = InterfaceIp("192.168.43.1", 24)
    private val ipv6 = InterfaceIp("fd00::1", 64)
    private val mixed = ProxyListenerInfo("mixed", "mixed-in", "0.0.0.0", 2080)

    @Test
    fun wildcardIsExpandedOnlyToMatchingAddressFamily() {
        assertTrue(NetworkInterfacesPolicy.matches(mixed, hotspot, ipv4))
        assertFalse(NetworkInterfacesPolicy.matches(mixed, hotspot, ipv6))
        val v6 = mixed.copy(listen = "::")
        assertTrue(NetworkInterfacesPolicy.matches(v6, hotspot, ipv6))
        assertFalse(NetworkInterfacesPolicy.matches(v6, hotspot, ipv4))
    }

    @Test
    fun concreteBindDoesNotAdvertiseOtherInterfacesOrAddresses() {
        val loopback = NetworkInterfaceInfo("lo", up = true, loopback = true)
        val listener = mixed.copy(listen = "127.0.0.1")
        assertTrue(NetworkInterfacesPolicy.matches(listener, loopback, InterfaceIp("127.0.0.1")))
        assertFalse(NetworkInterfacesPolicy.matches(listener, hotspot, ipv4))
        assertTrue(NetworkInterfacesPolicy.matches(mixed.copy(listen = ipv4.address), hotspot, ipv4))
        assertFalse(NetworkInterfacesPolicy.matches(mixed.copy(listen = "192.168.1.5"), hotspot, ipv4))
    }

    @Test
    fun unavailableInterfacesAndInvalidDestinationsHaveNoEndpoints() {
        assertFalse(NetworkInterfacesPolicy.matches(mixed, hotspot.copy(up = false), ipv4))
        assertFalse(NetworkInterfacesPolicy.matches(mixed, hotspot.copy(up = null), ipv4))
        assertFalse(NetworkInterfacesPolicy.matches(mixed, hotspot, InterfaceIp("0.0.0.0")))
        assertFalse(NetworkInterfacesPolicy.matches(mixed, hotspot, InterfaceIp("224.0.0.1")))
        assertFalse(NetworkInterfacesPolicy.matches(mixed, hotspot, InterfaceIp("example.invalid")))
        assertFalse(NetworkInterfacesPolicy.matches(mixed.copy(bindInterface = "wlan0"), hotspot, ipv4))
        assertFalse(NetworkInterfacesPolicy.matches(mixed.copy(networkNamespace = "other"), hotspot, ipv4))
    }

    @Test
    fun ipv6ScopeAndCanonicalAddressAreRespected() {
        val ip = InterfaceIp("fe80:0:0:0:0:0:0:1%ap0", 64)
        assertTrue(NetworkInterfacesPolicy.matches(mixed.copy(listen = "fe80::1%ap0"), hotspot, ip))
        assertTrue(NetworkInterfacesPolicy.matches(mixed.copy(listen = "fe80::1%12"), hotspot, ip))
        assertFalse(NetworkInterfacesPolicy.matches(mixed.copy(listen = "fe80::1%wlan0"), hotspot, ip))
        assertEquals("[fe80::1%ap0]:2080", NetworkInterfacesPolicy.endpoint("fe80::1%ap0", 2080))
        assertEquals("192.168.43.1:2080", NetworkInterfacesPolicy.endpoint(ipv4.address, 2080))
    }

    @Test
    fun sharingRecommendationsExcludeLocalAndCellularEndpoints() {
        assertTrue(NetworkInterfacesPolicy.canShare(hotspot, ipv4))
        assertTrue(NetworkInterfacesPolicy.canShare(hotspot, ipv6))
        assertFalse(NetworkInterfacesPolicy.canShare(hotspot, InterfaceIp("fe80::1%ap0")))
        assertFalse(NetworkInterfacesPolicy.canShare(hotspot, InterfaceIp("169.254.1.1")))
        assertFalse(NetworkInterfacesPolicy.canShare(hotspot.copy(loopback = true), ipv4))
        assertFalse(NetworkInterfacesPolicy.canShare(hotspot.copy(kind = InterfaceKind.VPN), ipv4))
        assertFalse(NetworkInterfacesPolicy.canShare(hotspot.copy(kind = InterfaceKind.CELLULAR), ipv4))
        assertFalse(NetworkInterfacesPolicy.canShare(hotspot.copy(up = false), ipv4))
    }

    @Test
    fun customConfigListenersAreSanitizedWithoutCredentials() {
        val config = """{"inbounds":[
            {"type":"mixed","tag":"lan","listen":"0.0.0.0","listen_port":8080,
             "users":[{"username":"private-name","password":"private-password"}]},
            {"type":"http","listen":"::1","listen_port":3128,"tls":{"enabled":true}},
            {"type":"tun","tag":"vpn"},
            {"type":"socks","listen":"127.0.0.1","listen_port":1080},
            {"type":"http","listen":"example.invalid","listen_port":80},
            {"type":"mixed","listen_port":0}
        ]}"""
        val parsed = NetworkInterfacesPolicy.listenersFromConfig(config)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].authentication)
        assertTrue(parsed[1].tls)
        val wire = NetworkInterfacesPolicy.listenersJson(parsed)
        assertFalse(wire.contains("private-name"))
        assertFalse(wire.contains("private-password"))
        assertFalse(wire.contains("users"))
        assertEquals(parsed, NetworkInterfacesPolicy.listenersFromJson(wire))
    }

    @Test
    fun omittedBindDefaultsToLoopbackAndMissingInboundsAreEmpty() {
        assertEquals("127.0.0.1", NetworkInterfacesPolicy.listenersFromConfig(
            """{"inbounds":[{"type":"http","listen_port":3128}]}""",
        ).single().listen)
        assertTrue(NetworkInterfacesPolicy.listenersFromConfig("{}").isEmpty())
        assertTrue(NetworkInterfacesPolicy.listenersFromJson("[]").isEmpty())
    }

    @Test
    fun addressSourcesMergePartialMetadataAndDeduplicateScopes() {
        val result = NetworkInterfacesPolicy.mergeAddresses(
            listOf(InterfaceIp("192.168.43.1"), InterfaceIp("fe80::1%ap0")),
            listOf(ipv4.copy(broadcast = "192.168.43.255"), InterfaceIp("fe80::1", 64)),
        )
        assertEquals(2, result.size)
        assertEquals(24, result[0].prefixLength)
        assertEquals("192.168.43.255", result[0].broadcast)
        assertEquals("fe80::1%ap0", result[1].address)
        assertEquals(64, result[1].prefixLength)
    }

    @Test
    fun netmasksCoverPrefixBoundaryCases() {
        assertEquals("255.255.255.0", ipv4.netmask)
        assertEquals("0.0.0.0", ipv4.copy(prefixLength = 0).netmask)
        assertEquals("255.255.255.255", ipv4.copy(prefixLength = 32).netmask)
        assertEquals("255.255.255.254", ipv4.copy(prefixLength = 31).netmask)
        assertNull(ipv6.netmask)
        assertNull(ipv4.copy(prefixLength = null).netmask)
    }

    @Test
    fun tetheringEvidenceOverridesNameHeuristics() {
        assertEquals(HotspotKind.CONFIRMED, NetworkInterfacesPolicy.classifyHotspot("vendor0", setOf("vendor0")))
        assertEquals(HotspotKind.NONE, NetworkInterfacesPolicy.classifyHotspot("ap0", emptySet()))
        assertEquals(HotspotKind.POSSIBLE, NetworkInterfacesPolicy.classifyHotspot("ap0", null))
        assertEquals(HotspotKind.POSSIBLE, NetworkInterfacesPolicy.classifyHotspot("rndis0", null))
        assertEquals(HotspotKind.NONE, NetworkInterfacesPolicy.classifyHotspot("rmnet_data0", null))
    }

    @Test
    fun hiddenCellularAndTunnelNetworksAreNotSuggestedForSharing() {
        assertEquals(InterfaceKind.CELLULAR, NetworkInterfacesPolicy.inferKind("rmnet_data0"))
        assertEquals(InterfaceKind.CELLULAR, NetworkInterfacesPolicy.inferKind("v4-rmnet_data0"))
        assertEquals(InterfaceKind.CELLULAR, NetworkInterfacesPolicy.inferKind("ccmni0"))
        assertEquals(InterfaceKind.VPN, NetworkInterfacesPolicy.inferKind("tun0"))
        assertEquals(InterfaceKind.VPN, NetworkInterfacesPolicy.inferKind("wg0"))
        assertEquals(InterfaceKind.OTHER, NetworkInterfacesPolicy.inferKind("ap0"))
        val cellular = hotspot.copy(kind = NetworkInterfacesPolicy.inferKind("v4-rmnet_data0"))
        assertFalse(NetworkInterfacesPolicy.canShare(cellular, InterfaceIp("10.1.2.3", 24)))
    }

    @Test
    fun addressRemovalRemovesMatchingEndpoints() {
        val before = hotspot.copy(addresses = listOf(ipv4))
        val after = before.copy(addresses = emptyList())
        assertEquals(1, before.addresses.count { NetworkInterfacesPolicy.matches(mixed, before, it) })
        assertEquals(0, after.addresses.count { NetworkInterfacesPolicy.matches(mixed, after, it) })
    }
}
