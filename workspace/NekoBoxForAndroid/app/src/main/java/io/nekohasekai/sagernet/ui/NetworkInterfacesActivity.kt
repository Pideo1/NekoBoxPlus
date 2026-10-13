package io.nekohasekai.sagernet.ui

import android.content.pm.PackageManager
import android.net.TetheringManager
import android.net.TetheringInterface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.LocalNetworkPermission
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.NetworkInterfacesScreen
import io.nekohasekai.sagernet.utils.NetworkInterfacesPolicy
import io.nekohasekai.sagernet.utils.NetworkInterfacesRepository
import io.nekohasekai.sagernet.utils.NetworkInterfacesSnapshot
import io.nekohasekai.sagernet.utils.NetworkSharingPolicy
import io.nekohasekai.sagernet.utils.NetworkSharingPreferences
import io.nekohasekai.sagernet.utils.NetworkSharingSettings
import io.nekohasekai.sagernet.utils.SharingMode
import io.nekohasekai.sagernet.utils.ProxyListenerInfo
import io.nekohasekai.sagernet.utils.SharingAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.proxy.config.ConfigBean

class NetworkInterfacesActivity : ThemedActivity(), SagerConnection.Callback {
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_NETWORK_INTERFACES, listenForDeath = true)
    private val repository by lazy { NetworkInterfacesRepository(SagerNet.connectivity) }
    private val refreshMutex = Mutex()
    private val snackbar = SnackbarHostState()
    private var snapshot by mutableStateOf(NetworkInterfacesSnapshot())
    private var listeners by mutableStateOf<List<ProxyListenerInfo>>(emptyList())
    private var loading by mutableStateOf(true)
    private var serviceState by mutableStateOf(BaseService.State.Idle)
    private var configurable by mutableStateOf(false)
    private var customConfig by mutableStateOf(false)
    private var sharingBusy by mutableStateOf(false)
    private var localNetworkAllowed by mutableStateOf(false)
    private var status by mutableStateOf<Int?>(null)
    private var generation = 0L
    private var tethered: Set<String>? = null
    private var stopTetheringObserver: (() -> Unit)? = null
    private var settings by mutableStateOf(NetworkSharingSettings(SharingMode.VPN, false, false, "127.0.0.1", false))
    private var tproxyActive by mutableStateOf(false)
    private var ipv4Disabled by mutableStateOf(false)
    private var expectedChange: SharingChange? = null
    private var requestedEnabled: Boolean? = null

    private data class SharingChange(val mode: SharingMode, val enabled: Boolean, val port: Int)
    private var sharingDeadline = 0L
    private var awaitingPermission = false
    private var applyOnConnection = false
    private var connectionDeadline = 0L

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (awaitingPermission) {
            awaitingPermission = false
            if (granted) requestApplySharing() else {
                sharingBusy = false
                requestedEnabled = null
                status = R.string.network_interfaces_permission_denied
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingPermission = savedInstanceState?.getBoolean("sharing_permission") ?: false
        applyOnConnection = savedInstanceState?.getBoolean("sharing_waiting_connection") ?: false
        connectionDeadline = savedInstanceState?.getLong("sharing_connection_deadline") ?: 0L
        requestedEnabled = savedInstanceState?.takeIf { it.containsKey("sharing_target") }?.getBoolean("sharing_target")
        val savedPort = savedInstanceState?.getInt("sharing_port", 0) ?: 0
        if (savedPort > 0) {
            val mode = savedInstanceState?.getString("sharing_mode")?.let { value -> SharingMode.values().find { it.name == value } }
                ?: NetworkSharingPreferences.load().mode
            expectedChange = SharingChange(mode, requestedEnabled ?: true, savedPort)
        }
        sharingDeadline = savedInstanceState?.getLong("sharing_deadline") ?: 0L
        sharingBusy = awaitingPermission || applyOnConnection || expectedChange != null
        if (expectedChange != null) status = R.string.network_interfaces_applying
        setContent {
            NekoComposeTheme {
                NetworkInterfacesScreen(
                    snapshot = snapshot,
                    listeners = listeners,
                    loading = loading,
                    connected = serviceState == BaseService.State.Connected,
                    localNetworkAllowed = localNetworkAllowed,
                    mode = settings.mode,
                    sharingChecked = requestedEnabled ?: settings.enabled,
                    sharingEnabled = configurable && !sharingBusy &&
                        serviceState !in listOf(BaseService.State.Connecting, BaseService.State.Stopping),
                    sharingBusy = sharingBusy,
                    warnings = if (loading || sharingBusy) emptyList() else NetworkSharingPolicy.warnings(
                        settings, localNetworkAllowed, customConfig,
                        serviceState == BaseService.State.Connected,
                        if (settings.mode == SharingMode.TPROXY) tproxyActive else NetworkSharingPolicy.hasExternalListener(listeners),
                        ipv4Disabled,
                    ),
                    status = status,
                    snackbar = snackbar,
                    onBack = ::finish,
                    onRefresh = ::refresh,
                    onSharingChanged = ::setSharing,
                    onCopy = { value ->
                        val message = if (SagerNet.trySetPrimaryClip(value)) R.string.network_interfaces_copied
                            else R.string.network_interfaces_copy_failed
                        lifecycleScope.launch { snackbar.showSnackbar(getString(message)) }
                    },
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    collect()
                    delay(2_000)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.connect(this, this)
        if (Build.VERSION.SDK_INT >= 36) observeTethering()
    }

    override fun onStop() {
        generation++
        stopTetheringObserver?.invoke()
        stopTetheringObserver = null
        connection.disconnect(this)
        listeners = emptyList()
        configurable = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("sharing_permission", awaitingPermission)
        outState.putBoolean("sharing_waiting_connection", applyOnConnection)
        outState.putLong("sharing_connection_deadline", connectionDeadline)
        requestedEnabled?.let { outState.putBoolean("sharing_target", it) }
        outState.putInt("sharing_port", expectedChange?.port ?: 0)
        outState.putString("sharing_mode", expectedChange?.mode?.name)
        outState.putLong("sharing_deadline", sharingDeadline)
        super.onSaveInstanceState(outState)
    }

    @RequiresApi(36)
    private fun observeTethering() {
        val manager = getSystemService(TetheringManager::class.java) ?: return
        val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                tethered = interfaces.map { it.getInterface() }.toSet()
                refresh()
            }
        }
        runCatching {
            manager.registerTetheringEventCallback(ContextCompat.getMainExecutor(this), callback)
            stopTetheringObserver = { runCatching { manager.unregisterTetheringEventCallback(callback) } }
        }
    }

    private fun refresh() {
        lifecycleScope.launch { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) collect() }
    }

    private suspend fun collect() = refreshMutex.withLock {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withLock
        val version = generation
        val service = connection.service
        val tetheredSnapshot = tethered
        val result = withContext(Dispatchers.IO) {
            val granted = ContextCompat.checkSelfPermission(this@NetworkInterfacesActivity, LocalNetworkPermission.NAME) == PackageManager.PERMISSION_GRANTED
            val localNetworkAllowed = !NetworkSharingPolicy.needsPermission(Build.VERSION.SDK_INT, granted)
            val sharingSettings = NetworkSharingPreferences.load()
            val ipv4Disabled = DataStore.ipv6Mode == IPv6Mode.ONLY
            val interfaces = repository.collect(tetheredSnapshot)
            val state = runCatching { service?.state?.let { BaseService.State.values().getOrNull(it) } }
                .getOrNull() ?: BaseService.State.Idle
            val active = if (state == BaseService.State.Connected) runCatching {
                NetworkInterfacesPolicy.listenersFromJson(service!!.activeProxyListeners())
            }.getOrDefault(emptyList()) else emptyList()
            val profile = if (state != BaseService.State.Connected) runCatching {
                AppData.profiles.getById(DataStore.selectedProxy)
            }.getOrNull() else null
            val raw = (profile?.requireBean() as? ConfigBean)?.type == 0
            val canConfigure = if (state == BaseService.State.Connected) {
                runCatching { service!!.isNetworkSharingConfigurable }.getOrDefault(false)
            } else profile != null && !raw && service != null
            val tproxyActive = state == BaseService.State.Connected && sharingSettings.mode == SharingMode.TPROXY &&
                runCatching { service!!.isTProxyTetheringActive }.getOrDefault(false)
            Collected(interfaces, state, active, canConfigure, if (state == BaseService.State.Connected) !canConfigure else raw,
                localNetworkAllowed, sharingSettings, tproxyActive, ipv4Disabled)
        }
        if (version != generation || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withLock
        snapshot = result.snapshot
        serviceState = result.state
        listeners = result.listeners
        configurable = result.configurable
        customConfig = result.customConfig
        localNetworkAllowed = result.localNetworkAllowed
        settings = result.settings
        tproxyActive = result.tproxyActive
        ipv4Disabled = result.ipv4Disabled
        loading = false
        if (applyOnConnection && SystemClock.elapsedRealtime() >= connectionDeadline) {
            applyOnConnection = false
            sharingBusy = false
            requestedEnabled = null
            status = R.string.network_interfaces_sharing_failed
        }
        expectedChange?.let { change ->
            when {
                result.state == BaseService.State.Connected && result.settings.mode == change.mode &&
                    (change.mode == SharingMode.TPROXY || !change.enabled || result.localNetworkAllowed) &&
                    NetworkSharingPolicy.applied(change.mode, change.enabled, result.listeners, change.port, result.tproxyActive) -> {
                    expectedChange = null
                    requestedEnabled = null
                    sharingBusy = false
                    status = if (change.enabled) {
                        if (change.mode == SharingMode.TPROXY) R.string.network_interfaces_tproxy_enabled
                        else R.string.network_interfaces_sharing_enabled
                    } else R.string.network_interfaces_sharing_disabled
                }
                result.state == BaseService.State.Connected || SystemClock.elapsedRealtime() >= sharingDeadline -> {
                    expectedChange = null
                    requestedEnabled = null
                    sharingBusy = false
                    status = R.string.network_interfaces_sharing_failed
                }
            }
        }

    }

    private data class Collected(
        val snapshot: NetworkInterfacesSnapshot,
        val state: BaseService.State,
        val listeners: List<ProxyListenerInfo>,
        val configurable: Boolean,
        val customConfig: Boolean,
        val localNetworkAllowed: Boolean,
        val settings: NetworkSharingSettings,
        val tproxyActive: Boolean,
        val ipv4Disabled: Boolean,
    )

    private fun setSharing(enabled: Boolean) {
        if (!configurable || sharingBusy || serviceState in listOf(BaseService.State.Connecting, BaseService.State.Stopping)) return
        sharingBusy = true
        requestedEnabled = enabled
        val granted = ContextCompat.checkSelfPermission(this, LocalNetworkPermission.NAME) == PackageManager.PERMISSION_GRANTED
        if (NetworkSharingPolicy.permissionRequired(settings.mode, enabled, Build.VERSION.SDK_INT, granted)) {
            awaitingPermission = true
            permissionRequest.launch(LocalNetworkPermission.NAME)
        } else requestApplySharing()
    }

    private fun requestApplySharing() {
        if (connection.service == null) {
            // Permission dialogs can stop this activity and detach its service connection.
            applyOnConnection = true
            connectionDeadline = SystemClock.elapsedRealtime() + 10_000
        } else applySharing()
    }

    private fun applySharing() {
        lifecycleScope.launch {
            status = R.string.network_interfaces_applying
            generation++
            listeners = emptyList()
            val enabled = requestedEnabled ?: return@launch
            val change = SharingChange(NetworkSharingPreferences.load().mode, enabled, DataStore.mixedPort)
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val granted = ContextCompat.checkSelfPermission(this@NetworkInterfacesActivity, LocalNetworkPermission.NAME) == PackageManager.PERMISSION_GRANTED
                    if (NetworkSharingPolicy.permissionRequired(change.mode, enabled, Build.VERSION.SDK_INT, granted)) return@runCatching SharingAction.UNAVAILABLE
                    val service = connection.service ?: return@runCatching SharingAction.UNAVAILABLE
                    val state = BaseService.State.values().getOrNull(service.state) ?: BaseService.State.Idle
                    if (state == BaseService.State.Connected) {
                        if (service.setNetworkSharingEnabled(enabled)) SharingAction.RESTART else SharingAction.UNAVAILABLE
                    } else {
                        val profile = AppData.profiles.getById(DataStore.selectedProxy)
                        val raw = (profile?.requireBean() as? ConfigBean)?.type == 0
                        val action = NetworkSharingPolicy.action(
                            connected = false,
                            transitioning = state in listOf(BaseService.State.Connecting, BaseService.State.Stopping),
                            customConfig = raw || profile == null,
                        )
                        if (action == SharingAction.SAVE) {
                            NetworkSharingPreferences.save(enabled)
                        }
                        action
                    }
                }.getOrDefault(SharingAction.UNAVAILABLE)
            }
            // Discard snapshots collected before the restart request completed.
            generation++
            when (outcome) {
                SharingAction.RESTART -> {
                    expectedChange = change
                    sharingDeadline = SystemClock.elapsedRealtime() + 30_000
                }
                SharingAction.SAVE -> {
                    sharingBusy = false
                    requestedEnabled = null
                    status = R.string.network_interfaces_saved
                }
                SharingAction.UNAVAILABLE -> {
                    sharingBusy = false
                    status = R.string.network_interfaces_sharing_failed
                }
            }
            refresh()
        }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        generation++
        if (applyOnConnection) {
            applyOnConnection = false
            applySharing()
        }
        refresh()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        generation++
        serviceState = state
        if (state != BaseService.State.Connected) listeners = emptyList()
        if (state != BaseService.State.Connected && !sharingBusy) status = null
        if (!msg.isNullOrBlank()) lifecycleScope.launch { snackbar.showSnackbar(msg) }
        refresh()
    }

    override fun onServiceDisconnected() {
        generation++
        listeners = emptyList()
        configurable = false
        serviceState = BaseService.State.Idle
    }

    override fun onBinderDied() = onServiceDisconnected()
}
