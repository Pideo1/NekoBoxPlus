package io.nekohasekai.sagernet.fmt.shadowsocks

import org.json.JSONObject
import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.*
import org.junit.Test

class ShadowsocksFmtTest {

    @Test
    fun standardLinkExportRoundTripsEveryTransportAndTls() {
        for (transport in listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
            val original = parseShadowsocks(
                "ss://aes-128-gcm:pass@example.com:443?type=$transport&security=tls" +
                    "&host=front.example&path=%2Fss&serviceName=ss&sni=tls.example" +
                    "&alpn=http%2F1.1&fp=chrome&allowInsecure=1&cert=certificate&mode=packet-up"
            ).apply {
                initializeDefaultValues()
                name = "Тест SS / + #"
            }
            val shared = parseShadowsocks(original.toUri()).apply { initializeDefaultValues() }
            assertEquals(original.serverAddress, shared.serverAddress)
            assertEquals(443, shared.serverPort)
            assertEquals(original.method, shared.method)
            assertEquals(original.password, shared.password)
            assertEquals(original.name, shared.name)
            assertEquals(transport, shared.type)
            assertEquals("tls", shared.security)
            assertEquals(original.host, shared.host)
            assertEquals(original.path, shared.path)
            assertEquals("tls.example", shared.sni)
            assertEquals("http/1.1", shared.alpn)
            assertEquals("chrome", shared.utlsFingerprint)
            assertEquals("certificate", shared.certificates)
            assertTrue(shared.allowInsecure)
            if (transport == "xhttp") assertEquals("packet-up", shared.xhttpMode)
        }
    }

    @Test
    fun standardLinkExportKeepsWebSocketEarlyData() {
        for (query in listOf("path=%2Fss&ed=2048&eh=X-Early-Data", "path=%2Fss%3Fed%3D2048")) {
            val original = parseShadowsocks("ss://aes-128-gcm:pass@example.com:443?type=ws&$query")
                .apply { initializeDefaultValues() }
            val shared = parseShadowsocks(original.toUri()).apply { initializeDefaultValues() }
            assertEquals("/ss", shared.path)
            val settings = JSONObject(shared.customOutboundJson).getJSONObject("transport")
            assertEquals(2048, settings.getInt("max_early_data"))
            assertEquals(if (query.contains("eh=")) "X-Early-Data" else "Sec-WebSocket-Protocol",
                settings.getString("early_data_header_name"))
        }
    }

    @Test
    fun standardLinkExportKeepsGrpcAuthorityFromConfig() {
        val bean = JSONObject("""
            {"server":"example.com","server_port":443,"method":"aes-128-gcm","password":"pass",
             "transport":{"type":"grpc","service_name":"ss","authority":"front.example"}}
        """.trimIndent()).parseShadowsocks().apply { initializeDefaultValues() }
        val shared = parseShadowsocks(bean.toUri()).apply { initializeDefaultValues() }
        assertEquals("ss", shared.path)
        assertEquals("front.example", JSONObject(shared.customOutboundJson)
            .getJSONObject("transport").getString("authority"))
    }

    @Test
    fun standardLinkExportKeepsXhttpExtraAndOverrides() {
        val bean = parseShadowsocks("ss://aes-128-gcm:pass@example.com:443?type=xhttp&path=%2Fold")
            .apply {
                initializeDefaultValues()
                xhttpExtra = """{"no_grpc_header":true,"headers":{"X-Test":"value"}}"""
                customOutboundJson = """{"transport":{"path":"/new","mode":"stream-one","sc_max_each_post_bytes":12345}}"""
            }
        val shared = parseShadowsocks(bean.toUri()).apply { initializeDefaultValues() }
        assertEquals("/new", shared.path)
        assertEquals("stream-one", shared.xhttpMode)
        val extra = JSONObject(shared.xhttpExtra)
        assertTrue(extra.getBoolean("no_grpc_header"))
        assertEquals("value", extra.getJSONObject("headers").getString("X-Test"))
        assertEquals(12345, extra.getInt("sc_max_each_post_bytes"))
    }

