package io.nekohasekai.sagernet.ui.dashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DashboardStateTest {
    private fun connection(id: String, closed: Long = 0) = JSONObject()
        .put("id", id).put("closedAt", closed).put("uplinkTotal", "12")
        .put("downlinkTotal", "24").put("createdAt", "100")
    private fun event(type: String, id: String, connection: JSONObject? = null) = JSONObject()
        .put("type", type).put("id", id).apply { connection?.let { put("connection", it) } }
    private fun batch(vararg events: JSONObject) = JSONObject().put("events", org.json.JSONArray(events.toList()))

    @Test fun appliesEventsAndFinalTraffic() {
        val store = DashboardConnectionStore()
        store.apply(batch(event("CONNECTION_EVENT_NEW", "a", connection("a"))))
        store.apply(batch(event("CONNECTION_EVENT_UPDATE", "a").put("uplinkDelta", "3").put("downlinkDelta", "4")))
        assertEquals(15L, store.snapshot().single().upload)
        assertEquals(3L, store.snapshot().single().uploadRate)
        store.apply(batch(event("CONNECTION_EVENT_CLOSED", "a", connection("a", 200).put("uplinkTotal", "20")).put("closedAt", "200")))
        assertEquals(20L, store.snapshot().single().upload)
        assertTrue(store.snapshot().single().closed)
        assertEquals(0L, store.snapshot().single().uploadRate)
    }
    @Test fun initialProtoDefaultEventAndReset() {
        val store = DashboardConnectionStore()
        store.apply(batch(JSONObject().put("id", "a").put("connection", connection("a"))))
        assertEquals("a", store.snapshot().single().id)
        store.apply(batch(event("CONNECTION_EVENT_NEW", "b", connection("b"))).put("reset", true))
        assertEquals("b", store.snapshot().single().id)
    }
    @Test fun boundsClosedHistoryWithoutRemovingActive() {
        val store = DashboardConnectionStore(closedLimit = 2)
        store.apply(batch(event("CONNECTION_EVENT_NEW", "active", connection("active"))))
        (1..4).forEach { id -> store.apply(batch(event("CONNECTION_EVENT_NEW", "$id", connection("$id", id.toLong())))) }
        assertEquals(setOf("active", "3", "4"), store.snapshot().map { it.id }.toSet())
    }
    @Test fun ignoresUnknownUpdateAndNegativeCounters() {
        val store = DashboardConnectionStore()
        store.apply(batch(event("CONNECTION_EVENT_UPDATE", "unknown").put("uplinkDelta", 100)))
        assertTrue(store.snapshot().isEmpty())
        store.apply(batch(event("CONNECTION_EVENT_NEW", "a", connection("a"))))
        store.apply(batch(event("CONNECTION_EVENT_UPDATE", "a").put("uplinkDelta", -5)))
        assertEquals(12L, store.snapshot().single().upload)
    }
    @Test fun filtersSearchesAndSortsDeterministically() {
        val active = DashboardConnection("b", domain = "Example.COM", packages = listOf("org.browser"), download = 20)
        val closed = DashboardConnection("a", closedAt = 5, download = 40)
        assertEquals(listOf(active), visibleConnections(listOf(active, closed), "example", true, true, ConnectionSort.START, false))
        assertTrue(active.matches("BROWSER"))
        assertEquals(listOf(closed, active), visibleConnections(listOf(active, closed), "", true, true, ConnectionSort.DOWNLOAD, true))
        assertTrue(visibleConnections(listOf(active, closed), "", false, false, ConnectionSort.START, false).isEmpty())
    }
    @Test fun expandsNestedGroupsDeduplicatesAndStopsCycles() {
        val leaf = DashboardProxy("server", "shadowsocks")
        val nested = DashboardProxy("nested", "selector")
        val groups = listOf(DashboardGroup("nested", "selector", "", true, true,
            listOf(leaf, nested, DashboardProxy("direct", "direct"))))
        assertEquals(listOf(leaf), expandTestTargets(listOf(nested, leaf), groups))
    }
    @Test fun parsesIPv6AndPortsForRouteDraft() {
        assertEquals("2001:db8::1" to "443", splitEndpoint("[2001:db8::1]:443"))
        assertEquals("192.0.2.1" to "80", splitEndpoint("192.0.2.1:80"))
        assertEquals("2001:db8::1" to "", splitEndpoint("2001:db8::1"))
        assertEquals("" to "", splitEndpoint(""))
    }
    @Test fun latencyWithoutHistoryIsUntested() {
        assertEquals(-1, DashboardProxy.parse(JSONObject().put("tag", "test")).delay)
        assertEquals(0, DashboardProxy.parse(JSONObject().put("urlTestTime", "42").put("urlTestDelay", 0)).delay)
    }
}
