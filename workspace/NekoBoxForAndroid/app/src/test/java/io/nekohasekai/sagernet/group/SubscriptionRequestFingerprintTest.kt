package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SubscriptionRequestFingerprintTest {
    private val locale = Locale("ru", "RU")

    @Test
    fun `builds current Happ fingerprint without requiring HWID`() {
        val fingerprint =
            fingerprint(
                spoofApp = SpoofApp.HAPP,
                hwidEnabled = false,
                customUserAgent = "",
            )

        assertEquals("Happ/3.26.3/Android/17839452147361875676", fingerprint.userAgent)
        assertEquals("CPH2653", fingerprint.headers["X-Device-Model"])
        assertEquals("16", fingerprint.headers["X-Ver-Os"])
        assertEquals("Android", fingerprint.headers["X-Device-Os"])
        assertEquals("ru", fingerprint.headers["X-Device-Locale"])
        assertNull(fingerprint.headers["X-Hwid"])
        assertNull(fingerprint.headers["X-App-Version"])
    }

    @Test
    fun `builds current v2RayTun fingerprint with optional HWID`() {
        val fingerprint =
            fingerprint(
                spoofApp = SpoofApp.V2RAY_TUN,
                hwidEnabled = true,
                customUserAgent = "",
                hwid = "77B51D5A660C2616",
            )

        assertEquals("v2raytun/android", fingerprint.userAgent)
        assertEquals("5.25.80", fingerprint.headers["X-App-Version"])
        assertEquals("OnePlus CPH2653", fingerprint.headers["X-Device-Model"])
        assertEquals("Android 16", fingerprint.headers["X-Ver-Os"])
        assertEquals("Android", fingerprint.headers["X-Device-Os"])
        assertEquals("77B51D5A660C2616", fingerprint.headers["X-Hwid"])
    }

    @Test
    fun `builds current Incy fingerprint`() {
        val fingerprint =
            fingerprint(
                spoofApp = SpoofApp.INCY,
                hwidEnabled = true,
                customUserAgent = "",
                hwid = "3A8A4A04-5633-BA20-0962-2D0A915822B4",
            )

        assertEquals("INCY/3.4.3/android Dalvik/2.1.0", fingerprint.userAgent)
        assertEquals("*/*", fingerprint.headers["Accept"])
        assertEquals("ru-RU", fingerprint.headers["Accept-Language"])
        assertEquals("INCY", fingerprint.headers["X-Client"])
        assertEquals("ru_RU", fingerprint.headers["X-Device-Locale"])
        assertEquals("3.4.3", fingerprint.headers["X-App-Version"])
        assertEquals("OnePlus CPH2653", fingerprint.headers["X-Device-Model"])
        assertEquals("16", fingerprint.headers["X-Ver-Os"])
        assertEquals("Android", fingerprint.headers["X-Device-Os"])
        assertEquals(
            "3A8A4A04-5633-BA20-0962-2D0A915822B4",
            fingerprint.headers["X-Hwid"],
        )
    }

    @Test
    fun `keeps generic HWID behavior when spoofing is disabled`() {
        val fingerprint =
            fingerprint(
                spoofApp = SpoofApp.NONE,
                hwidEnabled = true,
                customUserAgent = "",
                hwid = "f7f41cbf74c2181e",
            )

        assertEquals("NekoBox", fingerprint.userAgent)
        assertEquals("f7f41cbf74c2181e", fingerprint.headers["X-Hwid"])
        assertEquals("OnePlus CPH2653", fingerprint.headers["X-Device-Model"])
        assertEquals("16", fingerprint.headers["X-Ver-Os"])
        assertEquals("Android", fingerprint.headers["X-Device-Os"])
    }

    @Test
    fun `updates known Happ default and preserves custom user agents`() {
        assertEquals(
            "Happ/3.26.3/Android/17839452147361875676",
            normalizeSpoofUserAgent(
                SpoofApp.HAPP,
                "Happ/3.17.0/Android/17756505247711753599",
            ),
        )
        assertEquals(
            "ProviderClient/1.0",
            normalizeSpoofUserAgent(SpoofApp.HAPP, "ProviderClient/1.0"),
        )
    }

    @Test
    fun `warns only when a spoofed app has no HWID`() {
        assertTrue(shouldWarnAboutMissingSpoofHwid(SpoofApp.HAPP, false))
        assertTrue(shouldWarnAboutMissingSpoofHwid(SpoofApp.V2RAY_TUN, false))
        assertTrue(shouldWarnAboutMissingSpoofHwid(SpoofApp.INCY, false))
        assertFalse(shouldWarnAboutMissingSpoofHwid(SpoofApp.NONE, false))
        assertFalse(shouldWarnAboutMissingSpoofHwid(SpoofApp.INCY, true))
    }

    @Test
    fun `saved fingerprint sends trimmed overrides and omits cleared headers`() {
        val fingerprint = savedFingerprint(
            enabled = true,
            hwid = " custom-id ",
            headers = mapOf("X-Device-Model" to " iPhone17,3 ", "X-App-Version" to "  "),
        )
        assertEquals(mapOf("X-Device-Model" to "iPhone17,3", "X-Hwid" to "custom-id"), fingerprint.headers)
        assertEquals("Provider/1.0", fingerprint.userAgent)
    }

    @Test
    fun `disabled sending preserves identity without leaking HWID`() {
        val fingerprint = savedFingerprint(false, "custom-id",
            mapOf("X-Device-Os" to "iOS", "X-Hwid" to "unexpected"))
        assertEquals(mapOf("X-Device-Os" to "iOS"), fingerprint.headers)
    }

    @Test
    fun `saved defaults emit no device headers while overrides are disabled`() {
        val fingerprint = buildSubscriptionRequestFingerprint(
            spoofApp = SpoofApp.NONE, hwidEnabled = false, customUserAgent = "",
            fallbackUserAgent = "NekoBox", manufacturer = "OnePlus", model = "CPH2653",
            sdkVersion = 34, locale = locale, hwid = "generated",
            deviceHeaders = mapOf("X-Device-Os" to "Android"),
        )
        assertTrue(fingerprint.headers.isEmpty())
        assertEquals("NekoBox", fingerprint.userAgent)
    }

    @Test
    fun `clearing saved User-Agent uses fallback rather than restoring app default`() {
        val fingerprint = savedFingerprint(false, "generated", emptyMap(), userAgent = "")
        assertEquals("NekoBox", fingerprint.userAgent)
    }

    @Test
    fun `device replacement sends headers without enabling HWID`() {
        val fingerprint = buildSubscriptionRequestFingerprint(
            spoofApp = SpoofApp.NONE, hwidEnabled = false, customUserAgent = "",
            fallbackUserAgent = "NekoBox", manufacturer = "OnePlus", model = "CPH2653",
            sdkVersion = 34, locale = locale, hwid = "saved",
            deviceHeaders = mapOf("X-Device-Model" to "Google Pixel 10"), spoofDevice = true,
        )
        assertEquals(mapOf("X-Device-Model" to "Google Pixel 10"), fingerprint.headers)
    }

    @Test
    fun `shared subscription entrypoint honors stored overrides for both download paths`() {
        val subscription = SubscriptionBean().applyDefaultValues().apply {
            spoofApp = SpoofApp.HAPP
            hwidEnabled = true
            hwid = " edited-id "
            customUserAgent = "Provider/1.0"
            spoofDevice = true
            deviceHeaders = "{\"X-Device-Model\":\"iPhone17,3\",\"X-Device-Os\":\"iOS\",\"X-App-Version\":\"\"}"
        }
        val enabled = buildSubscriptionRequestFingerprint(subscription, "NekoBox")
        assertEquals("edited-id", enabled.headers["X-Hwid"])
        assertEquals("iPhone17,3", enabled.headers["X-Device-Model"])
        assertNull(enabled.headers["X-App-Version"])
        assertEquals("Provider/1.0", enabled.userAgent)
        subscription.hwidEnabled = false
        val disabled = buildSubscriptionRequestFingerprint(subscription, "NekoBox")
        assertNull(disabled.headers["X-Hwid"])
        assertEquals("iOS", disabled.headers["X-Device-Os"])
        assertEquals(" edited-id ", subscription.hwid)
    }

    private fun savedFingerprint(
        enabled: Boolean,
        hwid: String,
        headers: Map<String, String>,
        userAgent: String = " Provider/1.0 ",
    ) = buildSubscriptionRequestFingerprint(
        spoofApp = SpoofApp.HAPP, hwidEnabled = enabled, customUserAgent = userAgent,
        fallbackUserAgent = "NekoBox", manufacturer = "OnePlus", model = "CPH2653",
        sdkVersion = 34, locale = locale, hwid = hwid, deviceHeaders = headers,
    )

    private fun fingerprint(
        spoofApp: Int,
        hwidEnabled: Boolean,
        customUserAgent: String,
        hwid: String? = null,
    ) = buildSubscriptionRequestFingerprint(
        spoofApp = spoofApp,
        hwidEnabled = hwidEnabled,
        customUserAgent = customUserAgent,
        fallbackUserAgent = "NekoBox",
        manufacturer = "OnePlus",
        model = "CPH2653",
        sdkVersion = 16,
        locale = locale,
        hwid = hwid,
    )
}
