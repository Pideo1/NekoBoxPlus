package io.nekohasekai.sagernet.group

import android.os.Build
import java.util.Locale

internal data class SubscriptionDeviceSettings(
    val hwid: String,
    val userAgent: String,
    val spoofDevice: Boolean,
    val devicePresetId: String,
    val headers: Map<String, String>,
)

/** Device selection keeps identity edits; app selection regenerates the entire fingerprint. */
internal fun regenerateSubscriptionDeviceSettings(
    current: SubscriptionDeviceSettings,
    spoofApp: Int,
    profile: SubscriptionDeviceProfile? = null,
    generatedHwid: String = current.hwid,
    preserveIdentity: Boolean = true,
    manufacturer: String = Build.MANUFACTURER,
    model: String = Build.MODEL,
    osVersion: String = Build.VERSION.SDK_INT.toString(),
    locale: Locale = Locale.getDefault(),
): SubscriptionDeviceSettings = SubscriptionDeviceSettings(
    hwid = if (preserveIdentity) current.hwid else generatedHwid,
    userAgent = if (preserveIdentity) current.userAgent else defaultSpoofUserAgent(spoofApp),
    spoofDevice = profile != null,
    devicePresetId = profile?.id.orEmpty(),
    headers = generateSubscriptionDeviceHeaders(
        spoofApp,
        profile?.manufacturer ?: manufacturer,
        profile?.model ?: model,
        profile?.os ?: "Android",
        profile?.osVersion ?: osVersion,
        locale,
    ),
)

internal fun isSubscriptionHwidValid(enabled: Boolean, hwid: String): Boolean =
    !enabled || hwid.isNotBlank()
