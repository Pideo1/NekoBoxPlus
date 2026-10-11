package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SpoofApp
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDeviceSettingsTest {
    private val current = SubscriptionDeviceSettings(
        "custom-hwid", "Provider/1.0", false, "", mapOf("X-Device-Model" to "edited"),
    )
    private val locale = Locale.forLanguageTag("ru-RU")

    @Test
    fun `preset selection preserves identity and replaces edited headers`() {
        val profile = SubscriptionDeviceProfiles.all.first { it.id == "ios_iPhone17_3_18" }
        val selected = regenerate(current, SpoofApp.HAPP, profile)
        assertEquals(current.hwid, selected.hwid)
        assertEquals(current.userAgent, selected.userAgent)
        assertTrue(selected.spoofDevice)
        assertEquals(profile.id, selected.devicePresetId)
        assertEquals(mapOf(
            "X-Device-Model" to "iPhone17,3", "X-Ver-Os" to "18.0",
            "X-Device-Os" to "iOS", "X-Device-Locale" to "ru",
        ), selected.headers)
    }

    @Test
    fun `disabling replacement restores real device and keeps identity`() {
        val profile = SubscriptionDeviceProfiles.forApp(SpoofApp.INCY).first()
        val selected = regenerate(current, SpoofApp.INCY, profile)
        val restored = regenerate(selected, SpoofApp.INCY)
        assertEquals(current.hwid, restored.hwid)
        assertEquals(current.userAgent, restored.userAgent)
        assertFalse(restored.spoofDevice)
        assertEquals("", restored.devicePresetId)
        assertEquals("OnePlus CPH2653", restored.headers["X-Device-Model"])
        assertEquals("34", restored.headers["X-Ver-Os"])
        assertEquals("ru_RU", restored.headers["X-Device-Locale"])
    }

    @Test
    fun `changing app resets identity profile and header edits`() {
        val selected = regenerate(current, SpoofApp.HAPP,
            SubscriptionDeviceProfiles.all.first { it.os == "iOS" })
        val changed = regenerate(selected, SpoofApp.V2RAY_TUN, preserveIdentity = false)
        assertEquals("generated", changed.hwid)
        assertEquals("v2raytun/android", changed.userAgent)
        assertFalse(changed.spoofDevice)
        assertEquals("", changed.devicePresetId)
        assertEquals("Android 34", changed.headers["X-Ver-Os"])
        assertNull(changed.headers["X-Device-Locale"])
    }

    @Test
    fun `HWID validation only requires a value when enabled`() {
        assertTrue(isSubscriptionHwidValid(false, ""))
        assertTrue(isSubscriptionHwidValid(true, "custom value"))
        assertFalse(isSubscriptionHwidValid(true, " \t"))
    }

    @Test
    fun `catalog preserves compatibility unique IDs and version ranges`() {
        val all = SubscriptionDeviceProfiles.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all, SubscriptionDeviceProfiles.forApp(SpoofApp.HAPP))
        for (app in listOf(SpoofApp.NONE, SpoofApp.INCY, SpoofApp.V2RAY_TUN)) {
            assertTrue(SubscriptionDeviceProfiles.forApp(app).all { it.os == "Android" })
            assertTrue(SubscriptionDeviceProfiles.forApp(app).contains(
                SubscriptionDeviceProfiles.randomForApp(app)))
        }
        assertTrue(all.any { it.id == "android_SM_G930F_7" })
        assertFalse(all.any { it.id == "android_SM_G930F_6" })
        assertTrue(all.any { it.id == "android_Pixel_10_16" })
        assertFalse(all.any { it.os == "Android" && it.osVersion.toInt() > 16 })
        assertTrue(all.any { it.id == "ios_iPhone10_1_11" })
        assertTrue(all.any { it.id == "ios_iPhone10_1_18" })
    }

    @Test
    fun `complete catalog matches tee-stream reference`() {
        val profiles = SubscriptionDeviceProfiles.all.sortedBy { it.id }
        assertEquals(417, profiles.size)
        val canonical = profiles.joinToString("\n") {
            listOf(it.id, it.manufacturer, it.model, it.os, it.osVersion).joinToString("|")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // Snapshot of tee-stream's complete preset catalog, including each supported OS version.
        assertEquals("9695c69af77e88ac771ef308191938da64860a4654afb3e88f3c566f279bee0b", digest)
    }

    private fun regenerate(
        settings: SubscriptionDeviceSettings,
        app: Int,
        profile: SubscriptionDeviceProfile? = null,
        preserveIdentity: Boolean = true,
    ) = regenerateSubscriptionDeviceSettings(
        settings, app, profile, generatedHwid = "generated", preserveIdentity = preserveIdentity,
        manufacturer = "OnePlus", model = "CPH2653", osVersion = "34", locale = locale,
    )
}
