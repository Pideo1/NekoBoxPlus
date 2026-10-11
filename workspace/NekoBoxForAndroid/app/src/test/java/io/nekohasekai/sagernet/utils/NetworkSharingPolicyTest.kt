package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSharingPolicyTest {
    @Test
    fun connectedProfilesRequireRestartWhileStoppedProfilesOnlySave() {
        assertEquals(SharingAction.RESTART, NetworkSharingPolicy.action(true, false, false))
        assertEquals(SharingAction.SAVE, NetworkSharingPolicy.action(false, false, false))
        assertEquals(SharingAction.UNAVAILABLE, NetworkSharingPolicy.action(true, true, false))
        assertEquals(SharingAction.UNAVAILABLE, NetworkSharingPolicy.action(false, true, false))
        assertEquals(SharingAction.UNAVAILABLE, NetworkSharingPolicy.action(true, false, true))
        assertEquals(SharingAction.UNAVAILABLE, NetworkSharingPolicy.action(false, false, true))
    }

    @Test
    fun localNetworkPermissionDependsOnAndroidVersion() {
        assertFalse(NetworkSharingPolicy.needsPermission(36, false))
        assertTrue(NetworkSharingPolicy.needsPermission(37, false))
        assertFalse(NetworkSharingPolicy.needsPermission(37, true))
        assertTrue(NetworkSharingPolicy.needsPermission(38, false))
    }

    @Test
    fun successRequiresActualMixedIpv4WildcardListenerAtExpectedPort() {
        val active = ProxyListenerInfo("mixed", "mixed-in", "0.0.0.0", 2080)
        assertTrue(NetworkSharingPolicy.applied(listOf(active), 2080))
        assertFalse(NetworkSharingPolicy.applied(emptyList(), 2080))
        assertFalse(NetworkSharingPolicy.applied(listOf(active), 3128))
        assertFalse(NetworkSharingPolicy.applied(listOf(active.copy(listen = "127.0.0.1")), 2080))
        assertFalse(NetworkSharingPolicy.applied(listOf(active.copy(type = "http")), 2080))
        assertFalse(NetworkSharingPolicy.applied(listOf(active.copy(bindInterface = "wlan0")), 2080))
        assertFalse(NetworkSharingPolicy.applied(listOf(active.copy(networkNamespace = "other")), 2080))
    }

    private fun settings(mode: SharingMode = SharingMode.VPN) =
        NetworkSharingSettings(mode, false, false, "127.0.0.1", false)

    @Test
    fun vpnToggleEnablesListenerAndDisableKeepsLocalProxy() {
        val enabled = NetworkSharingPolicy.change(settings(), true)
        assertTrue(enabled.enabled)
        assertTrue(enabled.requireProxy)
        val disabled = NetworkSharingPolicy.change(enabled.copy(mixedListener = "192.168.43.1"), false)
        assertFalse(disabled.enabled)
        assertTrue(disabled.requireProxy)
        assertEquals("127.0.0.1", disabled.mixedListener)
    }

    @Test
    fun proxyToggleDoesNotChangeVpnProxyPreference() {
        val enabled = NetworkSharingPolicy.change(settings(SharingMode.PROXY), true)
        assertTrue(enabled.enabled)
        assertFalse(enabled.requireProxy)
        assertFalse(NetworkSharingPolicy.change(enabled, false).enabled)
    }

    @Test
    fun tproxyToggleOnlyChangesTetheringSetting() {
        val original = settings(SharingMode.TPROXY).copy(allowAccess = true, mixedListener = "::")
        val enabled = NetworkSharingPolicy.change(original, true)
        assertEquals(original.copy(tproxyTethering = true), enabled)
        assertTrue(enabled.enabled)
        assertEquals(original, NetworkSharingPolicy.change(enabled, false))
        assertFalse(original.enabled)
    }

    @Test
    fun permissionAppliesOnlyToEnablingLanProxySharing() {
        for (mode in SharingMode.values()) {
            assertFalse(NetworkSharingPolicy.permissionRequired(mode, false, 37, false))
            assertFalse(NetworkSharingPolicy.permissionRequired(mode, true, 36, false))
            assertFalse(NetworkSharingPolicy.permissionRequired(mode, true, 37, true))
            assertEquals(mode != SharingMode.TPROXY, NetworkSharingPolicy.permissionRequired(mode, true, 37, false))
        }
    }

    @Test
    fun warningsIdentifyModeSpecificBlockers() {
        assertEquals(listOf(SharingWarning.LOCAL_PROXY_DISABLED, SharingWarning.LAN_DISABLED, SharingWarning.PERMISSION),
            NetworkSharingPolicy.warnings(settings(), false, false, false, false, false))
        assertEquals(listOf(SharingWarning.TPROXY_DISABLED),
            NetworkSharingPolicy.warnings(settings(SharingMode.TPROXY), false, false, false, false, false))
        assertEquals(listOf(SharingWarning.IPV4_DISABLED),
            NetworkSharingPolicy.warnings(settings(SharingMode.TPROXY).copy(tproxyTethering = true), false, false, true, true, true))
        assertEquals(emptyList<SharingWarning>(),
            NetworkSharingPolicy.warnings(settings().copy(requireProxy = true, mixedListener = "192.168.43.1"), true, false, true, true, false))
    }

    @Test
    fun unappliedSettingsAndCustomConfigurationsProduceWarnings() {
        assertEquals(listOf(SharingWarning.NOT_APPLIED), NetworkSharingPolicy.warnings(
            NetworkSharingPolicy.change(settings(), true), true, false, true, false, false))
        assertEquals(listOf(SharingWarning.CUSTOM_CONFIG), NetworkSharingPolicy.warnings(
            settings(), true, true, false, false, false))
    }

    @Test
    fun confirmationUsesActiveTetherCaptureForTproxyAndActualListenersForLan() {
        val listener = ProxyListenerInfo("mixed", "mixed-in", "0.0.0.0", 2080)
        assertTrue(NetworkSharingPolicy.applied(SharingMode.TPROXY, true, emptyList(), 2080, true))
        assertFalse(NetworkSharingPolicy.applied(SharingMode.TPROXY, true, listOf(listener), 2080, false))
        assertTrue(NetworkSharingPolicy.applied(SharingMode.TPROXY, false, listOf(listener), 2080, false))
        assertFalse(NetworkSharingPolicy.applied(SharingMode.VPN, false, listOf(listener), 2080, false))
        assertTrue(NetworkSharingPolicy.applied(SharingMode.VPN, false, listOf(listener.copy(listen = "127.0.0.1")), 2080, false))
    }
}
