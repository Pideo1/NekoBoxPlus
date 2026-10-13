package io.nekohasekai.sagernet.group

import android.os.Build
import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.app
import java.util.Locale
import moe.matsuri.nb4a.utils.HwidGenerator
import org.json.JSONObject

internal data class SubscriptionRequestFingerprint(
    val userAgent: String,
    val headers: Map<String, String>,
)

private const val HAPP_USER_AGENT = "Happ/3.26.3/Android/17839452147361875676"
private const val LEGACY_HAPP_USER_AGENT = "Happ/3.17.0/Android/17756505247711753599"
private const val V2RAY_TUN_USER_AGENT = "v2raytun/android"
private const val INCY_USER_AGENT = "INCY/3.4.3/android Dalvik/2.1.0"

internal fun defaultSpoofUserAgent(spoofApp: Int): String =
    when (spoofApp) {
        SpoofApp.HAPP -> HAPP_USER_AGENT
        SpoofApp.V2RAY_TUN -> V2RAY_TUN_USER_AGENT
        SpoofApp.INCY -> INCY_USER_AGENT
        else -> ""
    }

internal fun normalizeSpoofUserAgent(spoofApp: Int, customUserAgent: String): String {
    if (spoofApp == SpoofApp.NONE) return customUserAgent
    if (
        customUserAgent.isBlank() ||
        spoofApp == SpoofApp.HAPP && customUserAgent == LEGACY_HAPP_USER_AGENT
    ) {
        return defaultSpoofUserAgent(spoofApp)
    }
    return customUserAgent
}

internal fun shouldWarnAboutMissingSpoofHwid(spoofApp: Int, hwidEnabled: Boolean): Boolean =
    spoofApp != SpoofApp.NONE && !hwidEnabled

internal fun buildSubscriptionRequestFingerprint(
    spoofApp: Int,
    hwidEnabled: Boolean,
    customUserAgent: String,
    fallbackUserAgent: String,
    manufacturer: String = Build.MANUFACTURER.orEmpty(),
    model: String = Build.MODEL.orEmpty(),
    sdkVersion: Int = Build.VERSION.SDK_INT,
    locale: Locale = Locale.getDefault(),
    hwid: String? = if (hwidEnabled) HwidGenerator.generate(app, spoofApp) else null,
    deviceHeaders: Map<String, String>? = null,
    spoofDevice: Boolean = false,
): SubscriptionRequestFingerprint {
    val customized = hwidEnabled || spoofApp != SpoofApp.NONE ||
        customUserAgent.isNotEmpty() || spoofDevice
    val headers = when {
        deviceHeaders != null && customized -> deviceHeaders.mapValues { it.value.trim() }
            .filterValues(String::isNotEmpty).toMutableMap()
        deviceHeaders == null && (hwidEnabled || spoofApp != SpoofApp.NONE) ->
            generateSubscriptionDeviceHeaders(
                spoofApp, manufacturer, model, "Android", sdkVersion.toString(), locale,
            ).toMutableMap()
        else -> linkedMapOf()
    }
    val userAgent = if (deviceHeaders == null) {
        normalizeSpoofUserAgent(spoofApp, customUserAgent)
    } else customUserAgent

    headers.remove("X-Hwid")
    if (hwidEnabled && !hwid.isNullOrBlank()) headers["X-Hwid"] = hwid.trim()

    return SubscriptionRequestFingerprint(
        userAgent = userAgent.trim().takeIf(String::isNotBlank) ?: fallbackUserAgent,
        headers = headers,
    )
}

internal fun generateSubscriptionDeviceHeaders(
    spoofApp: Int,
    manufacturer: String = Build.MANUFACTURER.orEmpty(),
    model: String = Build.MODEL.orEmpty(),
    os: String = "Android",
    osVersion: String = Build.VERSION.SDK_INT.toString(),
    locale: Locale = Locale.getDefault(),
): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    val fullModel = "$manufacturer $model".trim()

    when (spoofApp) {
        SpoofApp.HAPP -> {
            headers["X-Device-Model"] = model
            headers["X-Ver-Os"] = osVersion
            headers["X-Device-Os"] = os
            headers["X-Device-Locale"] = locale.language
        }
        SpoofApp.V2RAY_TUN -> {
            headers["X-App-Version"] = "5.25.80"
            headers["X-Device-Model"] = fullModel
            headers["X-Ver-Os"] = "$os $osVersion"
            headers["X-Device-Os"] = os
        }
        SpoofApp.INCY -> {
            val deviceLocale =
                locale.country.takeIf(String::isNotEmpty)?.let { "${locale.language}_$it" }
                    ?: locale.language
            headers["Accept"] = "*/*"
            headers["Accept-Language"] = locale.toLanguageTag()
            headers["X-Client"] = "INCY"
            headers["X-Device-Locale"] = deviceLocale
            headers["X-App-Version"] = "3.4.3"
            headers["X-Device-Model"] = fullModel
            headers["X-Ver-Os"] = osVersion
            headers["X-Device-Os"] = os
        }
        else -> {
            headers["X-Device-Os"] = os
            headers["X-Ver-Os"] = osVersion
            headers["X-Device-Model"] = fullModel
        }
    }

    return headers
}

internal fun decodeSubscriptionDeviceHeaders(value: String?): Map<String, String>? {
    if (value == null) return null
    val json = JSONObject(value)
    return json.keys().asSequence().associateWith { json.getString(it) }
}

internal fun buildSubscriptionRequestFingerprint(
    subscription: SubscriptionBean,
    fallbackUserAgent: String,
): SubscriptionRequestFingerprint {
    val spoofApp = subscription.spoofApp ?: SpoofApp.NONE
    val enabled = subscription.hwidEnabled == true
    return buildSubscriptionRequestFingerprint(
        spoofApp = spoofApp,
        hwidEnabled = enabled,
        customUserAgent = subscription.customUserAgent.orEmpty(),
        fallbackUserAgent = fallbackUserAgent,
        hwid = if (enabled) subscription.hwid ?: HwidGenerator.generate(app, spoofApp) else null,
        deviceHeaders = decodeSubscriptionDeviceHeaders(subscription.deviceHeaders),
        spoofDevice = subscription.spoofDevice == true,
    )
}
