package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ui.subscriptionBannerPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionMetadataParserTest {
    @Test
    fun readsBannerFromBase64EncodedSubscription() {
        val announcement = "✅ НАШ ТГК: @Example | 🕑 Обновлено"
        val body = "#profile-title: 🇫🇲 Example • Автовыбор\n#announce: $announcement\nss://example"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val encodedBodies = listOf(
            Base64.getEncoder().encodeToString(bytes),
            Base64.getEncoder().withoutPadding().encodeToString(bytes),
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            Base64.getMimeEncoder(64, "\r\n".toByteArray()).encodeToString(bytes),
        )

        for (encodedBody in encodedBodies) {
            val metadata = SubscriptionMetadataParser.parse(
                SubscriptionDocument(encodedBody, source = SubscriptionDocument.Source.NETWORK),
                isFirstUpdate = true,
            )
            assertEquals("🇫🇲 Example • Автовыбор", metadata.suggestedName)
            assertEquals(announcement, metadata.announcement)
            val subscription = SubscriptionBean().apply {
                this.announcement = metadata.announcement
            }
            assertTrue(subscriptionBannerPresentation(subscription).visible)
        }
    }

    @Test
    fun responseHeadersOverrideEncodedBodyAndAnnouncementIsDecoded() {
        val body = """
            #profile-title: Body name
            #announce: base64:U2NoZWR1bGVkIG1haW50ZW5hbmNl
            #support-url: https://body.example
            #profile-update-interval: 2
            ss://example
            #announce-url: https://ignored.example
        """.trimIndent()
        val document = SubscriptionDocument(
            body = Base64.getEncoder().encodeToString(body.toByteArray()),
            headers = SubscriptionHeaders.of(mapOf("profile-title" to "Header name")),
            source = SubscriptionDocument.Source.NETWORK,
        )

        val metadata = SubscriptionMetadataParser.parse(document, isFirstUpdate = true)

        assertEquals("Header name", metadata.suggestedName)
        assertEquals("Scheduled maintenance", metadata.announcement)
        assertEquals("https://body.example", metadata.supportUrl)
        assertEquals(120, metadata.autoUpdateIntervalMinutes)
        assertEquals("", metadata.announcementUrl)
    }

    @Test
    fun malformedBase64DoesNotPreventResponseMetadata() {
        val document = SubscriptionDocument(
            body = "invalid%%%",
            headers = SubscriptionHeaders.of(mapOf("announce" to "Maintenance")),
            source = SubscriptionDocument.Source.NETWORK,
        )

        assertEquals(
            "Maintenance",
            SubscriptionMetadataParser.parse(document, isFirstUpdate = true).announcement,
        )
    }

    @Test
    fun responseHeadersOverrideBodyMetadata() {
        val document = SubscriptionDocument(
            body = """
                # profile-title: Body name
                # profile-update-interval: 12
                # support-url: https://body.example
                ss://example
            """.trimIndent(),
            headers = SubscriptionHeaders.of(
                mapOf(
                    "PROFILE-TITLE" to "base64:TmV0d29yayBuYW1l",
                    "Profile-Update-Interval" to "2",
                    "SUPPORT-URL" to "https://header.example",
                ),
            ),
            source = SubscriptionDocument.Source.NETWORK,
        )

        val metadata = SubscriptionMetadataParser.parse(document, isFirstUpdate = true)

        assertEquals("Network name", metadata.suggestedName)
        assertEquals(120, metadata.autoUpdateIntervalMinutes)
        assertEquals("https://header.example", metadata.supportUrl)
    }

    @Test
    fun updateIntervalIsIgnoredAfterFirstUpdate() {
        val document = SubscriptionDocument(
            body = "# profile-update-interval: 2\nss://example",
            source = SubscriptionDocument.Source.CONTENT,
        )

        assertNull(
            SubscriptionMetadataParser.parse(document, isFirstUpdate = false)
                .autoUpdateIntervalMinutes,
        )
    }
}
