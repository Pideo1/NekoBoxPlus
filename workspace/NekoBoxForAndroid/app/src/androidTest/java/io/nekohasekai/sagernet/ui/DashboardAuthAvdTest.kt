package io.nekohasekai.sagernet.ui

import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.dashboard.*
import libcore.DashboardStreamHandler
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Controlled protocol-status fixtures exercise authentication without real credentials. */
@RunWith(AndroidJUnit4::class)
class DashboardAuthAvdTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: DashboardViewModel
    private val bridge = FixtureBridge()
    private var originalWidgets: String? = null
    private var originalFirstSetup = false
    private var originalClash = false
    @Before fun saveSettings() {
        originalFirstSetup = io.nekohasekai.sagernet.database.DataStore.proxyAppsFirstSetup
        originalClash = io.nekohasekai.sagernet.database.DataStore.enableClashAPI
        io.nekohasekai.sagernet.database.DataStore.proxyAppsFirstSetup = true
        io.nekohasekai.sagernet.database.DataStore.enableClashAPI = false
        originalWidgets = io.nekohasekai.sagernet.database.DataStore.configurationStore.getString("dashboardWidgets")
    }
    @After fun cleanup() {
        if (::model.isInitialized) model.stop()
        scenario?.close()
        io.nekohasekai.sagernet.database.DataStore.proxyAppsFirstSetup = originalFirstSetup
        io.nekohasekai.sagernet.database.DataStore.enableClashAPI = originalClash
        io.nekohasekai.sagernet.database.DataStore.configurationStore.putString("dashboardWidgets", originalWidgets)
    }
    private fun launch(kind: String, endpoint: String) {
        originalWidgets = io.nekohasekai.sagernet.database.DataStore.configurationStore.getString("dashboardWidgets")
        bridge.endpoint = endpoint; bridge.kind = kind
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        scenario!!.onActivity { activity ->
            model = DashboardViewModel { bridge }
            model.updateWidgets(model.widgets.sortedBy { it.first != kind.lowercase() })
            activity.displayFragmentWithId(R.id.nav_dashboard)
            activity.supportFragmentManager.executePendingTransactions()
            val view = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder)!!.requireView() as ComposeView
            view.setContent { NekoComposeTheme { NativeDashboardScreen(model,
                onOpenDrawer = {}, onInterfaces = {}, onApp = {}, onRoute = {}, onBrowserAuth = {}) } }
            model.start()
        }
        compose.waitUntil(5000) { model.state.available && (model.state.openVPN + model.state.openConnect).isNotEmpty() }
    }
    @Test fun openVpnChallengeSubmitAndCancel() {
        launch("OpenVPN", """{"endpointTag":"vpn","stateText":"Authentication required","challenge":{"id":"vpn-test","kind":"credentials","message":"Test challenge"}}""")
        compose.onNodeWithText(context.getString(R.string.dashboard_authenticate)).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.dashboard_username)).performTextInput("test-user")
        compose.onNodeWithText(context.getString(R.string.dashboard_password)).performTextInput("test-password")
        compose.onNodeWithText(context.getString(R.string.apply)).performClick()
        compose.waitUntil(5000) { bridge.lastMethod == "SubmitOpenVPNChallengeResponse" }
        assertEquals("vpn-test", bridge.lastRequest.getString("challengeID"))
        assertEquals("test-user", bridge.lastRequest.getString("username"))
        compose.onNodeWithText(context.getString(R.string.dashboard_authenticate)).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.waitUntil(5000) { bridge.lastMethod == "CancelOpenVPNChallenge" }
    }
    @Test fun openConnectFormSubmitAndCancel() {
        launch("OpenConnect", """{"endpointTag":"oc","stateText":"Authentication required","authChallenge":{"id":"oc-test","form":{"fields":[{"submissionKey":"user","label":"Test user","kind":"text"},{"submissionKey":"pass","label":"Test password","kind":"password"},{"submissionKey":"realm","label":"Test realm","kind":"select","value":"first","options":[{"value":"first","label":"First"},{"value":"second","label":"Second"}]}]}}}""")
        compose.onNodeWithText(context.getString(R.string.dashboard_authenticate)).performScrollTo().performClick()
        compose.onNodeWithText("Test user").performTextInput("test-user")
        compose.onNodeWithText("Test password").performTextInput("test-password")
        compose.onNodeWithText("first").performClick()
        compose.onNodeWithText("Second").performClick()
        compose.onNodeWithText(context.getString(R.string.apply)).performClick()
        compose.waitUntil(5000) { bridge.lastMethod == "SubmitOpenConnectAuthResponse" }
        val values = bridge.lastRequest.getJSONObject("form").getJSONObject("values")
        assertEquals("test-user", values.getString("user")); assertEquals("second", values.getString("realm"))
        compose.onNodeWithText(context.getString(R.string.dashboard_authenticate)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.waitUntil(5000) { bridge.lastMethod == "CancelOpenConnectAuthChallenge" }
    }
    @Test fun browserChallengeCollectsRequestedCookie() {
        val request = JSONObject().put("endpointTag", "oc").put("challengeID", "browser-test")
            .put("browser", JSONObject().put("url", "http://10.0.2.2:18765/auth")
                .put("finalURL", "http://10.0.2.2:18765/auth-finished")
                .put("cookieNames", org.json.JSONArray(listOf("dashboard-test-session"))))
        val browser = ActivityScenario.launchActivityForResult<DashboardBrowserAuthActivity>(
            Intent(context, DashboardBrowserAuthActivity::class.java).putExtra("request", request.toString()))
        try {
            compose.waitUntil(15000) { browser.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            val result = browser.result
            assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
            val response = JSONObject(result.resultData.getStringExtra("response")!!)
            assertEquals("browser-test", response.getString("challengeID"))
            assertEquals("fixture", response.getJSONObject("browser").getJSONArray("cookies")
                .getJSONObject(0).getString("value"))
        } finally { browser.close() }
    }

    private class FixtureBridge : DashboardBridge {
        var kind = ""; var endpoint = "{}"
        @Volatile var lastMethod = ""
        @Volatile var lastRequest = JSONObject()
        override fun call(method: String, request: String, timeoutMillis: Int): String {
            lastRequest = JSONObject(request); lastMethod = method
            return if (method == "GetClashModeStatus") "{\"modeList\":[\"Rule\"],\"currentMode\":\"Rule\"}" else "{}"
        }
        override fun stream(method: String, request: String, handler: DashboardStreamHandler): DashboardSubscription {
            val message = when {
                method == "Subscribe${kind}Status" -> "{\"endpoints\":[$endpoint]}"
                method == "SubscribeClashMode" -> "{\"mode\":\"Rule\"}"
                else -> "{}"
            }
            handler.onMessage(message)
            return DashboardSubscription { handler.onClosed("") }
        }
        override fun close() {}
    }
}
