package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.ui.compose.ShadowsocksProfileSettingsScreen
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class ShadowsocksSettingsActivity : ProfileSettingsActivity<ShadowsocksBean>() {

    override val usesComposePreferences = true

    override fun createEntity() = ShadowsocksBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val method = pbm.add(PreferenceBinding(Type.Text, "method"))
    private val pluginName =
        pbm.add(PreferenceBinding(Type.Text, "pluginName").apply { disable = true })
    private val pluginConfig =
        pbm.add(PreferenceBinding(Type.Text, "pluginConfig").apply { disable = true })
    private val sUoT = pbm.add(PreferenceBinding(Type.Bool, "sUoT"))
    private val type = pbm.add(PreferenceBinding(Type.Text, "type"))
    private val host = pbm.add(PreferenceBinding(Type.Text, "host"))
    private val path = pbm.add(PreferenceBinding(Type.Text, "path"))
    private val security = pbm.add(PreferenceBinding(Type.Text, "security"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val xhttpMode = pbm.add(PreferenceBinding(Type.Text, "xhttpMode"))
    private val xhttpExtra = pbm.add(PreferenceBinding(Type.Text, "xhttpExtra"))
    private val enableMux = pbm.add(PreferenceBinding(Type.Bool, "enableMux"))
    private val muxType = pbm.add(PreferenceBinding(Type.TextToInt, "muxType"))
    private val muxConcurrency = pbm.add(PreferenceBinding(Type.TextToInt, "muxConcurrency"))
    private val muxMode = pbm.add(PreferenceBinding(Type.TextToInt, "muxMode"))
    private val muxMaxConnections = pbm.add(PreferenceBinding(Type.TextToInt, "muxMaxConnections"))
    private val muxMinStreams = pbm.add(PreferenceBinding(Type.TextToInt, "muxMinStreams"))
    private val muxPadding = pbm.add(PreferenceBinding(Type.Bool, "muxPadding"))
    private val muxBrutal = pbm.add(PreferenceBinding(Type.Bool, "muxBrutal"))
    private val muxBrutalUpMbps = pbm.add(PreferenceBinding(Type.TextToInt, "muxBrutalUpMbps"))
    private val muxBrutalDownMbps = pbm.add(PreferenceBinding(Type.TextToInt, "muxBrutalDownMbps"))

    override fun ShadowsocksBean.init() {
        pbm.writeToCacheAll(this)

        DataStore.profileCacheStore.putString("pluginName", plugin.substringBefore(";"))
        DataStore.profileCacheStore.putString("pluginConfig", plugin.substringAfter(";"))
    }

    override fun ShadowsocksBean.serialize() {
        pbm.fromCacheAll(this)

        val pn = pluginName.readStringFromCache()
        val pc = pluginConfig.readStringFromCache()
        plugin = if (pn.isNotBlank()) "$pn;$pc" else ""
    }

    @Composable
    override fun ComposePreferences() = ShadowsocksProfileSettingsScreen()

}
