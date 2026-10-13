package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.provider.Settings
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.dashboard.DashboardViewModel
import io.nekohasekai.sagernet.ui.dashboard.NativeDashboardScreen
import org.json.JSONObject

class DashboardFragment : ToolbarFragment(), SagerConnection.Callback {
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_DASHBOARD, listenForDeath = true)
    private val model: DashboardViewModel by viewModels()
    private var visible = false
    private var serviceState = BaseService.State.Idle
    private val browserAuth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra("response")?.let {
            val response = JSONObject(it)
            lifecycleScope.launch {
                // Browser authentication pauses this fragment and closes its dashboard session.
                snapshotFlow { model.state.available }.first { available -> available }
                model.call("SubmitOpenConnectAuthResponse", response)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection.connect(requireContext(), this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                NativeDashboardScreen(model, onOpenDrawer = { (activity as? MainActivity)?.openDrawer() },
                    onInterfaces = { startActivity(Intent(requireContext(), NetworkInterfacesActivity::class.java)) },
                    onApp = { pkg -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))) },
                    onRoute = { draft -> startActivity(Intent(requireContext(), RouteSettingsActivity::class.java)
                        .putExtra(RouteSettingsActivity.EXTRA_DASHBOARD_ROUTE_DRAFT, draft.toString())) },
                    onBrowserAuth = { request -> browserAuth.launch(Intent(requireContext(), DashboardBrowserAuthActivity::class.java)
                        .putExtra("request", request.toString())) })
            }
        }
    }

    private fun updateSession() {
        if (visible && serviceState.connected) model.start()
        else model.stop(stopped = !serviceState.connected)
    }
    override fun onStart() { super.onStart(); visible = true; updateSession() }
    override fun onStop() { visible = false; model.stop(stopped = false); super.onStop() }
    override fun onDestroy() { connection.disconnect(requireContext()); super.onDestroy() }
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) { serviceState = state; updateSession() }
    override fun onServiceConnected(service: ISagerNetService) {
        serviceState = BaseService.State.entries.getOrElse(service.state) { BaseService.State.Stopped }
        updateSession()
    }
    override fun onServiceDisconnected() { serviceState = BaseService.State.Stopped; model.stop() }
    override fun onBinderDied() { onServiceDisconnected() }
    override fun onBackPressed(): Boolean {
        if (model.selectedId == null) return false
        model.selectedId = null
        return true
    }
}
