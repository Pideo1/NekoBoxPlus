package io.nekohasekai.sagernet.group

import com.esotericsoftware.kryo.io.ByteBufferInput
import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SubscriptionDeviceSerializationTest {
    @Test
    fun `version 9 keeps custom identity preset and cleared headers`() {
        val source = SubscriptionBean().applyDefaultValues().apply {
            hwidEnabled = true
            hwid = "custom-id"
            spoofApp = SpoofApp.HAPP
            customUserAgent = "" // An explicit clear must survive reopening.
            spoofDevice = true
            devicePresetId = "ios_iPhone17_3_18"
            deviceHeaders = JSONObject(mapOf("X-Device-Os" to "iOS", "X-App-Version" to "")).toString()
        }
        val restored = KryoConverters.subscriptionDeserialize(KryoConverters.serialize(source))
        assertTrue(restored.hwidEnabled)
        assertEquals(source.hwid, restored.hwid)
        assertEquals(source.spoofApp, restored.spoofApp)
        assertEquals("", restored.customUserAgent)
        assertTrue(restored.spoofDevice)
        assertEquals(source.devicePresetId, restored.devicePresetId)
        assertEquals(mapOf("X-Device-Os" to "iOS", "X-App-Version" to ""),
            decodeSubscriptionDeviceHeaders(restored.deviceHeaders))
    }

    @Test
    fun `uninitialized values stay distinct from intentionally cleared values`() {
        val source = SubscriptionBean().applyDefaultValues()
        val legacyDefaults = KryoConverters.subscriptionDeserialize(KryoConverters.serialize(source))
        assertNull(legacyDefaults.hwid)
        assertNull(legacyDefaults.deviceHeaders)
        assertFalse(legacyDefaults.hwidEnabled)
        assertFalse(legacyDefaults.spoofDevice)
        source.hwid = ""
        source.deviceHeaders = "{}"
        val cleared = KryoConverters.subscriptionDeserialize(KryoConverters.serialize(source))
        assertEquals("", cleared.hwid)
        assertEquals(emptyMap<String, String>(), decodeSubscriptionDeviceHeaders(cleared.deviceHeaders))
    }

    @Test
    fun `version 8 keeps enabled app settings without reading the following group fields`() {
        val source = SubscriptionBean().applyDefaultValues().apply {
            hwidEnabled = true
            spoofApp = SpoofApp.INCY
            customUserAgent = "Provider/legacy"
        }
        val input = ByteBufferInput(version8(source))
        val restored = SubscriptionBean().apply {
            deserializeFromBuffer(input)
            initializeDefaultValues()
        }
        assertTrue(restored.hwidEnabled)
        assertEquals(SpoofApp.INCY, restored.spoofApp)
        assertEquals("Provider/legacy", restored.customUserAgent)
        assertNull(restored.hwid)
        assertNull(restored.deviceHeaders)
        assertFalse(restored.spoofDevice)
        assertEquals("", restored.devicePresetId)
        assertEquals(0x76543210, input.readInt())
    }

    @Test
    fun `separate subscriptions cannot overwrite each other's saved identity`() {
        val first = SubscriptionBean().applyDefaultValues().apply {
            hwid = "first"
            deviceHeaders = "{\"X-Device-Model\":\"first\"}"
        }
        val second = SubscriptionBean().applyDefaultValues().apply {
            hwid = "second"
            deviceHeaders = "{\"X-Device-Model\":\"second\"}"
        }
        val restored = listOf(first, second).map {
            KryoConverters.subscriptionDeserialize(KryoConverters.serialize(it))
        }
        assertEquals(listOf("first", "second"), restored.map { it.hwid })
        assertEquals(listOf("first", "second"), restored.map {
            decodeSubscriptionDeviceHeaders(it.deviceHeaders)?.get("X-Device-Model")
        })
    }

    @Test
    fun `subscription sharing omits saved device identity`() {
        val source = SubscriptionBean().applyDefaultValues().apply {
            hwidEnabled = true
            hwid = "custom"
            spoofDevice = true
            devicePresetId = "android_Pixel_10_16"
            deviceHeaders = "{\"X-Device-Model\":\"custom\"}"
        }
        val stream = ByteArrayOutputStream()
        val output = stream.byteBuffer()
        source.serializeForShare(output)
        output.flush()
        val shared = SubscriptionBean().apply {
            deserializeFromShare(ByteBufferInput(stream.toByteArray()))
            initializeDefaultValues()
        }
        assertFalse(shared.hwidEnabled)
        assertFalse(shared.spoofDevice)
        assertNull(shared.hwid)
        assertNull(shared.deviceHeaders)
        assertEquals("", shared.devicePresetId)
    }

    private fun version8(source: SubscriptionBean): ByteArray {
        val stream = ByteArrayOutputStream()
        val output = stream.byteBuffer()

        output.writeInt(8)

        output.writeInt(source.type)

        output.writeString(source.link)

        output.writeBoolean(source.forceResolve)
        output.writeBoolean(source.deduplication)
        output.writeBoolean(source.updateWhenConnectedOnly)
        output.writeString(source.customUserAgent)
        output.writeBoolean(source.autoUpdate)
        output.writeInt(source.autoUpdateDelay)
        output.writeInt(source.lastUpdated)

        output.writeString(source.subscriptionUserinfo)

        // v2
        output.writeInt(source.filterMode)
        output.writeString(source.filterRegex)

        // v3
        output.writeBoolean(source.hwidEnabled)
        output.writeInt(source.spoofApp)

        // v4
        output.writeString(source.serverDnsResolver)

        // v5
        output.writeInt(source.bannerLayout)
        output.writeString(source.announcement)
        output.writeString(source.announcementUrl)
        output.writeString(source.supportUrl)
        output.writeString(source.supportEmail)
        output.writeString(source.profileWebPageUrl)
        output.writeString(source.homepage)

        // v6
        output.writeBoolean(source.routingEnabled)
        output.writeString(source.routingPayload)
        output.writeString(source.routingFormat)
        output.writeString(source.autoRoutingUrl)
        output.writeInt(source.routingUpdateInterval)
        output.writeLong(source.routingLastUpdated)
        output.writeBoolean(source.routingOff)

        // v7
        output.writeLong(source.expireAt)

        // v8
        output.writeBoolean(source.providerAutoUpdateDefaultsApplied)
        output.writeInt(0x76543210)
        output.flush()
        return stream.toByteArray()
    }
}
