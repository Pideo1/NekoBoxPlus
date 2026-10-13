package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore

internal object NetworkSharingPreferences {
    fun load() = NetworkSharingSettings(
        mode = when (DataStore.serviceMode) {
            Key.MODE_TPROXY -> SharingMode.TPROXY
            Key.MODE_PROXY -> SharingMode.PROXY
            else -> SharingMode.VPN
        },
        allowAccess = DataStore.allowAccess,
        requireProxy = DataStore.requireProxyInVPN,
        mixedListener = DataStore.mixedListener,
        tproxyTethering = DataStore.tproxyTethering,
    )

    fun save(enabled: Boolean) {
        val changed = NetworkSharingPolicy.change(load(), enabled)
        if (changed.mode == SharingMode.TPROXY) DataStore.tproxyTethering = changed.tproxyTethering
        else {
            DataStore.allowAccess = changed.allowAccess
            DataStore.requireProxyInVPN = changed.requireProxy
            DataStore.mixedListener = changed.mixedListener
        }
    }
}
