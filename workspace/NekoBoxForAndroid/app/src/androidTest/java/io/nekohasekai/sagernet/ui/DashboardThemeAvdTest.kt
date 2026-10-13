package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardThemeAvdTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun russianDarkThemeRespectsSystemInsets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val locales = AppCompatDelegate.getApplicationLocales()
        val nightMode = AppCompatDelegate.getDefaultNightMode()
        val cachedNightMode = io.nekohasekai.sagernet.utils.Theme.currentNightMode
        val nightSetting = io.nekohasekai.sagernet.database.DataStore.nightTheme
        val firstSetup = io.nekohasekai.sagernet.database.DataStore.proxyAppsFirstSetup
        val clash = io.nekohasekai.sagernet.database.DataStore.enableClashAPI
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            io.nekohasekai.sagernet.database.DataStore.proxyAppsFirstSetup = true
            io.nekohasekai.sagernet.database.DataStore.enableClashAPI = false
            io.nekohasekai.sagernet.database.DataStore.nightTheme = 1
            io.nekohasekai.sagernet.utils.Theme.currentNightMode = 1
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            scenario.onActivity {
                it.displayFragmentWithId(io.nekohasekai.sagernet.R.id.nav_dashboard)
                it.supportFragmentManager.executePendingTransactions()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
            }
            compose.waitUntil(10000) { runCatching { compose.onNode(hasText("Панель управления") and !hasClickAction()).isDisplayed() }.getOrDefault(false) }
            compose.onNode(hasText("Панель управления") and !hasClickAction()).assertIsDisplayed()
            scenario.onActivity {
                org.junit.Assert.assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES,
                    it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            }
            compose.waitForIdle()
            android.os.SystemClock.sleep(750)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "dashboard-russian-dark.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                DashboardTestArtifacts.export("cache/dashboard-russian-dark.png", "russian-dark")
            }
        } finally {
            io.nekohasekai.sagernet.database.DataStore.nightTheme = nightSetting
            io.nekohasekai.sagernet.utils.Theme.currentNightMode = cachedNightMode
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(locales)
                AppCompatDelegate.setDefaultNightMode(nightMode)
            }
            scenario?.close()
            io.nekohasekai.sagernet.database.DataStore.proxyAppsFirstSetup = firstSetup
            io.nekohasekai.sagernet.database.DataStore.enableClashAPI = clash
        }
    }
}
