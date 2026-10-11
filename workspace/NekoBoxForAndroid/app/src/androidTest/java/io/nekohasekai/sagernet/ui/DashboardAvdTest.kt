package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.dashboard.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Requires dashboard_server.py and a sing-box SOCKS listener on host port 19090. */
@RunWith(AndroidJUnit4::class)
class DashboardAvdTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: DashboardViewModel
    private var group = 0L
    private var originalProfile = 0L
    private var originalGroup = 0L
    private var originalFirstSetup = false
    private var originalMode = ""
    private var originalClash: Boolean? = null
    private var originalUrl = ""
    private var originalTimeout = 0
    private var originalAttempts = 0
    private var originalWidgets: String? = null
    private var originalActive: Boolean? = null
    private var originalClosed: Boolean? = null
    private var originalSort: Int? = null
    private var originalDescending: Boolean? = null
    private val executor = Executors.newCachedThreadPool()

    @Before fun setup() {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        // Android's systemExempted foreground-service type requires VPN consent,
        // including when exercising this application's proxy-only service.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} ACTIVATE_VPN allow"
        )).use { it.readBytes() }
        assertNull(android.net.VpnService.prepare(context))
        assertEquals("OK", URL("http://10.0.2.2:18765/health").readText())
        runBlocking(Dispatchers.IO) {
            originalFirstSetup = DataStore.proxyAppsFirstSetup
            DataStore.proxyAppsFirstSetup = true
            originalProfile = DataStore.selectedProxy; originalGroup = DataStore.selectedGroup
            originalMode = DataStore.serviceMode
            originalClash = DataStore.configurationStore.getBoolean(Key.ENABLE_CLASH_API)
            originalUrl = DataStore.connectionTestURL; originalTimeout = DataStore.connectionTestTimeout
            originalAttempts = DataStore.connectionTestAttempts
            originalWidgets = DataStore.configurationStore.getString("dashboardWidgets")
            originalActive = DataStore.configurationStore.getBoolean("dashboardActive")
            originalClosed = DataStore.configurationStore.getBoolean("dashboardClosed")
            originalSort = DataStore.configurationStore.getInt("dashboardSort")
            originalDescending = DataStore.configurationStore.getBoolean("dashboardDescending")
            DataStore.configurationStore.remove("dashboardSort")
            DataStore.configurationStore.remove("dashboardDescending")
            DataStore.enableClashAPI = false; DataStore.serviceMode = Key.MODE_PROXY
            DataStore.connectionTestURL = "http://10.0.2.2:18765/generate_204"
            DataStore.connectionTestTimeout = 1500; DataStore.connectionTestAttempts = 1
            DataStore.configurationStore.putBoolean("dashboardActive", true)
            DataStore.configurationStore.putBoolean("dashboardClosed", true)
            DataStore.configurationStore.remove("dashboardWidgets")
            group = GroupManager.createGroup(ProxyGroup(name = "Dashboard local AVD test")).id
            val bean = ConfigBean().applyDefaultValues().apply {
                name = "Dashboard local core"; type = 0
                config = """{
                  "log":{"level":"warn"},
                  "inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2087}],
                  "outbounds":[
                    {"type":"selector","tag":"proxy","outbounds":["remote-one","remote-two","unavailable"],"default":"remote-one"},
                    {"type":"socks","tag":"remote-one","server":"10.0.2.2","server_port":19090},
                    {"type":"socks","tag":"remote-two","server":"10.0.2.2","server_port":19090},
                    {"type":"socks","tag":"unavailable","server":"10.0.2.2","server_port":19091},
                    {"type":"direct","tag":"bypass"}
                  ],
                  "route":{"rules":[
                    {"clash_mode":"Direct","action":"route","outbound":"bypass"},
                    {"clash_mode":"Global","action":"route","outbound":"proxy"},
                    {"clash_mode":"Rule","port":18766,"action":"route","outbound":"bypass"}
                  ],"final":"proxy"},
                  "experimental":{"cache_file":{"enabled":true,"path":"${File(context.cacheDir, "dashboard-avd-test.db").absolutePath}"}}
                }"""
            }
            DataStore.selectedProxy = ProfileManager.createProfile(group, bean).id
            DataStore.selectedGroup = group
        }
    }
    @After fun cleanup() {
        SagerNet.stopService()
        executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS)
        try {
            // Keep MainActivity's Binder listener attached until teardown finishes.
            compose.waitUntil(15000) {
                if (!File(DataStore.dashboardSocketPath).exists()) true
                else try {
                    android.net.LocalSocket().use { it.connect(android.net.LocalSocketAddress(
                        DataStore.dashboardSocketPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM)) }
                    false
                } catch (_: java.io.IOException) { true } // A killed test process can leave a stale socket file.
            }
        } finally {
            scenario?.close(); scenario = null
            runBlocking(Dispatchers.IO) {
                if (group != 0L) GroupManager.deleteGroup(group)
                DataStore.selectedProxy = originalProfile; DataStore.selectedGroup = originalGroup
                DataStore.proxyAppsFirstSetup = originalFirstSetup
                DataStore.serviceMode = originalMode
                DataStore.configurationStore.putBoolean(Key.ENABLE_CLASH_API, originalClash)
                DataStore.connectionTestURL = originalUrl; DataStore.connectionTestTimeout = originalTimeout
                DataStore.connectionTestAttempts = originalAttempts
                DataStore.configurationStore.putString("dashboardWidgets", originalWidgets)
                DataStore.configurationStore.putBoolean("dashboardActive", originalActive)
                DataStore.configurationStore.putBoolean("dashboardClosed", originalClosed)
                DataStore.configurationStore.putInt("dashboardSort", originalSort)
                DataStore.configurationStore.putBoolean("dashboardDescending", originalDescending)
            }
            File(context.cacheDir, "dashboard-avd-test.db").delete()
        }
    }
    private fun launch() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        scenario!!.onActivity {
            it.displayFragmentWithId(R.id.nav_dashboard)
            it.supportFragmentManager.executePendingTransactions()
            model = dashboardModel(it)
        }
    }
    private fun dashboardModel(activity: MainActivity): DashboardViewModel {
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder)
        assertTrue(fragment is DashboardFragment)
        return ViewModelProvider(fragment!!)[DashboardViewModel::class.java]
    }
    private fun label(id: Int) = context.getString(id)
    private fun action(block: () -> Unit) { scenario!!.onActivity { block() } }
    private fun await(condition: () -> Boolean) { compose.waitUntil(20000, condition) }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(750) // Allow the system rotation animation to finish.
        val directory = File(context.cacheDir, "dashboard-test-screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            DashboardTestArtifacts.export("cache/dashboard-test-screenshots/$name.png", name)
        }
    }
    private fun traffic(port: Int, slow: Boolean = false) = executor.submit<Long> {
        val connection = URL("http://10.0.2.2:$port/${if (slow) "slow" else "bytes"}").openConnection(
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 2087))) as HttpURLConnection
        connection.connectTimeout = 5000; connection.readTimeout = 15000
        try { connection.inputStream.use { input ->
            val buffer = ByteArray(16384); var total = 0L
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count }
            total
        } } finally { connection.disconnect() }
    }

    @Test fun liveDashboardAgainstLocalSingBox() {
        launch()
        compose.onNodeWithText(label(R.string.dashboard_stopped)).assertIsDisplayed()
        action { SagerNet.startService() }
        await { model.state.available }
        assertTrue(File(DataStore.dashboardSocketPath).exists())
        assertThrows(java.io.IOException::class.java) {
            android.net.LocalSocket().use { socket -> socket.connect(android.net.LocalSocketAddress(
                DataStore.clashApiSocketPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM)) }
        }
        assertEquals(setOf("Rule", "Global", "Direct"), model.state.modes.toSet())
        await { model.state.groups.any { it.tag == "proxy" } }
        action { model.select("proxy", "remote-one") }
        await { model.state.groups.first { it.tag == "proxy" }.selected == "remote-one" }
        action { model.setMode("Rule") }
        await { model.state.mode == "Rule" }
        assertTrue("Proxy fixture", traffic(18765).get(10, TimeUnit.SECONDS) > 0)
        assertTrue("Direct fixture", traffic(18766).get(10, TimeUnit.SECONDS) > 0)
        val proxy = traffic(18765, slow = true)
        val direct = traffic(18766, slow = true)
        await { model.state.proxyDown > 0 && model.state.directDown > 0 }
        screenshot("status")
        compose.onNodeWithText(label(R.string.dashboard_connections)).performClick()
        await { model.state.connections.any { !it.closed } }
        val tracked = model.state.connections.first { !it.closed && it.outbound == "remote-one" }
        action { model.selectedId = tracked.id }
        screenshot("connection-details")
        compose.onNodeWithContentDescription(label(R.string.dashboard_copy)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.dashboard_disconnect)).performScrollTo().performClick()
        await { model.state.connections.find { it.id == tracked.id }?.closed == true }
        action { model.selectedId = null }
        compose.onNodeWithContentDescription(label(R.string.dashboard_pause)).performClick()
        assertTrue(model.paused)
        compose.onNodeWithContentDescription(label(R.string.dashboard_resume)).performClick()
        assertFalse(model.paused)
        proxy.cancel(true); direct.cancel(true)
        action { model.query = "no-such-connection" }
        compose.onNodeWithText(label(R.string.dashboard_no_matches)).assertIsDisplayed()
        action { model.query = "" }
        screenshot("connections")
        compose.onNodeWithText(label(R.string.dashboard_proxies)).performClick()
        await { model.state.groups.any { it.tag == "proxy" } }
        action { model.expand("proxy", true) }
        await { model.state.groups.firstOrNull { it.tag == "proxy" }?.expanded == true }
        val selectableProxy = compose.onNodeWithTag("dashboard-proxy:proxy:remote-two")
        compose.waitUntil(5000) { runCatching { selectableProxy.assertExists(); true }.getOrDefault(false) }
        selectableProxy.performScrollTo().performClick()
        await { model.state.groups.firstOrNull { it.tag == "proxy" }?.selected == "remote-two" }
        await {
            var started = false
            scenario!!.onActivity {
                // Selector callbacks can reset the transport; take the fresh group and start atomically.
                model.state.groups.firstOrNull { it.tag == "proxy" }?.let { group ->
                    model.test(group.items, "proxy")
                    started = "proxy" in model.state.progress
                }
            }
            started
        }
        await { model.state.outbounds.any { it.tag == "remote-two" && it.delay >= 0 } }
        await { "proxy" !in model.state.progress }
        assertTrue(model.state.testErrors.containsKey("unavailable"))
        screenshot("proxies")
        action { model.proxyQuery = "remote-two" }
        compose.onAllNodesWithText("remote-one").assertCountEquals(0)
        action { model.proxyQuery = "" }
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        await { model.state.available && model.state.groups.isNotEmpty() }
        screenshot("landscape")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        compose.onNodeWithText(label(R.string.dashboard_status)).performClick()
        compose.onNodeWithContentDescription(label(R.string.dashboard_widgets)).performClick()
        compose.onAllNodesWithText(label(R.string.dashboard_proxy_traffic)).onLast().assertIsDisplayed()
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        action { model.updateWidgets(model.widgets.map { if (it.first == "direct") it.first to false else it }) }
        compose.onAllNodesWithText(label(R.string.dashboard_direct_traffic)).assertCountEquals(0)
        await { DataStore.configurationStore.getString("dashboardWidgets").orEmpty().contains("!direct") }
        scenario!!.recreate()
        scenario!!.onActivity { model = dashboardModel(it) }
        await { model.state.available }
        assertFalse(model.widgets.first { it.first == "direct" }.second)
        action { model.setMode("Direct") }
        await { model.state.mode == "Direct" }
        action { model.setMode("Rule") }
        await { model.state.mode == "Rule" }
        action { SagerNet.stopService() }
        await { model.state.stopped && !File(DataStore.dashboardSocketPath).exists() }
        action { SagerNet.startService() }
        await { model.state.available }
        val client = Libcore.newDashboardClient(DataStore.dashboardSocketPath)
        try {
            val response = JSONObject(client.call("GetClashModeStatus", "{}", 3000))
            assertEquals("Rule", response.getString("currentMode"))
            assertThrows(Exception::class.java) { client.call("SubscribeLog", "{}", 1000) }
            assertThrows(Exception::class.java) { client.call("SelectOutbound", "{\"groupTag\":\"invalid\",\"outboundTag\":\"bad\"}", 1000) }
        } finally { client.close() }
        screenshot("restarted")
    }

    @Test fun failedAndTimedOutProbesCanRetryWithoutReconnect() {
        launch()
        action { SagerNet.startService() }
        await { model.state.available && model.state.outbounds.isNotEmpty() }
        compose.onNodeWithText(label(R.string.dashboard_proxies)).performClick()
        action { model.proxyQuery = "remote-one" }
        val button = compose.onNodeWithTag("dashboard-test::remote-one")
        action { DataStore.connectionTestURL = "http://10.0.2.2:18765/disconnect" }
        repeat(3) {
            button.assertIsEnabled().performClick()
            await { model.state.progress.isEmpty() && "remote-one" in model.state.testErrors }
            button.assertIsEnabled()
            assertTrue(model.state.available)
        }
        action {
            DataStore.connectionTestURL = "http://10.0.2.2:18765/timeout"
            DataStore.connectionTestTimeout = 200
        }
        button.performClick()
        await { model.state.progress.isEmpty() && "remote-one" in model.state.testErrors }
        button.assertIsEnabled()
        action {
            DataStore.connectionTestURL = "http://10.0.2.2:18765/generate_204"
            DataStore.connectionTestTimeout = 1500
        }
        button.performClick()
        await { model.state.progress.isEmpty() && "remote-one" !in model.state.testErrors &&
            model.state.outbounds.any { it.tag == "remote-one" && it.delay >= 0 } }
        button.assertIsEnabled()
        screenshot("retry-recovered")
    }

    @Test fun inlineDrawerBottomTabsAndSortSelection() {
        launch()
        compose.onNodeWithContentDescription(label(R.string.navigation_drawer_open)).performClick()
        compose.onNode(hasText(label(R.string.integrated_dashboard)) and hasClickAction()).assertIsSelected()
        compose.onNodeWithText(label(R.string.menu_configuration)).performClick()
        scenario!!.onActivity {
            it.supportFragmentManager.executePendingTransactions()
            assertTrue(it.supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment)
            it.displayFragmentWithId(R.id.nav_traffic)
            it.supportFragmentManager.executePendingTransactions()
            model = dashboardModel(it)
        }
        compose.waitForIdle()
        val display = context.resources.displayMetrics
        val y = display.heightPixels / 2
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "input swipe 2 $y ${display.widthPixels / 2} $y 400"
        )).use { it.readBytes() }
        compose.waitUntil(5000) { runCatching {
            compose.onNodeWithText(label(R.string.menu_configuration)).isDisplayed()
        }.getOrDefault(false) }
        compose.onNode(hasText(label(R.string.integrated_dashboard)) and hasClickAction()).assertIsSelected().performClick()
        val tabs = listOf(R.string.dashboard_status, R.string.dashboard_connections, R.string.dashboard_proxies)
        tabs.forEach { id ->
            val bounds = compose.onNode(hasText(label(id)) and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Tab is not at the bottom", bounds.top > display.heightPixels * 0.75f)
        }
        compose.onNodeWithText(label(R.string.dashboard_connections)).performClick()
        val sort = context.getString(R.string.dashboard_sort_current, label(R.string.dashboard_started))
        compose.onNodeWithText(sort).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.dashboard_download_speed)).performClick()
        compose.onNodeWithText(context.getString(R.string.dashboard_sort_current,
            label(R.string.dashboard_download_speed))).assertIsDisplayed()
        scenario!!.recreate()
        scenario!!.onActivity { model = dashboardModel(it); it.openDrawer() }
        compose.onNode(hasText(label(R.string.integrated_dashboard)) and hasClickAction()).assertIsSelected().performClick()
        assertEquals(ConnectionSort.DOWNLOAD_SPEED, model.sort)
        compose.waitForIdle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "input keyevent KEYCODE_BACK"
        )).use { it.readBytes() }
        compose.waitUntil(5000) {
            var configuration = false
            scenario!!.onActivity { configuration = it.supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment }
            configuration
        }
    }

    @Test fun legacyWebViewAndMenuSwitch() {
        val firstSetup = DataStore.proxyAppsFirstSetup
        val hideApi = DataStore.hideClashApi
        var main: ActivityScenario<MainActivity>? = null
        try {
            runBlocking(Dispatchers.IO) {
                DataStore.proxyAppsFirstSetup = true
                DataStore.enableClashAPI = true
                DataStore.hideClashApi = true
                val bean = io.nekohasekai.sagernet.fmt.socks.SOCKSBean().applyDefaultValues().apply {
                    name = "Dashboard legacy local core"
                    serverAddress = "10.0.2.2"
                    serverPort = 19090
                }
                DataStore.selectedProxy = ProfileManager.createProfile(group, bean).id
            }
            SagerNet.startService()
            await { File(DataStore.clashApiSocketPath).exists() }
            android.net.LocalSocket().use { socket ->
                socket.connect(android.net.LocalSocketAddress(DataStore.clashApiSocketPath,
                    android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = 5000
                socket.outputStream.write(("GET /version HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " +
                    DataStore.clashApiSecret + "\r\nConnection: close\r\n\r\n").toByteArray())
                val response = socket.inputStream.bufferedReader().readText()
                assertTrue(response.contains("200 OK"))
                assertTrue(response.contains("version"))
            }
            main = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            main.onActivity { it.openDrawer() }
            await { runCatching { compose.onNodeWithText(label(R.string.menu_dashboard)).isDisplayed() }.getOrDefault(false) }
            compose.onNodeWithText(label(R.string.menu_dashboard)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.integrated_dashboard)).assertDoesNotExist()
            main.onActivity { it.displayFragmentWithId(R.id.nav_traffic) }
            var webReady = false
            await {
                main!!.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder)
                    fun web(view: android.view.View?): android.webkit.WebView? = when (view) {
                        is android.webkit.WebView -> view
                        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { web(view.getChildAt(it)) }
                        else -> null
                    }
                    assertTrue(fragment is WebviewFragment)
                    web(fragment?.view)?.evaluateJavascript("document.readyState === 'complete' && document.body.innerText.length > 0") {
                        webReady = it == "true"
                    }
                }
                webReady
            }
            SagerNet.stopService()
            // Keep the Binder listener attached until the stopped state arrives.
            await { DataStore.serviceState == io.nekohasekai.sagernet.bg.BaseService.State.Stopped }
            main.close(); main = null
            DataStore.enableClashAPI = false
            main = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            main.onActivity { it.openDrawer() }
            await { runCatching { compose.onNodeWithText(label(R.string.integrated_dashboard)).isDisplayed() }.getOrDefault(false) }
            compose.onNodeWithText(label(R.string.integrated_dashboard)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.menu_dashboard)).assertDoesNotExist()
        } finally {
            main?.close()
            DataStore.proxyAppsFirstSetup = firstSetup
            DataStore.hideClashApi = hideApi
        }
    }

    @Test fun defaultsPreserveSavedChoice() {
        runBlocking(Dispatchers.IO) {
            DataStore.configurationStore.remove(Key.ENABLE_CLASH_API)
            assertFalse(DataStore.enableClashAPI)
            DataStore.initGlobal()
            assertFalse(DataStore.enableClashAPI)
            DataStore.enableClashAPI = true
            DataStore.initGlobal()
            assertTrue(DataStore.enableClashAPI)
            DataStore.enableClashAPI = false
            DataStore.initGlobal()
            assertFalse(DataStore.enableClashAPI)
        }
    }
}
