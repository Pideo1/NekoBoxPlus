package io.nekohasekai.sagernet.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.shadowsocks.*
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Run fixtures/shadowsocks_transport_server.py on the emulator host first. */
@RunWith(AndroidJUnit4::class)
class ShadowsocksTransportAvdTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun importedProfilesTransferPayloadThroughEveryTransport() {
        val cases = JSONArray(URL("http://10.0.2.2:21300/cases").readText())
        val expected = "shadowsocks-transport-ok\n".repeat(8192)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val outbound = if (case.has("uri")) {
                val imported = parseShadowsocks(case.getString("uri")).apply { initializeDefaultValues() }
                // Exercise Android Base64 in both SIP002 export and import.
                val shared = parseShadowsocks(imported.toUri()).apply { initializeDefaultValues() }
                val bean = KryoConverters.deserialize(ShadowsocksBean(), KryoConverters.serialize(shared))
                assertEquals(name, imported.type, bean.type)
                assertEquals(name, imported.security, bean.security)
                assertEquals(name, imported.path, bean.path)
                JSONObject(Gson().toJson(buildSingBoxOutboundShadowsocksBean(bean)))
            } else {
                // HTTP, QUIC and KCP remain available through custom configs.
                case.getJSONObject("outbound")
            }
            outbound.put("tag", "test-proxy")
            val config = JSONObject()
                .put("log", JSONObject().put("level", "warn"))
                .put("inbounds", JSONArray().put(JSONObject()
                    .put("type", "mixed").put("listen", "127.0.0.1").put("listen_port", 21400)))
                .put("outbounds", JSONArray().put(outbound))
            if (name == "tcp" || case.optBoolean("udp")) {
                config.getJSONArray("inbounds").put(JSONObject()
                    .put("type", "direct").put("listen", "127.0.0.1").put("listen_port", 21401)
                    .put("network", "udp").put("override_address", "127.0.0.1").put("override_port", 21301))
            }
            val box = Libcore.newSingBoxInstance(config.toString(), null)
            try {
                box.start()
                // Repeated requests catch stale/reused transport stream failures.
                repeat(2) {
                    val connection = URL("http://127.0.0.1:21300/payload").openConnection(
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 21400))
                    ) as HttpURLConnection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 15000
                    try {
                        assertEquals(name, 200, connection.responseCode)
                        assertEquals(name, expected, connection.inputStream.bufferedReader().use { it.readText() })
                    } finally { connection.disconnect() }
                }
                if (name == "tcp" || case.optBoolean("udp")) {
                    java.net.DatagramSocket().use { socket ->
                        socket.soTimeout = 10000
                        val payload = "udp-through-$name".toByteArray()
                        socket.send(java.net.DatagramPacket(payload, payload.size,
                            java.net.InetAddress.getByName("127.0.0.1"), 21401))
                        val reply = java.net.DatagramPacket(ByteArray(1024), 1024)
                        socket.receive(reply)
                        assertEquals(name, String(payload), String(reply.data, 0, reply.length))
                    }
                }
                box.resetNetwork()
                val connection = URL("http://127.0.0.1:21300/health").openConnection(
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 21400))
                ) as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                try {
                    assertEquals("$name after reset", "shadowsocks-transport-ok\n",
                        connection.inputStream.bufferedReader().use { it.readText() })
                } finally { connection.disconnect() }
                android.util.Log.i("ShadowsocksTransportTest", "PASS $name: payload, reuse, network reset")
            } finally { box.close() }
        }
    }

    @Test
    fun certificateAndXhttpExtraSurviveShareLinks() {
        val original = ShadowsocksBean().apply {
            initializeDefaultValues()
            serverAddress = "example.com"
            serverPort = 443
            type = "xhttp"
            security = "tls"
            certificates = "-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----"
            xhttpExtra = "{\"no_grpc_header\":true}"
        }
        val restored = parseShadowsocks(original.toUri()).apply { initializeDefaultValues() }
        assertEquals(original.certificates, restored.certificates)
        assertTrue(JSONObject(restored.xhttpExtra).getBoolean("no_grpc_header"))
    }

    @Test
    fun profileEditorOpensInBothOrientations() {
        val context = instrumentation.targetContext
        val group = runBlocking(Dispatchers.IO) {
            GroupManager.createGroup(ProxyGroup(name = "Shadowsocks transport UI test")).id
        }
        try {
            val bean = ShadowsocksBean().apply {
                initializeDefaultValues()
                name = "Shadowsocks XHTTP TLS test"
                serverAddress = "10.0.2.2"
                type = "xhttp"
                security = "tls"
                allowInsecure = true
            }
            val profile = runBlocking(Dispatchers.IO) { ProfileManager.createProfile(group, bean) }
            ActivityScenario.launch<ShadowsocksSettingsActivity>(Intent(context, ShadowsocksSettingsActivity::class.java)
                .putExtra("id", profile.id)).use { scenario ->
                instrumentation.waitForIdleSync()
                for (orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { assertFalse(it.isFinishing) }
                }
            }
        } finally { runBlocking(Dispatchers.IO) { GroupManager.deleteGroup(group) } }
    }
}
