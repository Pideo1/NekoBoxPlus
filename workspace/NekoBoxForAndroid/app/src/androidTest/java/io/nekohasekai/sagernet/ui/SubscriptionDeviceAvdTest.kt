package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.group.DefaultSubscriptionReader
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.group.SubscriptionDeviceProfiles
import io.nekohasekai.sagernet.group.decodeSubscriptionDeviceHeaders
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.routing.SubscriptionRoutingRepository
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.utils.AppLocale
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.matsuri.nb4a.utils.HwidGenerator
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run with -Pandroid.testInstrumentationRunnerArguments.deviceIdServer=http://10.0.2.2:18765. */
@RunWith(AndroidJUnit4::class)
class SubscriptionDeviceAvdTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runId = UUID.randomUUID().toString()
    private var scenario: ActivityScenario<GroupSettingsActivity>? = null
    private val createdGroups = mutableListOf<Long>()
    private lateinit var server: String
    private var originalNightTheme = 0
    private var originalLanguage = ""
    private var originalLocales = LocaleListCompat.getEmptyLocaleList()

    @Before
    fun setUp() {
        val address = InstrumentationRegistry.getArguments().getString("deviceIdServer")
        assumeNotNull(address)
        server = requireNotNull(address).trimEnd('/')
        assertEquals("OK", controlGet("/health"))
        originalNightTheme = DataStore.nightTheme
        originalLanguage = DataStore.appLanguage
        instrumentation.runOnMainSync {
            originalLocales = if (Build.VERSION.SDK_INT >= 33) {
                LocaleListCompat.wrap(context.getSystemService(android.app.LocaleManager::class.java).applicationLocales)
            } else AppCompatDelegate.getApplicationLocales()
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        if (::server.isInitialized) {
            runBlocking(Dispatchers.IO) {
                createdGroups.forEach { GroupManager.deleteGroup(it) }
            }
            DataStore.nightTheme = originalNightTheme
            Theme.currentNightMode = originalNightTheme
            DataStore.appLanguage = originalLanguage
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(originalLocales)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                        android.os.LocaleList.forLanguageTags(originalLocales.toLanguageTags())
                }
                AppCompatDelegate.setDefaultNightMode(when (originalNightTheme) {
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    2 -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                })
            }
        }
    }

    @Test
    fun defaultsEditingSendingValidationAndPersistence() {
        val id = createGroup()
        launch(id)
        scrollTo(R.string.subscription_device_id)
        compose.onNodeWithText(label(R.string.subscription_device_id)).assertIsDisplayed()
        assertFalse(DataStore.subscriptionHwidEnabled)
        assertFalse(DataStore.subscriptionSpoofDevice)
        assertEquals(HwidGenerator.generate(context), DataStore.subscriptionHwid)
        scrollTo(R.string.subscription_hwid_value)
        compose.onNodeWithText(DataStore.subscriptionHwid).assertIsDisplayed()
        screenshot("default-disabled")
        save()
        assertNoDeviceHeaders(capture(stored(id), "default-disabled"))

        launch(id)
        edit(R.string.subscription_hwid_value, " custom-device-a ")
        save()
        assertNull(capture(stored(id), "override-disabled")["x-hwid"])
        launch(id)
        assertEquals(" custom-device-a ", DataStore.subscriptionHwid)
        clickRow(R.string.hwid_support)
        save()
        val enabled = capture(stored(id), "override-enabled")
        assertEquals("custom-device-a", enabled["x-hwid"])
        assertEquals("${Build.MANUFACTURER} ${Build.MODEL}".trim(), enabled["x-device-model"])
        assertEquals(Build.VERSION.SDK_INT.toString(), enabled["x-ver-os"])

        launch(id)
        edit(R.string.subscription_hwid_value, " ")
        clickApply()
        compose.waitUntil(10_000) { nodesWithText(label(R.string.subscription_hwid_empty)) }
        screenshot("empty-enabled-validation")
        compose.onNodeWithText(label(android.R.string.ok)).performClick()
        assertEquals(" custom-device-a ", stored(id).hwid)
        clickRow(R.string.hwid_support)
        save()
        assertNull(capture(stored(id), "blank-disabled")["x-hwid"])
        launch(id)
        assertEquals(" ", DataStore.subscriptionHwid)
        scenario?.close()
    }

    @Test
    fun allAppFingerprintsAndAppChanges() {
        val id = createGroup()
        launch(id)
        clickRow(R.string.hwid_support)
        for (app in listOf(SpoofApp.HAPP, SpoofApp.V2RAY_TUN, SpoofApp.INCY)) {
            selectApp(app)
            val generated = DataStore.subscriptionHwid
            assertEquals(HwidGenerator.generate(context, app), generated)
            when (app) {
                SpoofApp.INCY -> assertTrue(generated.matches(Regex("[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}")))
                SpoofApp.V2RAY_TUN -> assertTrue(generated.matches(Regex("[0-9A-F]{16}")))
                else -> assertTrue(generated.matches(Regex("[0-9a-f]{16}")))
            }
            assertFalse(DataStore.subscriptionSpoofDevice)
            save()
            val headers = capture(stored(id), "app-$app")
            assertEquals(generated, headers["x-hwid"])
            assertEquals("Android", headers["x-device-os"])
            val fullModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            assertEquals(if (app == SpoofApp.HAPP) Build.MODEL else fullModel, headers["x-device-model"])
            assertEquals(if (app == SpoofApp.V2RAY_TUN) "Android ${Build.VERSION.SDK_INT}" else Build.VERSION.SDK_INT.toString(), headers["x-ver-os"])
            when (app) {
                SpoofApp.HAPP -> {
                    assertTrue(headers.getValue("user-agent").startsWith("Happ/"))
                    assertNull(headers["x-app-version"])
                }
                SpoofApp.V2RAY_TUN -> {
                    assertEquals("v2raytun/android", headers["user-agent"])
                    assertEquals("5.25.80", headers["x-app-version"])
                }
                SpoofApp.INCY -> {
                    assertTrue(headers.getValue("user-agent").startsWith("INCY/"))
                    assertEquals("INCY", headers["x-client"])
                    assertEquals("*/*", headers["accept"])
                    assertEquals("3.4.3", headers["x-app-version"])
                    assertNotNull(headers["accept-language"])
                }
            }
            launch(id)
            edit(R.string.subscription_hwid_value, "manual-$app")
            edit(R.string.subscription_user_agent, "Manual/$app")
            clickRow(R.string.subscription_spoof_device)
            assertTrue(DataStore.subscriptionSpoofDevice)
            selectApp(if (app == SpoofApp.HAPP) SpoofApp.V2RAY_TUN else SpoofApp.HAPP)
            assertFalse(DataStore.subscriptionSpoofDevice)
            assertEquals("", DataStore.subscriptionDevicePreset)
            assertTrue(DataStore.subscriptionHwidEnabled)
            assertFalse(DataStore.subscriptionHwid.startsWith("manual-"))
            assertFalse(DataStore.subscriptionUserAgent.startsWith("Manual/"))
        }
        selectApp(SpoofApp.NONE)
        clickRow(R.string.hwid_support)
        selectApp(SpoofApp.HAPP)
        compose.onNodeWithText(label(R.string.spoof_app_without_hwid_message)).assertIsDisplayed()
        compose.onNodeWithText(label(android.R.string.ok)).performClick()
        save()
        val disabled = capture(stored(id), "app-without-hwid")
        assertNull(disabled["x-hwid"])
        assertEquals("Android", disabled["x-device-os"])
    }

    @Test
    fun deviceProfilesRandomSelectionFilteringAndRealDeviceReset() {
        val id = createGroup()
        launch(id)
        clickRow(R.string.hwid_support)
        selectApp(SpoofApp.HAPP)
        edit(R.string.subscription_hwid_value, "profile-identity")
        edit(R.string.subscription_user_agent, "Profile/UserAgent")
        clickRow(R.string.subscription_spoof_device)
        assertTrue(DataStore.subscriptionSpoofDevice)
        assertTrue(SubscriptionDeviceProfiles.forApp(SpoofApp.HAPP).any { it.id == DataStore.subscriptionDevicePreset })
        selectProfile("ios_iPhone17_3_18")
        assertEquals("profile-identity", DataStore.subscriptionHwid)
        assertEquals("Profile/UserAgent", DataStore.subscriptionUserAgent)
        screenshot("happ-ios-profile")
        save()
        val ios = capture(stored(id), "ios-profile")
        assertEquals("profile-identity", ios["x-hwid"])
        assertEquals("Profile/UserAgent", ios["user-agent"])
        assertEquals("iPhone17,3", ios["x-device-model"])
        assertEquals("iOS", ios["x-device-os"])
        assertEquals("18.0", ios["x-ver-os"])
        launch(id)
        assertEquals("ios_iPhone17_3_18", DataStore.subscriptionDevicePreset)
        clickRow(R.string.subscription_device_profile)
        dialogChoice(label(R.string.subscription_device_random))
        val randomId = DataStore.subscriptionDevicePreset
        val randomHeaders = decodeSubscriptionDeviceHeaders(DataStore.subscriptionDeviceHeaders)
        assertEquals("profile-identity", DataStore.subscriptionHwid)
        save()
        val randomFirst = capture(stored(id), "random-1")
        val randomSecond = capture(stored(id), "random-2")
        assertEquals(randomFirst, randomSecond)
        randomHeaders!!.forEach { (name, value) -> assertEquals(value, randomFirst[name.lowercase()]) }
        assertEquals("profile-identity", randomFirst["x-hwid"])
        launch(id)
        assertEquals(randomId, DataStore.subscriptionDevicePreset)
        assertEquals(randomHeaders, decodeSubscriptionDeviceHeaders(DataStore.subscriptionDeviceHeaders))
        clickRow(R.string.subscription_spoof_device)
        assertFalse(DataStore.subscriptionSpoofDevice)
        assertEquals("profile-identity", DataStore.subscriptionHwid)
        assertEquals("Profile/UserAgent", DataStore.subscriptionUserAgent)
        save()
        val real = capture(stored(id), "real-device-restored")
        assertEquals(Build.MODEL, real["x-device-model"])
        assertEquals("Android", real["x-device-os"])
        assertEquals(Build.VERSION.SDK_INT.toString(), real["x-ver-os"])
        for (app in listOf(SpoofApp.NONE, SpoofApp.V2RAY_TUN, SpoofApp.INCY)) {
            launch(id)
            selectApp(app)
            clickRow(R.string.subscription_spoof_device)
            clickRow(R.string.subscription_device_profile)
            compose.onNodeWithText("iPhone17,3 · iOS 18.0").assertDoesNotExist()
            dialogChoice("Pixel 10 Pro · Android 16")
            save()
            val android = capture(stored(id), "android-profile-$app")
            assertEquals(if (app == SpoofApp.V2RAY_TUN) "Android 16" else "16", android["x-ver-os"])
            assertEquals("Google Pixel 10 Pro", android["x-device-model"])
            assertEquals("Android", android["x-device-os"])
        }
        launch(id)
        clickRow(R.string.hwid_support)
        compose.onNodeWithText(label(R.string.spoof_app_without_hwid_message)).assertIsDisplayed()
        compose.onNodeWithText(label(android.R.string.ok)).performClick()
        save()
        val withoutHwid = capture(stored(id), "device-profile-without-hwid")
        assertNull(withoutHwid["x-hwid"])
        assertEquals("Google Pixel 10 Pro", withoutHwid["x-device-model"])
    }

    @Test
    fun editingAndClearingEveryHeaderSurvivesRefreshAndRoutingUsesDraft() {
        val id = createGroup()
        launch(id)
        clickRow(R.string.hwid_support)
        selectApp(SpoofApp.INCY)
        edit(R.string.subscription_hwid_value, "saved-hwid")
        edit(R.string.subscription_user_agent, "Saved/UserAgent")
        val titles = headerTitles()
        titles.entries.forEachIndexed { index, (_, title) -> edit(title, " custom-$index ") }
        save()
        val custom = capture(stored(id), "all-custom-headers")
        titles.keys.forEachIndexed { index, name -> assertEquals("custom-$index", custom[name.lowercase()]) }
        assertEquals("saved-hwid", custom["x-hwid"])
        val group = storedGroup(id)
        group.subscription!!.link = "$server/valid/$runId-refresh"
        SagerDatabase.groupDao.updateGroup(group)
        runBlocking(Dispatchers.IO) { withTimeout(20_000) { RawUpdater.doUpdate(group, group.subscription!!, null, true) } }
        val refreshed = stored(id)
        assertEquals("saved-hwid", refreshed.hwid)
        assertEquals("Saved/UserAgent", refreshed.customUserAgent)
        assertEquals(123L, io.nekohasekai.sagernet.utils.parseSubscriptionUserinfo(refreshed.subscriptionUserinfo)?.download)
        titles.keys.forEachIndexed { index, name ->
            assertEquals(" custom-$index ", decodeSubscriptionDeviceHeaders(refreshed.deviceHeaders)?.get(name))
        }
        launch(id)
        edit(R.string.subscription_hwid_value, "draft-hwid")
        edit(R.string.subscription_user_agent, "Draft/UserAgent")
        edit(R.string.group_subscription_link, "$server/empty/$runId-routing-draft")
        clickRow(R.string.subscription_import_routing)
        val draft = awaitHeaders("/empty/$runId-routing-draft")
        assertEquals("draft-hwid", draft["x-hwid"])
        assertEquals("Draft/UserAgent", draft["user-agent"])
        assertEquals("saved-hwid", stored(id).hwid)
        compose.waitForIdle()
        scenario?.close()
        scenario = null
        launch(id)
        titles.values.forEach { edit(it, "") }
        edit(R.string.subscription_user_agent, "")
        save()
        val cleared = capture(stored(id), "all-cleared-headers")
        titles.keys.forEach { assertNull("$it must remain omitted", cleared[it.lowercase()]) }
        assertEquals("saved-hwid", cleared["x-hwid"])
        assertFalse(cleared.getValue("user-agent").startsWith("INCY/"))
        launch(id)
        assertEquals("", DataStore.subscriptionUserAgent)
        assertTrue(decodeSubscriptionDeviceHeaders(DataStore.subscriptionDeviceHeaders)!!.values.all { it.isEmpty() })
        scenario?.close()
        scenario = null
        val routingGroup = storedGroup(id)
        routingGroup.subscription!!.link = "$server/empty/$runId-routing-saved"
        runBlocking(Dispatchers.IO) { withTimeout(20_000) { SubscriptionRoutingRepository.fetchFromSubscription(routingGroup) } }
        val routing = awaitHeaders("/empty/$runId-routing-saved")
        assertEquals("saved-hwid", routing["x-hwid"])
        titles.keys.forEach { assertNull(routing[it.lowercase()]) }
    }

    @Test
    fun subscriptionIsolationAndCancelledEdits() {
        val first = createGroup()
        val second = createGroup()
        launch(first)
        clickRow(R.string.hwid_support)
        edit(R.string.subscription_hwid_value, "first-hwid")
        clickRow(R.string.subscription_spoof_device)
        val firstProfile = DataStore.subscriptionDevicePreset
        save()
        launch(second)
        assertFalse(DataStore.subscriptionHwidEnabled)
        assertFalse(DataStore.subscriptionSpoofDevice)
        assertNotEquals("first-hwid", DataStore.subscriptionHwid)
        clickRow(R.string.hwid_support)
        edit(R.string.subscription_hwid_value, "second-hwid")
        save()
        assertEquals("first-hwid", capture(stored(first), "isolation-first")["x-hwid"])
        assertEquals("second-hwid", capture(stored(second), "isolation-second")["x-hwid"])
        launch(first)
        assertEquals(firstProfile, DataStore.subscriptionDevicePreset)
        clickRow(R.string.subscription_hwid_value)
        compose.onNode(hasSetTextAction()).performTextReplacement("cancelled-hwid")
        compose.onNodeWithText(label(android.R.string.cancel)).performClick()
        assertEquals("first-hwid", DataStore.subscriptionHwid)
        edit(R.string.subscription_hwid_value, "discarded-hwid")
        compose.onNodeWithContentDescription(label(android.R.string.cancel)).performClick()
        compose.onNodeWithText(label(R.string.no)).performClick()
        compose.waitUntil(10_000) { scenario!!.state == Lifecycle.State.DESTROYED }
        assertEquals("first-hwid", stored(first).hwid)
    }

    @Test
    fun recreationRussianDarkLandscapeAndSharing() {
        val id = createGroup()
        launch(id)
        clickRow(R.string.hwid_support)
        edit(R.string.subscription_hwid_value, "recreated-hwid")
        scenario!!.recreate()
        awaitEditor()
        assertTrue("HWID sending must survive recreation", DataStore.subscriptionHwidEnabled)
        assertEquals("recreated-hwid", DataStore.subscriptionHwid)
        save()
        launch(id)
        instrumentation.runOnMainSync {
            DataStore.nightTheme = 1
            Theme.currentNightMode = 1
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            DataStore.appLanguage = "ru"
            AppLocale.apply()
        }
        awaitEditor()
        scenario!!.onActivity {
            assertEquals(Configuration.UI_MODE_NIGHT_YES, it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            assertEquals("ru", it.resources.configuration.locales[0].language)
        }
        scrollTo(R.string.subscription_device_id)
        screenshot("russian-dark-portrait")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(15_000) {
            var landscape = false
            scenario!!.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            landscape
        }
        awaitEditor()
        scrollTo(R.string.subscription_hwid_value)
        compose.onNodeWithText("recreated-hwid").assertIsDisplayed()
        screenshot("russian-dark-landscape")
        save()
        assertEquals("recreated-hwid", capture(stored(id), "recreated-saved")["x-hwid"])
        val exported = storedGroup(id).apply { export = true }
        val shared = KryoConverters.deserialize(ProxyGroup().apply { export = true }, KryoConverters.serialize(exported))
        assertFalse(shared.subscription!!.hwidEnabled)
        assertNull(shared.subscription!!.hwid)
        assertFalse(shared.subscription!!.spoofDevice)
        val basic = createGroup(GroupType.BASIC)
        launch(basic)
        compose.onNodeWithText(label(R.string.subscription_device_id)).assertDoesNotExist()
    }

    private fun createGroup(type: Int = GroupType.SUBSCRIPTION): Long = runBlocking(Dispatchers.IO) {
        GroupManager.createGroup(ProxyGroup(name = "Local Device ID AVD test", type = type).apply {
            if (type == GroupType.SUBSCRIPTION) subscription = SubscriptionBean().applyDefaultValues().apply {
                link = "$server/empty/$runId-initial-${createdGroups.size}"
            }
        }).id.also(createdGroups::add)
    }

    private fun storedGroup(id: Long) = requireNotNull(SagerDatabase.groupDao.getById(id))
    private fun stored(id: Long) = requireNotNull(storedGroup(id).subscription)

    private fun launch(id: Long) {
        scenario?.close()
        scenario = ActivityScenario.launch(Intent(context, GroupSettingsActivity::class.java)
            .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, id))
        awaitEditor()
    }

    private fun awaitEditor() {
        compose.waitUntil(15_000) { runCatching { nodesWithText(label(R.string.group_settings)) }.getOrDefault(false) }
        compose.waitForIdle()
    }

    private fun label(resId: Int): String {
        var value = context.getString(resId)
        scenario?.let { if (it.state != Lifecycle.State.DESTROYED) it.onActivity { activity -> value = activity.getString(resId) } }
        return value
    }

    private fun nodesWithText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun scrollTo(resId: Int) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(resId)))
    }
    private fun clickRow(resId: Int) {
        scrollTo(resId)
        compose.onNode(hasText(label(resId)) and hasClickAction()).performClick()
    }
    private fun edit(resId: Int, value: String) {
        clickRow(resId)
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
        compose.onNodeWithText(label(android.R.string.ok)).performClick()
    }
    private fun selectApp(app: Int) {
        clickRow(R.string.spoof_app)
        dialogChoice(context.resources.getStringArray(R.array.spoof_app_modes)[app])
    }
    private fun selectProfile(id: String) {
        clickRow(R.string.subscription_device_profile)
        dialogChoice(SubscriptionDeviceProfiles.all.first { it.id == id }.label)
    }
    private fun dialogChoice(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction()).onLast()
            .performScrollTo().performClick()
    }
    private fun clickApply() = compose.onNodeWithContentDescription(label(R.string.apply)).performClick()
    private fun save() {
        clickApply()
        compose.waitUntil(20_000) { scenario!!.state == Lifecycle.State.DESTROYED }
        scenario!!.close()
        scenario = null
    }

    private fun capture(subscription: SubscriptionBean, name: String): Map<String, String> {
        val path = "/empty/$runId-$name"
        subscription.link = "$server$path"
        val document = runBlocking(Dispatchers.IO) { withTimeout(20_000) { DefaultSubscriptionReader.read(subscription) } }
        assertEquals("", document.body)
        return awaitHeaders(path)
    }
    private fun awaitHeaders(path: String): Map<String, String> {
        var headers: Map<String, String>? = null
        compose.waitUntil(20_000) {
            val records = JSONArray(controlGet("/records?path=${URLEncoder.encode(path, "UTF-8")}"))
            if (records.length() > 0) {
                val json = records.getJSONObject(records.length() - 1).getJSONObject("headers")
                headers = json.keys().asSequence().associateWith(json::getString)
            }
            headers != null
        }
        return requireNotNull(headers)
    }
    private fun controlGet(path: String): String {
        val connection = URL("$server$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
    }
    private fun assertNoDeviceHeaders(headers: Map<String, String>) {
        assertTrue(headers.keys.none { it.startsWith("x-device-") || it == "x-hwid" || it == "x-ver-os" })
    }
    private fun headerTitles() = linkedMapOf(
        "Accept" to R.string.subscription_header_accept,
        "Accept-Language" to R.string.subscription_header_accept_language,
        "X-Client" to R.string.subscription_header_client,
        "X-Device-Locale" to R.string.subscription_header_locale,
        "X-App-Version" to R.string.subscription_header_app_version,
        "X-Device-Model" to R.string.subscription_header_model,
        "X-Ver-Os" to R.string.subscription_header_os_version,
        "X-Device-Os" to R.string.subscription_header_os,
    )

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "device-id-avd").apply { mkdirs() }
        var activeRoot: AccessibilityNodeInfo? = null
        compose.waitUntil(10_000) {
            activeRoot = instrumentation.uiAutomation.rootInActiveWindow
            activeRoot?.packageName?.toString() == context.packageName
        }
        val root = requireNotNull(activeRoot)
        assertEquals("The app must be visible without an emulator system dialog", context.packageName, root.packageName?.toString())
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(directory, "$name.xml").outputStream().use { output ->
            val xml = Xml.newSerializer()
            xml.setOutput(output, "UTF-8")
            xml.startDocument("UTF-8", true)
            fun node(info: AccessibilityNodeInfo) {
                xml.startTag("", "node")
                xml.attribute("", "text", info.text?.toString().orEmpty())
                xml.attribute("", "description", info.contentDescription?.toString().orEmpty())
                val rect = Rect().also(info::getBoundsInScreen)
                xml.attribute("", "bounds", rect.toShortString())
                for (index in 0 until info.childCount) info.getChild(index)?.let(::node)
                xml.endTag("", "node")
            }
            node(root)
            xml.endDocument()
        }
        // Preserve only these synthetic test artifacts when Gradle uninstalls the test app.
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        shell("mkdir -p /sdcard/Download/nb4a-device-id-avd")
        for (extension in listOf("png", "xml")) {
            shell("cp ${directory.absolutePath}/$name.$extension /sdcard/Download/nb4a-device-id-avd/$name.$extension")
        }
    }
}
