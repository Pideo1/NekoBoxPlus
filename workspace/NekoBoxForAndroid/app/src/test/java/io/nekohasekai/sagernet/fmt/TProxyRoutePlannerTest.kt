package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import org.junit.Assert.*
import org.junit.Test

class TProxyRoutePlannerTest {
    @Test fun deviceGuardsDoNotApplyToTetheredClients() {
        val guard = mutableMapOf<String, Any?>("inbound" to listOf(TAG_TUN), "user_id" to listOf(1051))
        val nested = mutableMapOf<String, Any?>("inbound" to listOf(TAG_TUN, TAG_MIXED))
        val dns = mutableMapOf<String, Any?>("inbound" to listOf(TAG_TUN), "server" to "dns-fake")
        val config = mutableMapOf<String, Any?>(
            "route" to mutableMapOf("rules" to listOf(guard, mutableMapOf("rules" to listOf(nested)))),
            "dns" to mutableMapOf("rules" to listOf(dns)),
        )
        val device = listOf("tproxy-in4", "tproxy-in6")
        val all = device + listOf("tproxy-tether-in4", "tproxy-tether-in6")
        retargetTunRules(config, device, all)
        assertEquals(device, guard["inbound"])
        assertEquals(listOf(1051), guard["user_id"])
        assertEquals(listOf(TAG_MIXED) + device, nested["inbound"])
        assertEquals(all, dns["inbound"])
    }

    @Test fun disabledFamiliesHaveNoInbound() {
        assertEquals(listOf("tproxy-in4"), tproxyDeviceTags(IPv6Mode.DISABLE))
        assertEquals(listOf("tproxy-in6"), tproxyDeviceTags(IPv6Mode.ONLY))
        assertEquals(listOf("tproxy-tether-in6"), tproxyTetherTags(IPv6Mode.ONLY))
    }
}
