package io.nekohasekai.sagernet.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import libcore.DashboardStreamHandler
import org.json.JSONObject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val createBridge: (String) -> DashboardBridge = ::NativeDashboardBridge) : ViewModel() {
    var state by mutableStateOf(DashboardUiState())
        private set
    var page by mutableStateOf(0)
    var selectedId by mutableStateOf<String?>(null)
    var paused by mutableStateOf(false)
    var query by mutableStateOf("")
    var proxyQuery by mutableStateOf("")
    var groupQuery by mutableStateOf("")
    var searchedGroup by mutableStateOf<String?>(null)
    var showActive by mutableStateOf(DataStore.configurationStore.getBoolean("dashboardActive", true))
    var showClosed by mutableStateOf(DataStore.configurationStore.getBoolean("dashboardClosed", false))
    var descending by mutableStateOf(DataStore.configurationStore.getBoolean("dashboardDescending", false))
    var sort by mutableStateOf(ConnectionSort.entries.getOrElse(DataStore.configurationStore.getInt("dashboardSort", 0)) { ConnectionSort.START })
    var proxyOrder by mutableStateOf(DataStore.configurationStore.getInt("dashboardProxyOrder", 0))
    var widgets by mutableStateOf(readWidgets())
        private set
    private var session: Job? = null
    private var client: DashboardBridge? = null
    private val store = DashboardConnectionStore()
    private class ActiveSession(val bridge: DashboardBridge, val scope: CoroutineScope) {
        val semaphore = Semaphore(DataStore.connectionTestConcurrent.coerceIn(1, 16))
    }
    private var activeSession: ActiveSession? = null

    private fun clearTests() {
        state = state.copy(testing = emptySet(), progress = emptyMap())
    }

    private fun readWidgets(): List<Pair<String, Boolean>> {
        val defaults = listOf("proxy", "direct", "status", "mode", "addresses", "interfaces", "openvpn", "openconnect")
        val stored = DataStore.configurationStore.getString("dashboardWidgets", "").orEmpty()
        val entries = stored.split(',').mapNotNull { entry ->
            val id = entry.removePrefix("!")
            if (id in defaults) id to !entry.startsWith("!") else null
        }.distinctBy { it.first }
        return entries + defaults.filter { id -> entries.none { it.first == id } }.map { it to true }
    }
    fun updateWidgets(entries: List<Pair<String, Boolean>>) {
        widgets = entries
        viewModelScope.launch(Dispatchers.IO) {
            DataStore.configurationStore.putString("dashboardWidgets", entries.joinToString(",") { (id, visible) -> if (visible) id else "!$id" })
        }
    }
    fun persistFilters() = viewModelScope.launch(Dispatchers.IO) {
        DataStore.configurationStore.putBoolean("dashboardActive", showActive)
        DataStore.configurationStore.putBoolean("dashboardClosed", showClosed)
        DataStore.configurationStore.putBoolean("dashboardDescending", descending)
        DataStore.configurationStore.putInt("dashboardSort", sort.ordinal)
        DataStore.configurationStore.putInt("dashboardProxyOrder", proxyOrder)
    }
    fun pause() { paused = !paused; if (!paused) state = state.copy(connections = store.snapshot()) }

    fun start() {
        if (session?.isActive == true) return
        state = state.copy(stopped = false)
        session = viewModelScope.launch {
            while (currentCoroutineContext()[Job]?.isActive == true) {
                var current: DashboardBridge? = null
                try {
                    val active = withContext(Dispatchers.IO) { createBridge(DataStore.dashboardSocketPath) }
                    current = active
                    client = active
                    store.clear()
                    val mode = withContext(Dispatchers.IO) { JSONObject(active.call("GetClashModeStatus", "{}", 3000)) }
                    state = state.copy(available = true, error = "", connections = emptyList(), groups = emptyList(),
                        modes = mode.optJSONArray("modeList").strings(), mode = mode.optString("currentMode"))
                    coroutineScope {
                        activeSession = ActiveSession(active, this)
                        fun subscribe(method: String, request: String = "{}", consume: (JSONObject) -> Unit) = launch {
                            try {
                                callbackFlow {
                                    val call = active.stream(method, request, object : DashboardStreamHandler {
                                        override fun onMessage(message: String) {
                                            if (trySend(JSONObject(message)).isFailure) close(IllegalStateException("Dashboard updates require resynchronization"))
                                        }
                                        override fun onClosed(message: String) {
                                            close(IllegalStateException(message.ifBlank { "Dashboard stream closed" }))
                                        }
                                    })
                                    awaitClose { call.close() }
                                }.flowOn(Dispatchers.IO).collect(consume)
                            } finally {
                                // End in-flight unary calls too; a failed stream must not wait on a probe deadline.
                                withContext(NonCancellable + Dispatchers.IO) { active.close() }
                            }
                        }
                        subscribe("SubscribeStatus", "{\"interval\":\"1000000000\"}") {
                            state = state.copy(memory = it.optLong("memory"), goroutines = it.optInt("goroutines"),
                                activeCount = it.optInt("connectionsIn"), upload = it.optLong("uplinkTotal"), download = it.optLong("downlinkTotal"))
                        }
                        subscribe("SubscribeTraffic") {
                            val pu = it.optLong("proxyUp"); val pd = it.optLong("proxyDown")
                            val du = it.optLong("directUp"); val dd = it.optLong("directDown")
                            state = state.copy(proxyUp = pu, proxyDown = pd, directUp = du, directDown = dd,
                                proxyHistory = state.proxyHistory.drop(1) + (pu + pd).toFloat(),
                                directHistory = state.directHistory.drop(1) + (du + dd).toFloat())
                        }
                        subscribe("SubscribeConnections", "{\"interval\":\"1000000000\"}") {
                            store.apply(it); if (!paused) state = state.copy(connections = store.snapshot())
                        }
                        subscribe("SubscribeGroups") { state = state.copy(groups = it.optJSONArray("group").objects().map(DashboardGroup::parse)) }
                        subscribe("SubscribeOutbounds") { state = state.copy(outbounds = it.optJSONArray("outbounds").objects().map(DashboardProxy::parse)) }
                        subscribe("SubscribeClashMode") { state = state.copy(mode = it.optString("mode")) }
                        subscribe("SubscribeOpenVPNStatus") { state = state.copy(openVPN = it.optJSONArray("endpoints").objects()) }
                        subscribe("SubscribeOpenConnectStatus") { state = state.copy(openConnect = it.optJSONArray("endpoints").objects()) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { state = state.copy(available = false, error = e.localizedMessage.orEmpty()) }
                finally {
                    if (client === current) {
                        client = null
                        activeSession = null
                        clearTests()
                    }
                    withContext(NonCancellable + Dispatchers.IO) { current?.close() }
                }
                delay(1000)
            }
        }
    }
    fun stop(stopped: Boolean = true) {
        val closing = client
        activeSession = null
        client = null
        session?.cancel(); session = null
        if (closing != null) viewModelScope.launch(NonCancellable + Dispatchers.IO) { closing.close() }
        clearTests()
        state = state.copy(available = false, stopped = stopped, proxyUp = 0, proxyDown = 0, directUp = 0, directDown = 0)
        if (stopped) {
            store.clear()
            state = DashboardUiState()
        }
    }
    fun call(method: String, request: JSONObject = JSONObject(), onSuccess: () -> Unit = {}): Job? {
        val active = activeSession ?: return null
        return active.scope.launch {
            try {
                withContext(Dispatchers.IO) { active.bridge.call(method, request.toString(), 10000) }
                if (activeSession === active) onSuccess()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (activeSession === active) state = state.copy(error = e.localizedMessage.orEmpty())
            }
        }
    }
    fun clearError() { state = state.copy(error = "") }
    fun closeConnection(id: String) = call("CloseConnection", JSONObject().put("id", id))
    fun resetNetwork() = call("ResetNetwork")
    fun setMode(mode: String) = call("SetClashMode", JSONObject().put("mode", mode))
    fun select(group: String, tag: String) = call("SelectOutbound", JSONObject().put("groupTag", group).put("outboundTag", tag)) {
        state = state.copy(groups = state.groups.map { if (it.tag == group) it.copy(selected = tag) else it })
    }
    fun expand(group: String, expanded: Boolean) = call("SetGroupExpand", JSONObject().put("groupTag", group).put("isExpand", expanded)) {
        state = state.copy(groups = state.groups.map { if (it.tag == group) it.copy(expanded = expanded) else it })
    }
    fun test(items: List<DashboardProxy>, key: String): Job? {
        val active = activeSession ?: return null
        return active.scope.launch {
            if (!state.available || activeSession !== active || key in state.progress) return@launch
            val targets = expandTestTargets(items, state.groups).filter { it.tag !in state.testing }
            if (targets.isEmpty()) return@launch
            val tags = targets.map { it.tag }.toSet()
            // Reserve queued profiles too, so group and individual buttons cannot duplicate a probe.
            state = state.copy(testing = state.testing + tags, testErrors = state.testErrors - tags,
                progress = state.progress + (key to (0 to targets.size)))
            try {
                coroutineScope {
                    targets.map { proxy -> async {
                        active.semaphore.withPermit {
                            try {
                                val request = JSONObject().put("tag", proxy.tag).put("url", DataStore.connectionTestURL)
                                    .put("timeout", DataStore.connectionTestTimeout).put("attempts", DataStore.connectionTestAttempts.coerceIn(1, 10))
                                    .put("pause", DataStore.connectionTestPause).put("hardened", DataStore.connectionTestHardened)
                                // Core allows a readiness timeout followed by a probe timeout.
                                val timeout = (2 * DataStore.connectionTestTimeout.toLong() + 15000)
                                    .coerceIn(15000, Int.MAX_VALUE.toLong()).toInt()
                                withContext(Dispatchers.IO) { active.bridge.call("TestOutbound", request.toString(), timeout) }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (activeSession === active) state = state.copy(
                                    testErrors = state.testErrors + (proxy.tag to e.localizedMessage.orEmpty()))
                            } finally {
                                if (activeSession === active) {
                                    val done = state.progress[key]?.first ?: 0
                                    state = state.copy(testing = state.testing - proxy.tag,
                                        progress = state.progress + (key to (done + 1 to targets.size)))
                                }
                            }
                        }
                    } }.awaitAll()
                }
            } finally {
                // A cancelled native call may return after a replacement session has started.
                if (activeSession === active) state = state.copy(
                    testing = state.testing - tags, progress = state.progress - key)
            }
        }
    }
    override fun onCleared() { session?.cancel() }
}