    @Test
    fun configurationExportRetainsCompleteTransportAndTlsOptions() {
        for (type in listOf("ws", "grpc", "httpupgrade", "xhttp")) {
            val bean = JSONObject("""
                {"server":"example.com","server_port":443,"method":"aes-128-gcm","password":"pass",
                 "transport":{"type":"$type","path":"/ss","service_name":"ss","host":"front.example",
                              "headers":{"Host":"front.example","X-Test":"value"},"max_early_data":2048,
                              "authority":"front.example"},
                 "tls":{"enabled":true,"insecure":true,"server_name":"tls.example","alpn":["http/1.1"],
                        "certificate":"certificate","handshake_timeout":"5s",
                        "ech":{"enabled":true,"config":["ech-config"]}}}
            """.trimIndent()).parseShadowsocks().apply { initializeDefaultValues() }
            // Use the same serializer and custom override path as buildConfig(forExport = true).
            val outbound = buildSingBoxOutboundShadowsocksBean(bean).apply {
                _hack_custom_config = bean.customOutboundJson
            }
            val exported = JSONObject(outbound.asMap())
            assertEquals(type, exported.getJSONObject("transport").getString("type"))
            assertEquals("value", exported.getJSONObject("transport").getJSONObject("headers").getString("X-Test"))
            assertEquals("5s", exported.getJSONObject("tls").getString("handshake_timeout"))
            assertEquals("ech-config", exported.getJSONObject("tls").getJSONObject("ech").getJSONArray("config").getString(0))
            val imported = exported.parseShadowsocks().apply { initializeDefaultValues() }
            assertEquals(type, imported.type)
            assertEquals("tls", imported.security)
            assertEquals("tls.example", imported.sni)
        }
    }

    @Test
    fun plainAndPluginLinkExportsRemainCompatible() {
        for (plugin in listOf("", "obfs-local;obfs=http;obfs-host=front.example")) {
            val original = parseShadowsocks("ss://aes-128-gcm:pass@example.com:8388")
                .apply { initializeDefaultValues(); this.plugin = plugin }
            val link = original.toUri()
            assertFalse(link.contains("type="))
            val imported = parseShadowsocks(link).apply { initializeDefaultValues() }
            assertEquals("tcp", imported.type)
            assertEquals("none", imported.security)
            assertEquals(plugin, imported.plugin)
        }
    }

    @Test
    fun standardLinkExportRejectsUnsupportedStreamsAndPluginConflicts() {
        for (settings in listOf("transport", "security", "plugin")) {
            val bean = parseShadowsocks("ss://aes-128-gcm:pass@example.com:443?type=ws")
                .apply { initializeDefaultValues() }
            when (settings) {
                "transport" -> bean.type = "kcp"
                "security" -> bean.security = "reality"
                "plugin" -> bean.plugin = "obfs-local"
            }
            assertThrows(IllegalArgumentException::class.java) { bean.toUri() }
        }
    }


    @Test
    fun legacyBase64LinksImportStreamParameters() {
        val authority = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:pass@example.com:443".toByteArray())
        val bean = parseShadowsocks("ss://$authority?type=grpc&serviceName=ss&security=tls&sni=tls.example#Legacy")
            .apply { initializeDefaultValues() }
        assertEquals("grpc", bean.type)
        assertEquals("ss", bean.path)
        assertEquals("tls", bean.security)
        assertEquals("tls.example", bean.sni)
        assertEquals("Legacy", bean.name)
    }

    @Test
    fun sip002Base64UserInfoImportsEverySupportedTransport() {
        val userInfo = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:pass".toByteArray())
        for (transport in listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
            val bean = parseShadowsocks(
                "ss://$userInfo@example.com:443?type=$transport&security=tls&path=%2Fss&serviceName=ss"
            )
            assertEquals(transport, bean.type)
            assertEquals("aes-128-gcm", bean.method)
            assertEquals("pass", bean.password)
            assertEquals("tls", bean.security)
        }
    }

