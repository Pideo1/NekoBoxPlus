/* SPDX-License-Identifier: GPL-3.0-or-later
 * Local dashboard features adapted from Husi; UI uses NB4A's Material You theme.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.nekohasekai.sagernet.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.text.DateFormat
import java.util.Date

@Composable
fun NativeDashboardScreen(model: DashboardViewModel, onOpenDrawer: () -> Unit,
    onInterfaces: () -> Unit, onApp: (String) -> Unit, onRoute: (JSONObject) -> Unit,
    onBrowserAuth: (JSONObject) -> Unit) {
    val state = model.state
    var showWidgets by rememberSaveable { mutableStateOf(false) }
    var reset by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        if (state.error.isNotBlank()) { snackbar.showSnackbar(state.error); model.clearError() }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.integrated_dashboard)) },
                navigationIcon = { DashIcon(R.drawable.ic_navigation_menu, R.string.navigation_drawer_open, onClick = onOpenDrawer) },
                actions = {
                    if (model.page == 0) DashIcon(R.drawable.ic_image_edit, R.string.dashboard_widgets) { showWidgets = true }
                    if (model.page == 1) {
                        DashIcon(if (model.paused) R.drawable.ic_baseline_play_arrow_24 else R.drawable.ic_baseline_pause_24,
                            if (model.paused) R.string.dashboard_resume else R.string.dashboard_pause) { model.pause() }
                        DashIcon(R.drawable.ic_baseline_refresh_24, R.string.dashboard_reset, enabled = state.available) { reset = true }
                    }
                })
        },
        bottomBar = {
            val tabs = listOf(R.string.dashboard_status, R.string.dashboard_connections, R.string.dashboard_proxies)
            val icons = listOf(R.drawable.ic_baseline_speed_24, R.drawable.ic_baseline_compare_arrows_24, R.drawable.ic_baseline_vpn_key_24)
            PrimaryTabRow(selectedTabIndex = model.page,
                modifier = Modifier.navigationBarsPadding(),
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(model.page, matchContentSize = false).fillMaxHeight().wrapContentHeight(Alignment.Top),
                        width = androidx.compose.ui.unit.Dp.Unspecified,
                    )
                }, divider = {}) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = index == model.page, onClick = { model.page = index }, modifier = Modifier.height(56.dp)) {
                        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(painterResource(icons[index]), contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(stringResource(title), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }, snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.available) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(if (state.stopped) R.string.dashboard_stopped else R.string.dashboard_connecting))
                    if (!state.stopped) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            when (model.page) {
                0 -> StatusPage(model, onInterfaces, onBrowserAuth)
                1 -> ConnectionsPage(model)
                2 -> ProxiesPage(model)
            }
        }
    }
    if (showWidgets) WidgetEditor(model) { showWidgets = false }
    if (reset) AlertDialog(onDismissRequest = { reset = false },
        title = { Text(stringResource(R.string.dashboard_reset)) },
        text = { Text(stringResource(R.string.dashboard_reset_confirm)) },
        confirmButton = { TextButton(onClick = { reset = false; model.resetNetwork() }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text(stringResource(android.R.string.cancel)) } })
    model.selectedId?.let { id ->
        state.connections.find { it.id == id }?.let { connection ->
            ConnectionDetails(connection, state.available, onClose = { model.closeConnection(id) },
                onDismiss = { model.selectedId = null }, onApp = onApp, onRoute = onRoute)
        }
    }
}

@Composable
private fun DashIcon(icon: Int, label: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}
@Composable private fun bytes(value: Long) = Formatter.formatShortFileSize(LocalContext.current, value.coerceAtLeast(0))
@Composable private fun rate(value: Long) = stringResource(R.string.dashboard_rate, bytes(value))
private fun widgetTitle(id: String) = when (id) {
    "proxy" -> R.string.dashboard_proxy_traffic; "direct" -> R.string.dashboard_direct_traffic
    "status" -> R.string.dashboard_status; "mode" -> R.string.clash_mode
    "addresses" -> R.string.dashboard_addresses; "interfaces" -> R.string.dashboard_interfaces
    "openvpn" -> R.string.dashboard_openvpn; else -> R.string.dashboard_openconnect
}

@Composable
private fun StatusPage(model: DashboardViewModel, onInterfaces: () -> Unit, onBrowserAuth: (JSONObject) -> Unit) {
    val state = model.state
    var addresses by remember { mutableStateOf<List<Pair<String, List<String>>>>(emptyList()) }
    LaunchedEffect(state.available) {
        addresses = withContext(Dispatchers.IO) {
            runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .map { it.displayName to it.inetAddresses.toList().mapNotNull { address -> address.hostAddress } } }.getOrDefault(emptyList())
        }
    }
    val widgets = model.widgets.filter { (id, enabled) -> enabled && when (id) {
        "mode" -> state.modes.isNotEmpty(); "openvpn" -> state.openVPN.isNotEmpty()
        "openconnect" -> state.openConnect.isNotEmpty(); else -> true
    } }
    LazyVerticalGrid(columns = GridCells.Adaptive(260.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(widgets, key = { it.first }, span = { if (it.first in listOf("interfaces", "openvpn", "openconnect")) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { (id, _) ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(widgetTitle(id)), style = MaterialTheme.typography.titleMedium)
                    when (id) {
                        "proxy", "direct" -> {
                            val proxy = id == "proxy"
                            Text(stringResource(R.string.dashboard_transfer, rate(if (proxy) state.proxyUp else state.directUp), rate(if (proxy) state.proxyDown else state.directDown)))
                            SpeedGraph(if (proxy) state.proxyHistory else state.directHistory)
                        }
                        "status" -> {
                            Text(stringResource(R.string.dashboard_memory, bytes(state.memory)))
                            Text(stringResource(R.string.dashboard_goroutines, state.goroutines))
                            Text(stringResource(R.string.dashboard_count, state.activeCount))
                            Text(stringResource(R.string.dashboard_transfer, bytes(state.upload), bytes(state.download)))
                        }
                        "mode" -> state.modes.forEach { mode ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = state.available) { model.setMode(mode) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = mode == state.mode, onClick = { model.setMode(mode) }, enabled = state.available)
                                Text(mode)
                            }
                        }
                        "addresses", "interfaces" -> {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    addresses.forEachIndexed { index, (name, ips) ->
                                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (id == "interfaces") Text(name, style = MaterialTheme.typography.labelLarge)
                                            ips.forEach { Text(it) }
                                        }
                                    }
                                }
                            }
                            if (id == "interfaces") TextButton(onClick = onInterfaces) { Text(stringResource(R.string.dashboard_manage_interfaces)) }
                        }
                        "openvpn" -> EndpointCards(state.openVPN, "OpenVPN", model, onBrowserAuth)
                        "openconnect" -> EndpointCards(state.openConnect, "OpenConnect", model, onBrowserAuth)
                    }
                }
            }
        }
    }
}
@Composable private fun SpeedGraph(history: List<Float>) {
    val color = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        drawLine(guide, Offset(0f, size.height), Offset(size.width, size.height))
        val peak = (history.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val path = Path()
        history.forEachIndexed { index, value ->
            val x = index * size.width / (history.size - 1).coerceAtLeast(1)
            val y = size.height - value / peak * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}
@Composable private fun WidgetEditor(model: DashboardViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.dashboard_widgets)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                model.widgets.forEachIndexed { index, (id, visible) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = visible, onCheckedChange = { enabled -> model.updateWidgets(model.widgets.map { if (it.first == id) id to enabled else it }) })
                        Text(stringResource(widgetTitle(id)), Modifier.weight(1f))
                        IconButton(enabled = index > 0, onClick = {
                            model.updateWidgets(model.widgets.toMutableList().apply { add(index - 1, removeAt(index)) })
                        }) { Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_down_24), stringResource(R.string.dashboard_move_up), Modifier.rotate(180f)) }
                        IconButton(enabled = index < model.widgets.lastIndex, onClick = {
                            model.updateWidgets(model.widgets.toMutableList().apply { add(index + 1, removeAt(index)) })
                        }) { Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_down_24), stringResource(R.string.dashboard_move_down)) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } })
}

private fun sortTitle(sort: ConnectionSort) = when (sort) {
    ConnectionSort.START -> R.string.dashboard_started; ConnectionSort.INBOUND -> R.string.dashboard_inbound
    ConnectionSort.SOURCE -> R.string.dashboard_source; ConnectionSort.DESTINATION -> R.string.dashboard_destination
    ConnectionSort.UPLOAD -> R.string.dashboard_upload; ConnectionSort.DOWNLOAD -> R.string.dashboard_download
    ConnectionSort.UPLOAD_SPEED -> R.string.dashboard_upload_speed; ConnectionSort.DOWNLOAD_SPEED -> R.string.dashboard_download_speed
    ConnectionSort.RULE -> R.string.dashboard_rule
}
@Composable private fun ConnectionsPage(model: DashboardViewModel) {
    var sortMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(model.query, { model.query = it }, Modifier.fillMaxWidth().padding(16.dp, 8.dp), singleLine = true,
            label = { Text(stringResource(R.string.dashboard_search_connections)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_toolbar_search), null) })
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            FilterChip(selected = model.showActive, onClick = { model.showActive = !model.showActive; model.persistFilters() }, label = { Text(stringResource(R.string.dashboard_active)) })
            FilterChip(selected = model.showClosed, onClick = { model.showClosed = !model.showClosed; model.persistFilters() }, label = { Text(stringResource(R.string.dashboard_closed)) })
            Box {
                TextButton(onClick = { sortMenu = true }) {
                    Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_down_24),
                        contentDescription = stringResource(if (model.descending) R.string.dashboard_descending else R.string.dashboard_ascending),
                        modifier = Modifier.size(18.dp).rotate(if (model.descending) 0f else 180f))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.dashboard_sort_current, stringResource(sortTitle(model.sort))))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    ConnectionSort.entries.forEach { mode -> DropdownMenuItem(text = { Text(stringResource(sortTitle(mode))) },
                        leadingIcon = { RadioButton(selected = mode == model.sort, onClick = null) }, onClick = {
                        model.sort = mode; model.persistFilters(); sortMenu = false
                    }) }
                    DropdownMenuItem(text = { Text(stringResource(if (model.descending) R.string.dashboard_ascending else R.string.dashboard_descending)) }, onClick = {
                        model.descending = !model.descending; model.persistFilters(); sortMenu = false
                    })
                }
            }
        }
        val connections = remember(model.state.connections, model.query, model.showActive, model.showClosed, model.sort, model.descending) {
            visibleConnections(model.state.connections, model.query, model.showActive, model.showClosed, model.sort, model.descending)
        }
        if (connections.isEmpty()) Text(stringResource(when {
            !model.showActive && !model.showClosed -> R.string.dashboard_no_filters
            model.query.isNotBlank() -> R.string.dashboard_no_matches
            else -> R.string.dashboard_no_connections
        }), Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(connections, key = { it.id }) { connection ->
                OutlinedCard(onClick = { model.selectedId = connection.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(connection.domain.ifBlank { connection.destination }, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(connection.network.uppercase(), connection.inbound, connection.outbound).filter { it.isNotBlank() }.joinToString(" · "))
                        Text(stringResource(R.string.dashboard_transfer, rate(connection.uploadRate), rate(connection.downloadRate)))
                        Text(stringResource(R.string.dashboard_transfer, bytes(connection.upload), bytes(connection.download)), style = MaterialTheme.typography.bodySmall)
                        if (connection.closed) Text(stringResource(R.string.dashboard_closed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun ProxiesPage(model: DashboardViewModel) {
    var groupNames by rememberSaveable { mutableStateOf(false) }
    val state = model.state
    val all = DashboardGroup("", "", "", false, true, state.outbounds)
    val groups = (state.groups + all).filter { !groupNames || it.tag.contains(model.proxyQuery, true) || it.tag.isEmpty() }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(model.proxyQuery, { model.proxyQuery = it }, Modifier.fillMaxWidth().padding(16.dp, 8.dp), singleLine = true,
            label = { Text(stringResource(R.string.dashboard_search_proxies)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_toolbar_search), null) })
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = groupNames, onClick = { groupNames = !groupNames }, label = { Text(stringResource(R.string.dashboard_group_names)) })
            Spacer(Modifier.weight(1f))
            var orderMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { orderMenu = true }) { Text(stringResource(R.string.dashboard_sort)) }
                DropdownMenu(orderMenu, { orderMenu = false }) {
                    listOf(R.string.dashboard_original_order, R.string.dashboard_name_order, R.string.dashboard_delay_order).forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { model.proxyOrder = index; model.persistFilters(); orderMenu = false })
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                item(key = "header:${group.tag}") {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (group.tag.isEmpty()) stringResource(R.string.dashboard_all_proxies) else group.tag, style = MaterialTheme.typography.titleMedium)
                                    if (group.type.isNotBlank()) Text(group.type, style = MaterialTheme.typography.bodySmall)
                                }
                                DashIcon(R.drawable.ic_toolbar_search, R.string.dashboard_search_proxies, onClick = {
                                    model.searchedGroup = if (model.searchedGroup == group.tag) null else group.tag
                                    model.groupQuery = ""
                                })
                                DashIcon(R.drawable.ic_baseline_speed_24, R.string.dashboard_test,
                                    onClick = { model.test(group.items, group.tag) }, enabled = state.available && group.tag !in state.progress)
                                if (group.tag.isNotEmpty()) DashIcon(R.drawable.ic_baseline_keyboard_arrow_down_24, R.string.dashboard_expand,
                                    onClick = { model.expand(group.tag, !group.expanded) }, enabled = state.available)
                            }
                            state.progress[group.tag]?.let { (done, total) ->
                                Text(stringResource(R.string.dashboard_test_progress, done, total))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            if (model.searchedGroup == group.tag) OutlinedTextField(model.groupQuery, { model.groupQuery = it },
                                Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.dashboard_search_proxies)) }, singleLine = true)
                        }
                    }
                }
                if (group.expanded || group.tag.isEmpty() || model.proxyQuery.isNotBlank() || model.searchedGroup == group.tag) {
                    val filtered = group.items.filter { proxy ->
                        (groupNames || listOf(proxy.tag, proxy.type).any { it.contains(model.proxyQuery, true) }) &&
                            (model.searchedGroup != group.tag || proxy.tag.contains(model.groupQuery, true))
                    }
                    val proxies = when (model.proxyOrder) {
                        1 -> filtered.sortedBy { it.tag.lowercase() }
                        2 -> filtered.sortedBy { if (it.delay < 0) Int.MAX_VALUE else it.delay }
                        else -> filtered
                    }
                    // Individual lazy rows keep large subscriptions responsive.
                    items(proxies, key = { "proxy:${group.tag.length}:${group.tag}:${it.tag}" }) { proxy ->
                        Row(Modifier.fillMaxWidth().testTag("dashboard-proxy:${group.tag}:${proxy.tag}")
                            .clickable(enabled = group.selectable && state.available) {
                            model.select(group.tag, proxy.tag)
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (group.selectable) RadioButton(proxy.tag == group.selected, onClick = { model.select(group.tag, proxy.tag) }, enabled = state.available)
                            Column(Modifier.weight(1f)) {
                                Text(proxy.tag, style = MaterialTheme.typography.bodyLarge)
                                Text(proxy.type, style = MaterialTheme.typography.bodySmall)
                                state.testErrors[proxy.tag]?.let { error ->
                                    Text(stringResource(R.string.dashboard_test_failure_detail, error),
                                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (proxy.tag in state.testing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else Text(if (proxy.delay < 0) stringResource(R.string.dashboard_untested) else stringResource(R.string.dashboard_latency, proxy.delay))
                            DashIcon(R.drawable.ic_baseline_speed_24, R.string.dashboard_test,
                                onClick = { model.test(listOf(proxy), "single:${proxy.tag}") }, enabled = state.available && proxy.tag !in state.testing,
                                modifier = Modifier.testTag("dashboard-test:${group.tag}:${proxy.tag}"))
                        }
                    }
                    if (proxies.isEmpty()) item(key = "empty:${group.tag}") { Text(stringResource(R.string.dashboard_no_matches)) }
                }
            }
        }
    }
}

@Composable private fun ConnectionDetails(connection: DashboardConnection, available: Boolean,
    onClose: () -> Unit, onDismiss: () -> Unit, onApp: (String) -> Unit, onRoute: (JSONObject) -> Unit) {
    val context = LocalContext.current
    val date = remember { DateFormat.getDateTimeInstance() }
    val fields = listOf(
        "domain" to (R.string.dashboard_domain to connection.domain),
        "destination" to (R.string.dashboard_destination to connection.destination),
        "source" to (R.string.dashboard_source to connection.source),
        "network" to (R.string.dashboard_network to connection.network),
        "protocol" to (R.string.dashboard_protocol to connection.protocol),
        "packages" to (R.string.dashboard_app to connection.packages.joinToString(", ")),
    )
    val selected = remember(connection.id) { mutableStateMapOf<String, Boolean>() }
    val maximumHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.85f }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight)
            .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(connection.domain.ifBlank { connection.destination }, style = MaterialTheme.typography.titleLarge)
            SelectionContainer { Column {
                fields.filter { it.second.second.isNotBlank() }.forEach { (key, pair) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected[key] == true, onCheckedChange = { selected[key] = it })
                        Column { Text(stringResource(pair.first), style = MaterialTheme.typography.labelMedium); Text(pair.second) }
                    }
                }
                listOf(R.string.dashboard_inbound to connection.inbound, R.string.dashboard_rule to connection.rule,
                    R.string.dashboard_outbound to connection.chain.joinToString(" → "), R.string.dashboard_process to connection.process,
                    R.string.dashboard_uid to connection.uid.takeIf { it >= 0 }?.toString().orEmpty(),
                    R.string.dashboard_started to date.format(Date(connection.createdAt)),
                    R.string.dashboard_closed to if (connection.closed) date.format(Date(connection.closedAt)) else "").filter { it.second.isNotBlank() }
                    .forEach { (label, value) -> Text(stringResource(label), style = MaterialTheme.typography.labelMedium); Text(value) }
                Text(stringResource(R.string.dashboard_transfer, bytes(connection.upload), bytes(connection.download)))
            } }
            Row {
                DashIcon(R.drawable.ic_baseline_content_copy_24, R.string.dashboard_copy, onClick = {
                    val text = fields.filter { it.second.second.isNotBlank() }.joinToString("\n") { (key, pair) -> "$key: ${pair.second}" } +
                        "\n${connection.rule}\n${connection.chain.joinToString(" → ")}" 
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", text))
                })
                connection.packages.firstOrNull()?.let { pkg -> DashIcon(R.drawable.ic_navigation_apps, R.string.dashboard_app_info, onClick = { onApp(pkg) }) }
                TextButton(enabled = selected.any { it.value }, onClick = {
                    val draft = JSONObject()
                    selected.filterValues { it }.keys.forEach { key ->
                        if (key == "packages") draft.put(key, JSONArray(connection.packages))
                        else draft.put(key, fields.first { it.first == key }.second.second)
                    }
                    onDismiss(); onRoute(draft)
                }) { Text(stringResource(R.string.dashboard_create_rule)) }
            }
            if (!connection.closed) OutlinedButton(enabled = available, onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_navigation_close), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dashboard_disconnect))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun EndpointCards(endpoints: List<JSONObject>, kind: String,
    model: DashboardViewModel, onBrowserAuth: (JSONObject) -> Unit) {
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    val context = LocalContext.current
    endpoints.forEach { endpoint ->
        Text(endpoint.optString("endpointTag"), style = MaterialTheme.typography.titleSmall)
        Text(endpoint.optString("stateText").ifBlank { endpoint.optString("state") })
        endpoint.optString("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        endpoint.optJSONObject("tunnelInfo")?.let { info ->
            Text(listOf(info.optString("server"), info.optString("transport"), info.optString("network"),
                info.optJSONArray("ipv4").strings().joinToString(", "), info.optJSONArray("ipv6").strings().joinToString(", "))
                .filter { it.isNotBlank() }.joinToString("\n"))
        }
        val challenge = endpoint.optJSONObject(if (kind == "OpenVPN") "challenge" else "authChallenge")
        challenge?.optString("message")?.takeIf { it.isNotBlank() }?.let { Text(it) }
        if (challenge != null) FilledTonalButton(onClick = {
            if (kind == "OpenVPN" && challenge.optString("kind") == "open-url") {
                val uri = challenge.optString("url").toUri()
                if (uri.scheme in listOf("https", "http")) runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
            } else if (challenge.optJSONObject("browser") != null) onBrowserAuth(JSONObject().put("endpointTag", endpoint.optString("endpointTag"))
                .put("challengeID", challenge.optString("id")).put("browser", challenge.getJSONObject("browser")))
            else selected = endpoint
        }, enabled = model.state.available) { Icon(painterResource(R.drawable.ic_baseline_vpn_key_24), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dashboard_authenticate)) }
    }
    selected?.let { endpoint -> EndpointAuthDialog(endpoint, kind, model) { selected = null } }
}
@Composable private fun EndpointAuthDialog(endpoint: JSONObject, kind: String, model: DashboardViewModel, onDismiss: () -> Unit) {
    val challenge = endpoint.getJSONObject(if (kind == "OpenVPN") "challenge" else "authChallenge")
    val values = remember(challenge.optString("id")) {
        mutableStateMapOf<String, String>().apply { challenge.optString("username").takeIf { it.isNotBlank() }?.let { put("username", it) } }
    }
    val fields = if (kind == "OpenConnect") challenge.optJSONObject("form")?.optJSONArray("fields").objects()
        else when (challenge.optString("kind")) {
            "credentials" -> listOf("username", "password") + if (challenge.optString("secretMessage").isNotBlank()) listOf("secret") else emptyList()
            "secret" -> listOf("secret")
            else -> emptyList()
        }.map { JSONObject().put("submissionKey", it).put("kind", if (it == "username") "text" else "password") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.dashboard_authenticate)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                challenge.optString("previousError").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                challenge.optString("message").takeIf { it.isNotBlank() }?.let { Text(it) }
                challenge.optString("banner").takeIf { it.isNotBlank() }?.let { Text(it) }
                challenge.optString("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                fields.forEach { field ->
                    val key = field.optString("submissionKey")
                    val label = if (kind == "OpenVPN") stringResource(when (key) { "username" -> R.string.dashboard_username; "password" -> R.string.dashboard_password; else -> R.string.dashboard_secret })
                        else field.optString("label").ifBlank { field.optString("name").ifBlank { key } }
                    val options = field.optJSONArray("options").objects()
                    if (options.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        Box { OutlinedButton(onClick = { expanded = true }) { Text(values[key] ?: field.optString("value").ifBlank { label }) }
                            DropdownMenu(expanded, { expanded = false }) { options.forEach { option ->
                                DropdownMenuItem(text = { Text(option.optString("label")) }, onClick = { values[key] = option.optString("value"); expanded = false })
                            } }
                        }
                    } else OutlinedTextField(values[key] ?: field.optString("value"), { values[key] = it }, Modifier.fillMaxWidth(),
                        label = { Text(label) }, singleLine = true,
                        visualTransformation = if (field.optString("kind").contains("password", true) || key == "password" || (key == "secret" && !challenge.optBoolean("echo"))) PasswordVisualTransformation() else VisualTransformation.None)
                }
            }
        }, confirmButton = { TextButton(onClick = {
            val request = JSONObject().put("endpointTag", endpoint.optString("endpointTag")).put("challengeID", challenge.optString("id"))
            val form = JSONObject()
            fields.forEach { field -> val key = field.optString("submissionKey"); form.put(key, values[key] ?: field.optString("value")) }
            if (kind == "OpenConnect") request.put("form", JSONObject().put("values", form))
            else form.keys().forEach { key -> request.put(key, form.getString(key)) }
            model.call(if (kind == "OpenConnect") "SubmitOpenConnectAuthResponse" else "SubmitOpenVPNChallengeResponse", request, onDismiss)
        }) { Text(stringResource(R.string.apply)) } }, dismissButton = {
            TextButton(onClick = {
                model.call(if (kind == "OpenConnect") "CancelOpenConnectAuthChallenge" else "CancelOpenVPNChallenge",
                    JSONObject().put("endpointTag", endpoint.optString("endpointTag")).put("challengeID", challenge.optString("id")), onDismiss)
            }) { Text(stringResource(android.R.string.cancel)) }
        })
}
