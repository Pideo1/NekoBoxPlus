package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.HotspotKind
import io.nekohasekai.sagernet.utils.InterfaceKind
import io.nekohasekai.sagernet.utils.NetworkInterfaceInfo
import io.nekohasekai.sagernet.utils.NetworkInterfacesPolicy
import io.nekohasekai.sagernet.utils.NetworkInterfacesSnapshot
import io.nekohasekai.sagernet.utils.SharingMode
import io.nekohasekai.sagernet.utils.SharingWarning
import io.nekohasekai.sagernet.utils.ProxyListenerInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkInterfacesScreen(
    snapshot: NetworkInterfacesSnapshot,
    listeners: List<ProxyListenerInfo>,
    loading: Boolean,
    connected: Boolean,
    localNetworkAllowed: Boolean,
    mode: SharingMode,
    sharingChecked: Boolean,
    warnings: List<SharingWarning>,
    sharingEnabled: Boolean,
    sharingBusy: Boolean,
    @StringRes status: Int?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSharingChanged: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network_interfaces_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.tvFocusTarget()) {
                        Icon(painterResource(R.drawable.baseline_arrow_back_24), stringResource(R.string.mal_close))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, modifier = Modifier.tvFocusTarget()) {
                        Icon(painterResource(R.drawable.ic_baseline_refresh_24), stringResource(R.string.network_interfaces_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.network_interfaces_sharing_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (mode == SharingMode.TPROXY) R.string.network_interfaces_tproxy_instructions
                            else R.string.network_interfaces_sharing_instructions), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(if (mode == SharingMode.TPROXY) R.string.network_interfaces_tproxy_reachability
                            else R.string.network_interfaces_reachability), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = sharingChecked, enabled = sharingEnabled, role = Role.Switch,
                                onValueChange = onSharingChanged,
                            ).tvFocusTarget().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_hardware_router), contentDescription = null)
                            Text(stringResource(R.string.network_interfaces_enable_sharing), Modifier.weight(1f))
                            Switch(checked = sharingChecked, onCheckedChange = null, enabled = sharingEnabled)
                        }
                        if (sharingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                        if (!connected && !sharingBusy) Text(stringResource(R.string.network_interfaces_disconnected))
                        else if (connected && mode != SharingMode.TPROXY && listeners.isEmpty() && !sharingBusy) Text(stringResource(R.string.network_interfaces_no_proxy))
                    }
                }
            }
            if (warnings.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(R.drawable.ic_baseline_warning_24), contentDescription = null)
                            Text(stringResource(R.string.network_interfaces_warning), style = MaterialTheme.typography.titleMedium)
                        }
                        warnings.forEach { warning ->
                            Text(stringResource(when (warning) {
                                SharingWarning.LOCAL_PROXY_DISABLED -> R.string.network_interfaces_warning_local_proxy
                                SharingWarning.LAN_DISABLED -> R.string.network_interfaces_warning_lan
                                SharingWarning.PERMISSION -> R.string.network_interfaces_permission_needed
                                SharingWarning.TPROXY_DISABLED -> R.string.network_interfaces_warning_tproxy
                                SharingWarning.IPV4_DISABLED -> R.string.network_interfaces_warning_ipv4
                                SharingWarning.CUSTOM_CONFIG -> if (mode == SharingMode.TPROXY) R.string.network_interfaces_custom_tproxy
                                    else R.string.network_interfaces_custom_config
                                SharingWarning.NOT_APPLIED -> R.string.network_interfaces_warning_not_applied
                            }), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (snapshot.partial) item {
                Text(stringResource(R.string.network_interfaces_partial), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!loading && snapshot.interfaces.isEmpty()) item { Text(stringResource(R.string.network_interfaces_empty)) }
            items(snapshot.interfaces, key = { it.name }) { info -> InterfaceCard(info, listeners, localNetworkAllowed, onCopy) }
        }
    }
}