    @Test
    fun legacyBase64LinkWithoutFragmentHasNoName() {
        val authority = java.util.Base64.getEncoder()
            .encodeToString("aes-128-gcm:pass@example.com:443".toByteArray())
        val bean = parseShadowsocks("ss://$authority?type=ws&path=%2Fss%3Fed%3D2048")
            .apply { initializeDefaultValues() }
        assertEquals("", bean.name)
        assertEquals("/ss", bean.path)
        val transport = JSONObject(bean.customOutboundJson).getJSONObject("transport")
        assertEquals(2048, transport.getInt("max_early_data"))
        assertEquals("Sec-WebSocket-Protocol", transport.getString("early_data_header_name"))
    }

    @Test
    fun jsonImportsEverySupportedTransportWithTls() {
        for (type in listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
            val bean = JSONObject("""
                {"server":"example.com","server_port":443,"method":"aes-128-gcm","password":"pass",
                 "transport":{"type":"$type","host":"front.example","headers":{"Host":"front.example"},
                              "path":"/ss","service_name":"ss","mode":"packet-up"},
                 "tls":{"enabled":true,"server_name":"tls.example","alpn":["h2","http/1.1"],
                        "insecure":true,"utls":{"enabled":true,"fingerprint":"chrome"}}}
            """.trimIndent()).parseShadowsocks().apply { initializeDefaultValues() }
            assertEquals(type, bean.type)
            assertEquals("tls", bean.security)
            assertEquals("tls.example", bean.sni)
            assertEquals("h2\nhttp/1.1", bean.alpn)
            assertEquals("chrome", bean.utlsFingerprint)
            assertTrue(bean.allowInsecure)
            assertEquals(if (type == "grpc") "ss" else "/ss", bean.path)
            val stored = bean.clone()
            assertEquals(bean.getHash(), stored.getHash())
            assertNotNull(buildSingBoxOutboundShadowsocksBean(stored).tls)
        }
    }

    @Test
    fun jsonPreservesUnmodeledTransportAndTlsOptions() {
        val bean = JSONObject("""
            {"server":"example.com","server_port":443,"method":"aes-128-gcm","password":"pass",
             "transport":{"type":"ws","path":"/ss","max_early_data":2048,
                          "headers":{"Host":"front.example","X-Test":"value"}},
             "tls":{"enabled":true,"handshake_timeout":"5s"}}
        """.trimIndent()).parseShadowsocks().apply { initializeDefaultValues() }
        val extra = JSONObject(bean.clone().customOutboundJson)
        assertEquals(2048, extra.getJSONObject("transport").getInt("max_early_data"))
        assertEquals("value", extra.getJSONObject("transport").getJSONObject("headers").getString("X-Test"))
        assertEquals("5s", extra.getJSONObject("tls").getString("handshake_timeout"))
    }

    @Test
    fun jsonRejectsUnsupportedStreamsAndPluginConflicts() {
        for (extra in listOf(
            "\"transport\":{\"type\":\"quic\"}",
            "\"tls\":{\"enabled\":true,\"reality\":{\"enabled\":true}}",
            "\"plugin\":\"obfs-local\",\"transport\":{\"type\":\"ws\"}",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                JSONObject("{\"method\":\"aes-128-gcm\",$extra}").parseShadowsocks()
            }
        }
    }


    @Test
    fun supportedCipherIsPreservedOnImport() {
        assertEquals(
            "aes-256-gcm",
            parseShadowsocks("ss://aes-256-gcm:pass@example.com:1234").method,
        )
    }

    @Test
    fun unsupportedCipherIsNormalizedToNoneOnImport() {
        assertEquals(
            "none",
            parseShadowsocks("ss://crypt:pass@example.com:1234").method,
        )
        assertEquals(
            "none",
            JSONObject()
                .put("server", "example.com")
                .put("server_port", 1234)
                .put("password", "pass")
                .put("method", "crypt")
                .put("plugin", "")
                .parseShadowsocks()
                .method,
        )
    }

