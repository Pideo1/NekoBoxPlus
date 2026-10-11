package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SubscriptionWorkCleanupTest {
    @Test
    fun removesAllSubscriptionWorkWhenNoAutomaticUpdatesRemain() = runBlocking {
        val scheduler = FakeScheduler()
        cleanupSubscriptionWork(scheduler) { false }
        assertEquals(listOf(scheduler.canonical, scheduler.stray), scheduler.cancelled)
        assertTrue(scheduler.work.isEmpty())
        assertFalse(scheduler.cancelled.contains(scheduler.unrelated))
    }

    @Test
    fun preservesCanonicalScheduleAndCancelsStrayWork() = runBlocking {
        val scheduler = FakeScheduler()
        cleanupSubscriptionWork(scheduler) { true }
        assertEquals(listOf(scheduler.stray), scheduler.cancelled)
        assertEquals(setOf(scheduler.canonical), scheduler.work)
    }

    @Test
    fun repeatedCleanupDoesNotCancelMoreWork() = runBlocking {
        val scheduler = FakeScheduler()
        repeat(2) { cleanupSubscriptionWork(scheduler) { true } }
        assertEquals(listOf(scheduler.stray), scheduler.cancelled)
    }

    @Test
    fun routingOnlySubscriptionKeepsCanonicalSchedule() = runBlocking {
        val group = subscriptionGroup().apply {
            subscription!!.routingEnabled = true
            subscription!!.autoRoutingUrl = "https://routing.example/profile"
        }
        val scheduler = FakeScheduler()
        cleanupSubscriptionWork(scheduler) { group.needsAutomaticSubscriptionUpdates() }
        assertEquals(setOf(scheduler.canonical), scheduler.work)
    }

    @Test
    fun onlyValidAutomaticSubscriptionsNeedASchedule() {
        val group = subscriptionGroup()
        assertFalse(group.needsAutomaticSubscriptionUpdates())
        group.subscription!!.autoUpdate = true
        assertTrue(group.needsAutomaticSubscriptionUpdates())
        group.subscription!!.autoUpdate = false
        group.subscription!!.routingEnabled = true
        assertFalse(group.needsAutomaticSubscriptionUpdates())
        group.subscription!!.autoRoutingUrl = "https://routing.example/profile"
        assertTrue(group.needsAutomaticSubscriptionUpdates())
        group.type = GroupType.BASIC
        assertFalse(group.needsAutomaticSubscriptionUpdates())
        group.type = GroupType.SUBSCRIPTION
        group.subscription = null
        assertFalse(group.needsAutomaticSubscriptionUpdates())
    }

    @Test
    fun waitsForCancellationBeforeCompletingWithoutBlockingCaller() = runBlocking {
        val scheduler = FakeScheduler()
        val cancellation = CompletableDeferred<Unit>()
        scheduler.beforeCancel = { cancellation.await() }
        val cleanup = async { cleanupSubscriptionWork(scheduler) { true } }
        yield()
        assertFalse(cleanup.isCompleted)
        assertTrue(scheduler.cancelled.isEmpty())
        cancellation.complete(Unit)
        cleanup.await()
        assertEquals(listOf(scheduler.stray), scheduler.cancelled)
    }

    @Test
    fun checksCurrentEligibilityAfterQueryingWork() = runBlocking {
        val scheduler = FakeScheduler()
        var hasUpdates = true
        scheduler.afterCanonicalQuery = { hasUpdates = false }
        cleanupSubscriptionWork(scheduler) { hasUpdates }
        assertTrue(scheduler.work.isEmpty())
    }

    private fun subscriptionGroup() = ProxyGroup(
        id = 7L,
        type = GroupType.SUBSCRIPTION,
        subscription = SubscriptionBean().applyDefaultValues().apply {
            autoUpdate = false
            routingEnabled = false
            autoRoutingUrl = ""
        },
    )

    private class FakeScheduler : SubscriptionWorkScheduler {
        val canonical = UUID.randomUUID()
        val stray = UUID.randomUUID()
        val unrelated = UUID.randomUUID()
        val work = linkedSetOf(canonical, stray)
        val cancelled = mutableListOf<UUID>()
        var beforeCancel: suspend () -> Unit = {}
        var afterCanonicalQuery: () -> Unit = {}

        override suspend fun unfinishedSubscriptionWorkIds() = work.toSet()
        override suspend fun canonicalWorkIds(): Set<UUID> {
            afterCanonicalQuery()
            return setOf(canonical).intersect(work)
        }

        override suspend fun cancel(id: UUID) {
            beforeCancel()
            cancelled += id
            work -= id
        }

        override suspend fun cancelCanonicalWork() = error("Unexpected schedule cancellation")
        override suspend fun schedule(schedule: SubscriptionUpdateSchedulePolicy.Schedule) =
            error("Unexpected scheduling")
    }
}
