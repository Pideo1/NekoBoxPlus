/* SPDX-License-Identifier: GPL-3.0-or-later
 * Native dashboard behavior adapted from Husi's dashboard state model.
 */
package io.nekohasekai.sagernet.ui.dashboard

import org.json.JSONArray
import org.json.JSONObject

data class DashboardConnection(
    val id: String,
    val inbound: String = "",
    val source: String = "",
    val destination: String = "",
    val domain: String = "",
    val network: String = "",
    val protocol: String = "",
    val rule: String = "",
    val outbound: String = "",
    val chain: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val uid: Int = -1,
    val process: String = "",
    val createdAt: Long = 0,
    val closedAt: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val uploadRate: Long = 0,
    val downloadRate: Long = 0,
) {
    val closed get() = closedAt > 0
    fun matches(query: String): Boolean = listOf(inbound, source, destination, domain, network,
        protocol, rule, outbound, process, uid.toString()).plus(chain).plus(packages)
        .any { it.contains(query, ignoreCase = true) }

    companion object {
        fun parse(value: JSONObject): DashboardConnection {
            val process = value.optJSONObject("processInfo") ?: JSONObject()
            return DashboardConnection(
                id = value.getString("id"), inbound = value.optString("inbound"),
                source = value.optString("source"), destination = value.optString("destination"),
                domain = value.optString("domain"), network = value.optString("network"),
                protocol = value.optString("protocol"), rule = value.optString("rule"),
                outbound = value.optString("outbound"), chain = value.optJSONArray("chainList").strings(),
                packages = process.optJSONArray("packageNames").strings(), uid = process.optInt("userId", -1),
                process = process.optString("processPath"), createdAt = value.optLong("createdAt"),
                closedAt = value.optLong("closedAt"), upload = value.optLong("uplinkTotal"),
                download = value.optLong("downlinkTotal"), uploadRate = value.optLong("uplink"),
                downloadRate = value.optLong("downlink"),
            )
        }
    }
}

/** Event application is independent of Compose/JNI, and keeps detail identity stable. */
class DashboardConnectionStore(private val closedLimit: Int = 1000) {
    private val connections = linkedMapOf<String, DashboardConnection>()
    fun clear() = connections.clear()
    fun snapshot(): List<DashboardConnection> = connections.values.toList()
    fun apply(message: JSONObject) {
        if (message.optBoolean("reset")) clear()
        message.optJSONArray("events").objects().forEach { event ->
            val id = event.optString("id")
            when (event.optString("type", "CONNECTION_EVENT_NEW")) {
                "CONNECTION_EVENT_NEW" -> event.optJSONObject("connection")?.let {
                    connections[id] = DashboardConnection.parse(it)
                }
                "CONNECTION_EVENT_UPDATE" -> connections[id]?.let { old ->
                    val up = event.optLong("uplinkDelta").coerceAtLeast(0)
                    val down = event.optLong("downlinkDelta").coerceAtLeast(0)
                    connections[id] = old.copy(upload = old.upload + up, download = old.download + down,
                        uploadRate = up, downloadRate = down)
                }
                "CONNECTION_EVENT_CLOSED" -> {
                    val old = event.optJSONObject("connection")?.let(DashboardConnection::parse) ?: connections[id]
                    old?.let { connections[id] = it.copy(closedAt = event.optLong("closedAt", it.closedAt),
                        uploadRate = 0, downloadRate = 0) }
                }
            }
        }
        connections.values.filter { it.closed }.sortedBy { it.closedAt }
            .dropLast(closedLimit).forEach { connections.remove(it.id) }
    }
}

enum class ConnectionSort { START, INBOUND, SOURCE, DESTINATION, UPLOAD, DOWNLOAD, UPLOAD_SPEED, DOWNLOAD_SPEED, RULE }
fun visibleConnections(items: List<DashboardConnection>, query: String, active: Boolean,
    closed: Boolean, sort: ConnectionSort, descending: Boolean): List<DashboardConnection> {
    val comparator = when (sort) {
        ConnectionSort.START -> compareBy<DashboardConnection> { it.createdAt }
        ConnectionSort.INBOUND -> compareBy { it.inbound }
        ConnectionSort.SOURCE -> compareBy { it.source }
        ConnectionSort.DESTINATION -> compareBy { it.domain.ifBlank { it.destination } }
        ConnectionSort.UPLOAD -> compareBy { it.upload }
        ConnectionSort.DOWNLOAD -> compareBy { it.download }
        ConnectionSort.UPLOAD_SPEED -> compareBy { it.uploadRate }
        ConnectionSort.DOWNLOAD_SPEED -> compareBy { it.downloadRate }
        ConnectionSort.RULE -> compareBy { it.rule }
    }.thenBy { it.id }
    return items.filter { (if (it.closed) closed else active) && it.matches(query) }
        .sortedWith(if (descending) comparator.reversed() else comparator)
}

data class DashboardProxy(val tag: String, val type: String, val delay: Int = -1) {
    companion object {
        fun parse(value: JSONObject) = DashboardProxy(value.optString("tag"), value.optString("type"),
            value.optInt("urlTestDelay", -1).takeIf { value.optLong("urlTestTime") > 0 } ?: -1)
    }
}
data class DashboardGroup(val tag: String, val type: String, val selected: String,
    val selectable: Boolean, val expanded: Boolean, val items: List<DashboardProxy>) {
    companion object {
        fun parse(value: JSONObject) = DashboardGroup(value.optString("tag"), value.optString("type"),
            value.optString("selected"), value.optBoolean("selectable"), value.optBoolean("isExpand"),
            value.optJSONArray("items").objects().map(DashboardProxy::parse))
    }
}
fun expandTestTargets(items: List<DashboardProxy>, groups: List<DashboardGroup>): List<DashboardProxy> {
    val members = groups.associateBy { it.tag }
    val visited = mutableSetOf<String>()
    val leaves = mutableListOf<DashboardProxy>()
    fun visit(item: DashboardProxy) {
        if (!visited.add(item.tag)) return
        val group = members[item.tag]
        if (group != null) group.items.forEach(::visit)
        else if (item.type !in setOf("direct", "block", "reject", "dns")) leaves.add(item)
    }
    items.forEach(::visit)
    return leaves
}

data class DashboardUiState(
    val available: Boolean = false, val stopped: Boolean = true, val error: String = "",
    val memory: Long = 0, val goroutines: Int = 0, val activeCount: Int = 0,
    val upload: Long = 0, val download: Long = 0,
    val proxyUp: Long = 0, val proxyDown: Long = 0, val directUp: Long = 0, val directDown: Long = 0,
    val proxyHistory: List<Float> = List(60) { 0f }, val directHistory: List<Float> = List(60) { 0f },
    val connections: List<DashboardConnection> = emptyList(),
    val groups: List<DashboardGroup> = emptyList(), val outbounds: List<DashboardProxy> = emptyList(),
    val modes: List<String> = emptyList(), val mode: String = "",
    val openVPN: List<JSONObject> = emptyList(), val openConnect: List<JSONObject> = emptyList(),
    val testing: Set<String> = emptySet(), val progress: Map<String, Pair<Int, Int>> = emptyMap(),
    val testErrors: Map<String, String> = emptyMap(),
)
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).map { optString(it) }

fun splitEndpoint(value: String): Pair<String, String> {
    if (value.startsWith("[")) {
        val end = value.indexOf(']')
        return if (end >= 0) value.substring(1, end) to value.substring(end + 1).removePrefix(":") else value to ""
    }
    return if (value.count { it == ':' } == 1) value.substringBeforeLast(':') to value.substringAfterLast(':') else value to ""
}