    @Test
    fun streamLinksImportAndStorageRoundTrip() {
        for (transport in listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
            val original = parseShadowsocks(
                "ss://aes-128-gcm:pass@example.com:443?type=$transport&security=tls" +
                    "&sni=example.com&allowInsecure=1&fp=chrome&path=%2Fss&serviceName=ss&mode=packet-up"
            ).apply { initializeDefaultValues() }
            val stored = KryoConverters.deserialize(ShadowsocksBean(), KryoConverters.serialize(original))
            val shared = stored
            assertEquals(transport, shared.type)
            assertEquals("tls", shared.security)
            assertEquals(original.path, shared.path)
            assertEquals("chrome", shared.utlsFingerprint)
            assertTrue(shared.allowInsecure)
            assertEquals(original.getHash(), stored.getHash())
        }
    }

    @Test
    fun plainProfilesKeepVersionFiveAndIgnoreUnusedStreamFields() {
        val plain = ShadowsocksBean().apply { initializeDefaultValues() }
        val before = KryoConverters.serialize(plain)
        val hash = plain.getHash()
        plain.host = "unused.example"
        plain.path = "/unused"
        plain.sni = "unused.example"
        assertArrayEquals(before, KryoConverters.serialize(plain))
        assertEquals(hash, plain.getHash())
        val restored = KryoConverters.deserialize(ShadowsocksBean(), before)
        assertEquals("tcp", restored.type)
        assertEquals("none", restored.security)
        val outbound = buildSingBoxOutboundShadowsocksBean(restored)
        assertNull(outbound.tls)
        assertNull(outbound.transport)
    }

    @Test
    fun unsupportedSettingsAndPluginCombinationsAreRejected() {
        for (query in listOf("type=kcp", "security=reality", "security=unknown", "type=ws&plugin=obfs-local")) {
            assertThrows(IllegalArgumentException::class.java) {
                parseShadowsocks("ss://aes-128-gcm:pass@example.com:443?$query")
            }
        }
    }

    @Test
    fun xhttpExtraSurvivesImportStorageAndBuild() {
        val original = parseShadowsocks(
            "ss://aes-128-gcm:pass@example.com:443?type=xhttp&extra=%7B%22noGRPCHeader%22%3Atrue%7D"
        ).apply { initializeDefaultValues() }
        val stored = KryoConverters.deserialize(ShadowsocksBean(), KryoConverters.serialize(original))
        val shared = stored
        val transport = buildSingBoxOutboundShadowsocksBean(shared).transport as
            moe.matsuri.nb4a.SingBoxOptions.V2RayTransportOptions_XHTTPOptions
        assertTrue(transport.no_grpc_header.asBoolean)
    }

    @Test
    fun originalVersionFiveBytesArePreserved() {
        val legacy = ShadowsocksBean().apply {
            initializeDefaultValues()
            serverAddress = "example.com"
            serverPort = 8388
            method = "aes-128-gcm"
            password = "legacy-password"
            plugin = "obfs-local;obfs=http"
            sUoT = true
            enableMux = true
        }
        val fixture = javaClass.getResourceAsStream("/shadowsocks-v5.bin")!!.use { it.readBytes() }
        assertArrayEquals(fixture, KryoConverters.serialize(legacy))
        val restored = KryoConverters.deserialize(ShadowsocksBean(), fixture)
        assertEquals("tcp", restored.type)
        assertEquals("none", restored.security)
        assertEquals("obfs-local;obfs=http", restored.plugin)
        assertTrue(restored.sUoT)
        assertTrue(restored.enableMux)
        assertArrayEquals(fixture, KryoConverters.serialize(restored))
    }

    @Test
    fun plainImportedProfilesBuildBeforeDefaultInitialization() {
        val bean = parseShadowsocks("ss://aes-128-gcm:pass@example.com:1234")
        val outbound = buildSingBoxOutboundShadowsocksBean(bean)
        assertEquals("aes-128-gcm", outbound.method)
        assertNull(outbound.tls)
        assertNull(outbound.transport)
    }
}
