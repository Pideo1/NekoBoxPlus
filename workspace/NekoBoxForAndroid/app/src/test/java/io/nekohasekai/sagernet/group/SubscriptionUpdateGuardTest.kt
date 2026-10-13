package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SubscriptionUpdateGuardTest {
    @Test
    fun deletedGroupCannotStartARequest() = runBlocking {
        val guard = guard { null }
        assertFalse(guard.run(
            update = { error("Deleted group fetched") },
            onFailure = { _, _ -> error("Deleted group reported") },
        ))
    }

    @Test
    fun deletionWhileRequestIsRunningSuppresses404() = runBlocking {
        var current: ProxyGroup? = group()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val guard = guard { current }
        val update = async {
            guard.run(
                update = {
                    started.complete(Unit)
                    response.await()
                    throw IOException("HTTP 404")
                },
                onFailure = { _, _ -> error("Deleted group reported 404") },
            )
        }
        started.await()
        current = null
        response.complete(Unit)
        assertFalse(update.await())
    }

    @Test
    fun existingGroupStillReports404() = runBlocking {
        val current = group()
        val failure = IOException("HTTP 404")
        var reported: Throwable? = null
        assertFalse(guard { current }.run(
            update = { throw failure },
            onFailure = { group, error ->
                assertEquals(current, group)
                reported = error
            },
        ))
        assertEquals(failure, reported)
    }

    @Test
    fun convertedGroupsChangedLinksAndDisabledAutomaticUpdatesAreSkipped() = runBlocking {
        val converted = group().apply { type = GroupType.BASIC }
        val changed = group().apply { subscription!!.link = "https://new.example/sub" }
        val disabled = group().apply { subscription!!.autoUpdate = false }
        val missingSubscription = group().apply { subscription = null }
        for (current in listOf(converted, changed, disabled, missingSubscription)) {
            assertFalse(guard { current }.run(
                update = { error("Stale group fetched") },
                onFailure = { _, _ -> error("Stale group reported") },
            ))
        }
    }

    @Test
    fun changedLinkAndDisabledAutomaticUpdatesSuppressInFlightFailures() = runBlocking {
        for (change in listOf<(ProxyGroup) -> Unit>(
            { it.subscription!!.link = "https://new.example/sub" },
            { it.subscription!!.autoUpdate = false },
            { it.type = GroupType.BASIC },
        )) {
            val current = group()
            assertFalse(guard { current }.run(
                update = {
                    change(current)
                    throw IOException("HTTP 404")
                },
                onFailure = { _, _ -> error("Obsolete failure reported") },
            ))
        }
    }

    @Test
    fun manualUpdatesUseCurrentSettingsEvenWhenAutomaticUpdatesAreDisabled() = runBlocking {
        val current = group().apply {
            subscription!!.autoUpdate = false
            subscription!!.customUserAgent = "new-agent"
        }
        assertTrue(guard(byUser = true) { current }.run(
            update = { assertEquals("new-agent", it.subscription!!.customUserAgent) },
            onFailure = { _, _ -> error("Valid update failed") },
        ))
    }

    @Test
    fun deletionDuringTimeoutSuppressesFailure() = runBlocking {
        var current: ProxyGroup? = group()
        assertFalse(guard { current }.run(
            update = {
                withTimeout(1) {
                    current = null
                    awaitCancellation()
                }
            },
            onFailure = { _, _ -> error("Deleted group timeout reported") },
        ))
    }

    @Test
    fun validGroupTimeoutIsReported() = runBlocking {
        val current = group()
        var reported = false
        assertFalse(guard { current }.run(
            update = { withTimeout(1) { awaitCancellation() } },
            onFailure = { _, _ -> reported = true },
        ))
        assertTrue(reported)
    }

    @Test
    fun cancellationIsPropagatedWithoutReportingFailure() = runBlocking {
        val current = group()
        var cancelled = false
        try {
            guard { current }.run(
                update = { throw CancellationException("Cancelled") },
                onFailure = { _, _ -> error("Cancellation reported as error") },
            )
        } catch (error: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun guard(
        byUser: Boolean = false,
        loadGroup: suspend (Long) -> ProxyGroup?,
    ) = SubscriptionUpdateGuard(7L, "https://old.example/sub", byUser, loadGroup)

    private fun group() = ProxyGroup(
        id = 7L,
        type = GroupType.SUBSCRIPTION,
        subscription = SubscriptionBean().applyDefaultValues().apply {
            link = "https://old.example/sub"
            autoUpdate = true
        },
    )
}
