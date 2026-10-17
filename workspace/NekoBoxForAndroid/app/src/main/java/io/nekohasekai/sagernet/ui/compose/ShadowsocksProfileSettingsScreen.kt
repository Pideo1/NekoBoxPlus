package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.shadowsocks.shadowsocksTransports

@Composable
internal fun ShadowsocksProfileSettingsScreen() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val methods = stringArrayResource(R.array.ss_enc_method_value).toList()
    val plugins = stringArrayResource(R.array.box_shadowsocks_plugins).toList()
    val muxTypeLabels = stringArrayResource(R.array.mux_type).toList()
    val muxTypeValues = stringArrayResource(R.array.int_array_4).toList()
    val muxModeLabels = stringArrayResource(R.array.mux_mode).toList()
    val muxModeValues = stringArrayResource(R.array.int_array_2).toList()
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val methodTitle = stringResource(R.string.enc_method)
    val passwordTitle = stringResource(R.string.password)
    val pluginTitle = stringResource(R.string.plugin)
    val pluginConfigTitle = stringResource(R.string.plugin_configure)
    val muxTypeTitle = stringResource(R.string.mux_type)
    val muxModeTitle = stringResource(R.string.mux_mode)
    val muxConcurrencyTitle = stringResource(R.string.mux_concurrency)
    val muxMaxConnectionsTitle = stringResource(R.string.mux_max_connections)
    val muxMinStreamsTitle = stringResource(R.string.mux_min_streams)
    val brutalUpTitle = stringResource(R.string.mux_brutal_up_mbps)
    val brutalDownTitle = stringResource(R.string.mux_brutal_down_mbps)
    val allNetworkLabels = stringArrayResource(R.array.networks_entry).toList()
    val allNetworkValues = stringArrayResource(R.array.networks_value).toList()
    val networkIndexes = allNetworkValues.indices.filter { allNetworkValues[it] in shadowsocksTransports }
    val networkLabels = networkIndexes.map { allNetworkLabels[it] }
    val networkValues = networkIndexes.map { allNetworkValues[it] }
    val allSecurityLabels = stringArrayResource(R.array.transport_layer_encryption_entry).toList()
    val allSecurityValues = stringArrayResource(R.array.transport_layer_encryption_value).toList()
    val securityIndexes = allSecurityValues.indices.filter { allSecurityValues[it] != "reality" }
    val securityLabels = securityIndexes.map { allSecurityLabels[it] }
    val securityValues = securityIndexes.map { allSecurityValues[it] }
    val fingerprintLabels = stringArrayResource(R.array.utls_fingerprint_entry).toList()
    val fingerprintValues = stringArrayResource(R.array.utls_fingerprint_value).toList()
    val xhttpModeLabels = stringArrayResource(R.array.xhttp_mode_entry).toList()
    val xhttpModeValues = stringArrayResource(R.array.xhttp_mode_value).toList()
    val networkTitle = stringResource(R.string.network)
    val securityTitle = stringResource(R.string.security)
    val sniTitle = stringResource(R.string.sni)
    val alpnTitle = stringResource(R.string.alpn)
    val certificatesTitle = stringResource(R.string.certificates)
    val fingerprintTitle = stringResource(R.string.utls_fingerprint)
    val xhttpModeTitle = stringResource(R.string.xhttp_mode)
    val xhttpExtraTitle = stringResource(R.string.xhttp_extra)

    var name by remember { mutableStateOf(store.getString("name").orEmpty()) }
    var address by remember { mutableStateOf(store.getString("serverAddress").orEmpty()) }
    var port by remember { mutableStateOf(store.getString("serverPort").orEmpty()) }
    var method by remember { mutableStateOf(store.getString("method").orEmpty()) }
    var password by remember { mutableStateOf(store.getString("password").orEmpty()) }
    var pluginName by remember { mutableStateOf(store.getString("pluginName").orEmpty()) }
    var pluginConfig by remember { mutableStateOf(store.getString("pluginConfig").orEmpty()) }
    var udpOverTcp by remember { mutableStateOf(store.getBoolean("sUoT", false)) }
    var enableMux by remember { mutableStateOf(store.getBoolean("enableMux", false)) }
    var muxType by remember { mutableIntStateOf(store.getString("muxType")?.toIntOrNull() ?: 0) }
    var muxMode by remember { mutableIntStateOf(store.getString("muxMode")?.toIntOrNull() ?: 0) }
    var muxConcurrency by remember { mutableStateOf(store.getString("muxConcurrency") ?: "8") }
    var muxMaxConnections by remember { mutableStateOf(store.getString("muxMaxConnections") ?: "4") }
    var muxMinStreams by remember { mutableStateOf(store.getString("muxMinStreams") ?: "4") }
    var muxPadding by remember { mutableStateOf(store.getBoolean("muxPadding", false)) }
    var muxBrutal by remember { mutableStateOf(store.getBoolean("muxBrutal", false)) }
    var muxBrutalUp by remember { mutableStateOf(store.getString("muxBrutalUpMbps") ?: "100") }
    var muxBrutalDown by remember { mutableStateOf(store.getString("muxBrutalDownMbps") ?: "100") }
    var network by remember {
        mutableStateOf(store.getString("type")?.takeIf { it.isNotBlank() } ?: "tcp")
    }
    var host by remember { mutableStateOf(store.getString("host").orEmpty()) }
    var path by remember { mutableStateOf(store.getString("path").orEmpty()) }
    var security by remember {
        mutableStateOf(store.getString("security")?.takeIf { it.isNotBlank() } ?: "none")
    }
    var sni by remember { mutableStateOf(store.getString("sni").orEmpty()) }
    var alpn by remember { mutableStateOf(store.getString("alpn").orEmpty()) }
    var certificates by remember { mutableStateOf(store.getString("certificates").orEmpty()) }
    var allowInsecure by remember { mutableStateOf(store.getBoolean("allowInsecure", false)) }
    var fingerprint by remember { mutableStateOf(store.getString("utlsFingerprint").orEmpty()) }
    var xhttpMode by remember {
        mutableStateOf(store.getString("xhttpMode")?.takeIf { it.isNotBlank() } ?: "auto")
    }
    var xhttpExtra by remember { mutableStateOf(store.getString("xhttpExtra").orEmpty()) }

    fun edit(
        title: String,
        value: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        secret: Boolean = false,
        update: (String) -> Unit,
    ) = context.showComposeTextInputDialog(
        title = title,
        initialValue = value,
        keyboardType = keyboardType,
        maxLength = maxLength,
        password = secret,
        onPositive = update,
    )
    fun choose(title: String, labels: List<String>, selected: Int, update: (Int) -> Unit) =
        context.showComposeSingleChoiceDialog(
            title = title,
            items = labels,
            selectedIndex = selected.coerceIn(labels.indices),
            negativeButton = cancel,
            onItemSelected = update,
        )
    fun summary(value: String) = value.ifBlank { notSet }
    fun secretSummary(value: String) = value.takeIf(String::isNotBlank)
        ?.let { "\u2022".repeat(it.length) } ?: notSet

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name, summary(name)) {
                edit(nameTitle, name) { name = it; store.putString("name", it) }
            }
        }
        item { ProfileCategory(R.string.proxy_cat) }
        item {
            ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address, summary(address)) {
                edit(addressTitle, address) { address = it; store.putString("serverAddress", it) }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port, summary(port)) {
                edit(portTitle, port, KeyboardType.Number, 5) {
                    port = it; store.putString("serverPort", it)
                }
            }
        }
        item {
            val selected = methods.indexOf(method).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_notification_enhanced_encryption, R.string.enc_method,
                methods.getOrElse(selected) { summary(method) }) {
                choose(methodTitle, methods, selected) {
                    method = methods[it]; store.putString("method", method)
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_settings_password, R.string.password,
                secretSummary(password)) {
                edit(passwordTitle, password, secret = true) {
                    password = it; store.putString("password", it)
                }
            }
        }
        item { ProfileCategory(R.string.network) }
        item {
            val selected = networkValues.indexOf(network).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24, R.string.network,
                networkLabels.getOrElse(selected) { summary(network) }) {
                choose(networkTitle, networkLabels, selected) {
                    network = networkValues[it]; store.putString("type", network)
                }
            }
        }
        if (network == "ws" || network == "httpupgrade" || network == "xhttp") {
            item {
                val hostRes = when (network) {
                    "ws" -> R.string.ws_host
                    "httpupgrade" -> R.string.http_upgrade_host
                    else -> R.string.xhttp_host
                }
                val hostTitle = stringResource(hostRes)
                ProfileActionRow(R.drawable.ic_baseline_airplanemode_active_24, hostRes, summary(host)) {
                    edit(hostTitle, host) { host = it; store.putString("host", it) }
                }
            }
        }
        if (network != "tcp") {
            item {
                val pathRes = when (network) {
                    "ws" -> R.string.ws_path
                    "grpc" -> R.string.grpc_service_name
                    "httpupgrade" -> R.string.http_upgrade_path
                    else -> R.string.xhttp_path
                }
                val pathTitle = stringResource(pathRes)
                ProfileActionRow(R.drawable.ic_baseline_format_align_left_24, pathRes, summary(path)) {
                    edit(pathTitle, path) { path = it; store.putString("path", it) }
                }
            }
        }
        if (network == "xhttp") {
            item { ProfileCategory(R.string.xhttp_settings) }
            item {
                val selected = xhttpModeValues.indexOf(xhttpMode).coerceAtLeast(0)
                ProfileActionRow(R.drawable.ic_baseline_stream_24, R.string.xhttp_mode,
                    xhttpModeLabels.getOrElse(selected) { summary(xhttpMode) }) {
                    choose(xhttpModeTitle, xhttpModeLabels, selected) {
                        xhttpMode = xhttpModeValues[it]; store.putString("xhttpMode", xhttpMode)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_action_settings, R.string.xhttp_extra,
                    summary(xhttpExtra)) {
                    edit(xhttpExtraTitle, xhttpExtra) {
                        xhttpExtra = it; store.putString("xhttpExtra", it)
                    }
                }
            }
        }
        item {
            val selected = securityValues.indexOf(security).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_layers_24, R.string.security,
                securityLabels.getOrElse(selected) { summary(security) }) {
                choose(securityTitle, securityLabels, selected) {
                    security = securityValues[it]; store.putString("security", security)
                }
            }
        }
        if (security == "tls") {
            item { ProfileCategory(R.string.security_settings) }
            item {
                ProfileActionRow(R.drawable.ic_action_copyright, R.string.sni, summary(sni)) {
                    edit(sniTitle, sni) { sni = it; store.putString("sni", it) }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_legend_toggle_24, R.string.alpn, summary(alpn)) {
                    edit(alpnTitle, alpn) { alpn = it; store.putString("alpn", it) }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_vpn_key_24, R.string.certificates,
                    summary(certificates)) {
                    edit(certificatesTitle, certificates) {
                        certificates = it; store.putString("certificates", it)
                    }
                }
            }
            item {
                ProfileSwitchRow(R.drawable.ic_notification_enhanced_encryption, R.string.allow_insecure,
                    allowInsecure, stringResource(R.string.allow_insecure_sum),
                    dynamicSummary = false) {
                    allowInsecure = it; store.putBoolean("allowInsecure", it)
                }
            }
            item { ProfileCategory(R.string.tls_camouflage_settings) }
            item {
                val selected = fingerprintValues.indexOf(fingerprint).coerceAtLeast(0)
                ProfileActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.utls_fingerprint,
                    summary(fingerprintLabels.getOrElse(selected) { fingerprint })) {
                    choose(fingerprintTitle, fingerprintLabels, selected) {
                        fingerprint = fingerprintValues[it]
                        store.putString("utlsFingerprint", fingerprint)
                    }
                }
            }
        }
        item { ProfileCategory(R.string.plugin) }
        item {
            val selected = plugins.indexOf(pluginName).coerceAtLeast(0)
            ProfileActionRow(R.drawable.baseline_construction_24, R.string.plugin,
                plugins.getOrElse(selected) { summary(pluginName) }) {
                choose(pluginTitle, plugins, selected) {
                    pluginName = plugins[it]; store.putString("pluginName", pluginName)
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_action_settings, R.string.plugin_configure,
                summary(pluginConfig)) {
                edit(pluginConfigTitle, pluginConfig) {
                    pluginConfig = it; store.putString("pluginConfig", it)
                }
            }
        }
        item { ProfileCategory(R.string.sing_box_server) }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_compare_arrows_24, R.string.udp_over_tcp,
                udpOverTcp) {
                udpOverTcp = it; store.putBoolean("sUoT", it)
            }
        }
        item { ProfileCategory(R.string.mux_preference) }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_compare_arrows_24, R.string.enable_mux,
                enableMux, stringResource(R.string.mux_sum), dynamicSummary = false) {
                enableMux = it; store.putBoolean("enableMux", it)
            }
        }
        item {
            val selected = muxTypeValues.indexOf(muxType.toString()).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_stream_24, R.string.mux_type,
                muxTypeLabels.getOrElse(selected) { muxType.toString() }) {
                choose(muxTypeTitle, muxTypeLabels, selected) {
                    muxType = muxTypeValues[it].toIntOrNull() ?: 0
                    store.putString("muxType", muxType.toString())
                }
            }
        }
        item {
            val selected = muxModeValues.indexOf(muxMode.toString()).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_tune_24, R.string.mux_mode,
                muxModeLabels.getOrElse(selected) { muxMode.toString() }) {
                choose(muxModeTitle, muxModeLabels, selected) {
                    muxMode = muxModeValues[it].toIntOrNull() ?: 0
                    store.putString("muxMode", muxMode.toString())
                }
            }
        }
        if (muxMode == 0) {
            item {
                ProfileActionRow(R.drawable.ic_baseline_low_priority_24, R.string.mux_concurrency,
                    summary(muxConcurrency)) {
                    edit(muxConcurrencyTitle, muxConcurrency, KeyboardType.Number) {
                        muxConcurrency = it; store.putString("muxConcurrency", it)
                    }
                }
            }
        } else {
            item {
                ProfileActionRow(R.drawable.ic_baseline_low_priority_24,
                    R.string.mux_max_connections, summary(muxMaxConnections)) {
                    edit(muxMaxConnectionsTitle, muxMaxConnections, KeyboardType.Number) {
                        muxMaxConnections = it; store.putString("muxMaxConnections", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_low_priority_24, R.string.mux_min_streams,
                    summary(muxMinStreams)) {
                    edit(muxMinStreamsTitle, muxMinStreams, KeyboardType.Number) {
                        muxMinStreams = it; store.putString("muxMinStreams", it)
                    }
                }
            }
        }
        item {
            ProfileSwitchRow(R.drawable.baseline_developer_board_24, R.string.padding, muxPadding) {
                muxPadding = it; store.putBoolean("muxPadding", it)
            }
        }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_speed_24, R.string.mux_brutal, muxBrutal) {
                muxBrutal = it; store.putBoolean("muxBrutal", it)
            }
        }
        if (muxBrutal) {
            item {
                ProfileActionRow(R.drawable.ic_baseline_upload_24, R.string.mux_brutal_up_mbps,
                    summary(muxBrutalUp)) {
                    edit(brutalUpTitle, muxBrutalUp, KeyboardType.Number) {
                        muxBrutalUp = it; store.putString("muxBrutalUpMbps", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_download_24, R.string.mux_brutal_down_mbps,
                    summary(muxBrutalDown)) {
                    edit(brutalDownTitle, muxBrutalDown, KeyboardType.Number) {
                        muxBrutalDown = it; store.putString("muxBrutalDownMbps", it)
                    }
                }
            }
        }
        if (security == "tls") item { SharedTlsOptions() }
        item { SharedDialOptions() }
    }
}