@Composable
private fun InterfaceCard(info: NetworkInterfaceInfo, listeners: List<ProxyListenerInfo>, localNetworkAllowed: Boolean, onCopy: (String) -> Unit) {
    val unavailable = stringResource(R.string.network_interfaces_unavailable)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_baseline_link_24), contentDescription = null)
                Text(info.name, style = MaterialTheme.typography.titleLarge)
            }
            if (info.hotspot != HotspotKind.NONE) {
                Text(stringResource(if (info.hotspot == HotspotKind.CONFIRMED) R.string.network_interfaces_tethered
                    else R.string.network_interfaces_possible_hotspot), color = MaterialTheme.colorScheme.primary)
            }
            Detail(R.string.network_interfaces_type, stringResource(when (info.kind) {
                InterfaceKind.WIFI -> R.string.network_interfaces_wifi
                InterfaceKind.ETHERNET -> R.string.network_interfaces_ethernet
                InterfaceKind.CELLULAR -> R.string.network_interfaces_cellular
                InterfaceKind.VPN -> R.string.network_interfaces_vpn
                InterfaceKind.OTHER -> R.string.network_interfaces_other
            }))
            Detail(R.string.network_interfaces_status, when (info.up) {
                true -> stringResource(R.string.network_interfaces_up)
                false -> stringResource(R.string.network_interfaces_down)
                null -> unavailable
            })
            Detail(R.string.network_interfaces_index, info.index?.toString() ?: unavailable)
            Detail(R.string.network_interfaces_mtu, info.mtu?.toString() ?: unavailable)
            val flags = buildList {
                if (info.loopback) add(stringResource(R.string.network_interfaces_loopback))
                if (info.pointToPoint == true) add(stringResource(R.string.network_interfaces_point_to_point))
                if (info.multicast == true) add(stringResource(R.string.network_interfaces_multicast))
                if (info.virtual == true) add(stringResource(R.string.network_interfaces_virtual))
                if (info.pointToPoint == null || info.multicast == null || info.virtual == null) add(unavailable)
            }.joinToString(" · ").ifBlank { stringResource(R.string.network_interfaces_none) }
            Detail(R.string.network_interfaces_flags, flags)
            Detail(R.string.network_interfaces_mac, info.mac ?: unavailable)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.network_interfaces_addresses), style = MaterialTheme.typography.titleMedium)
            if (info.addresses.isEmpty()) Text(unavailable)
            info.addresses.forEach { ip ->
                CopyValue(
                    label = stringResource(if (ip.ipv6) R.string.network_interfaces_ipv6 else R.string.network_interfaces_ipv4),
                    display = ip.address + (ip.prefixLength?.let { "/$it" } ?: ""),
                    value = ip.address,
                    onCopy = onCopy,
                )
                ip.netmask?.let { CopyValue(stringResource(R.string.network_interfaces_netmask), it, it, onCopy) }
                ip.broadcast?.let { CopyValue(stringResource(R.string.network_interfaces_broadcast), it, it, onCopy) }
                listeners.filter { NetworkInterfacesPolicy.matches(it, info, ip) &&
                    (localNetworkAllowed || NetworkInterfacesPolicy.isLoopback(ip.address)) }.forEach { listener ->
                    val endpoint = NetworkInterfacesPolicy.endpoint(ip.address, listener.port)
                    val protocol = stringResource(when {
                        listener.tls && listener.type == "mixed" -> R.string.network_interfaces_mixed_tls_proxy
                        listener.tls -> R.string.network_interfaces_https_proxy
                        listener.type == "mixed" -> R.string.network_interfaces_mixed_proxy
                        else -> R.string.network_interfaces_http_proxy
                    })
                    CopyValue(protocol + listener.tag.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(), endpoint, endpoint, onCopy)
                    Text(stringResource(when {
                        ip.ipv6 && NetworkInterfacesPolicy.isLinkLocal(ip.address) -> R.string.network_interfaces_ipv6_scope
                        NetworkInterfacesPolicy.canShare(info, ip) -> R.string.network_interfaces_sharing_address
                        else -> R.string.network_interfaces_local_endpoint
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (listener.authentication) Text(stringResource(R.string.network_interfaces_authentication),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.network_interfaces_routes), style = MaterialTheme.typography.titleMedium)
            if (info.routes.isEmpty()) Text(unavailable)
            info.routes.forEach { route ->
                CopyValue(stringResource(R.string.network_interfaces_destination),
                    route.destination.address + (route.destination.prefixLength?.let { "/$it" } ?: ""),
                    route.destination.address, onCopy)
                route.gateway?.let { CopyValue(stringResource(R.string.network_interfaces_gateway), it, it, onCopy) }
            }
            Text(stringResource(R.string.network_interfaces_dns), style = MaterialTheme.typography.titleMedium)
            if (info.dns.isEmpty()) Text(unavailable)
            info.dns.forEach { CopyValue(stringResource(R.string.network_interfaces_dns), it, it, onCopy) }
        }
    }
}

@Composable
private fun Detail(@StringRes label: Int, value: String) {
    Text(stringResource(R.string.network_interfaces_detail, stringResource(label), value),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CopyValue(label: String, display: String, value: String, onCopy: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(display, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = { onCopy(value) }, modifier = Modifier.tvFocusTarget()) {
            Icon(painterResource(R.drawable.ic_baseline_content_copy_24),
                stringResource(R.string.network_interfaces_copy_value, label, value))
        }
    }
}
