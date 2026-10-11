package io.nekohasekai.sagernet.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.ui.dashboard.*
import libcore.DashboardStreamHandler
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises JNI-like blocking calls and session replacement on Android's main dispatcher. */
@RunWith(AndroidJUnit4::class)
class DashboardRetryAvdTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val bridges = mutableListOf<ProbeBridge>()
    private lateinit var model: DashboardViewModel
    private val proxy = DashboardProxy("probe", "socks")

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (true) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Dashboard condition timed out" }
            android.os.SystemClock.sleep(20)
        }
    }
    private fun start() {
        main {
            model = DashboardViewModel { ProbeBridge().also { bridges.add(it) } }
            model.start()
        }
        await { model.state.available }
    }
    @After fun cleanup() {
        bridges.forEach { it.release.countDown() }
        if (::model.isInitialized) main { model.stop() }
    }

    @Test fun repeatedFailureThenSuccessCanRetryWithoutReconnect() {
        start()
        val bridge = bridges.single()
        repeat(3) { attempt ->
            main { model.test(listOf(proxy), "single:probe") }
            await { bridge.calls.get() == attempt + 1 && model.state.progress.isEmpty() }
            main {
                assertTrue(model.state.testing.isEmpty())
                assertEquals("fixture probe failure", model.state.testErrors[proxy.tag])
                assertTrue(model.state.available)
            }
        }
        bridge.fail = false
        main { model.test(listOf(proxy), "single:probe") }
        await { bridge.calls.get() == 4 && model.state.progress.isEmpty() }
        main { assertFalse(model.state.testErrors.containsKey(proxy.tag)) }
        assertEquals(1, bridges.size)
    }

    @Test fun stoppingSessionClearsBusyStateAndIgnoresOldResults() {
        start()
        val old = bridges.single().apply { block = true }
        main { model.test(listOf(proxy), "single:probe") }
        assertTrue(old.entered.await(5, TimeUnit.SECONDS))
        main {
            model.stop(stopped = false)
            assertTrue("Stopped session retained busy profile", model.state.testing.isEmpty())
            assertTrue("Stopped session retained batch progress", model.state.progress.isEmpty())
            model.start()
        }
        await { model.state.available && bridges.size == 2 }
        bridges.last().fail = false
        main { model.test(listOf(proxy), "single:probe") }
        await { bridges.last().calls.get() == 1 && model.state.progress.isEmpty() }
        old.release.countDown()
        assertTrue(old.returned.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        main {
            assertTrue(model.state.testing.isEmpty())
            assertTrue(model.state.progress.isEmpty())
            assertFalse(model.state.testErrors.containsKey(proxy.tag))
        }
    }

    @Test fun overlappingSingleAndGroupTestsDoNotDuplicateProfiles() {
        start()
        val bridge = bridges.single().apply { block = true; fail = false }
        main {
            model.test(listOf(proxy), "group")
            model.test(listOf(proxy), "single:probe")
        }
        assertTrue(bridge.entered.await(5, TimeUnit.SECONDS))
        bridge.release.countDown()
        await { model.state.progress.isEmpty() }
        assertEquals(1, bridge.calls.get())
    }

    @Test fun streamFailureReconnectsAutomaticallyAndKeepsTestsUsable() {
        start()
        main { bridges.single().handlers.getValue("SubscribeStatus").onClosed("fixture transport disconnected") }
        await { bridges.size == 2 && model.state.available }
        bridges.last().fail = false
        main { model.test(listOf(proxy), "single:probe") }
        await { bridges.last().calls.get() == 1 && model.state.progress.isEmpty() }
        main { assertTrue(model.state.testing.isEmpty()) }
    }

    private class ProbeBridge : DashboardBridge {
        val calls = AtomicInteger()
        val handlers = ConcurrentHashMap<String, DashboardStreamHandler>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        @Volatile var fail = true
        @Volatile var block = false
        override fun call(method: String, request: String, timeoutMillis: Int): String {
            if (method != "TestOutbound") return "{}"
            calls.incrementAndGet()
            entered.countDown()
            if (block) check(release.await(10, TimeUnit.SECONDS))
            returned.countDown()
            if (fail) throw IOException("fixture probe failure")
            return JSONObject().put("latency", 12).toString()
        }
        override fun stream(method: String, request: String, handler: DashboardStreamHandler): DashboardSubscription {
            handlers[method] = handler
            handler.onMessage("{}")
            return DashboardSubscription { }
        }
        override fun close() { }
    }
}
