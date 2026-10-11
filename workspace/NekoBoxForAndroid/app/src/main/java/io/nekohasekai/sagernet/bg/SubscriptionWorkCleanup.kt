package io.nekohasekai.sagernet.bg

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.multiprocess.RemoteWorkManager
import com.google.common.util.concurrent.ListenableFuture
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val SUBSCRIPTION_UPDATE_WORK_NAME = "SubscriptionUpdater"

internal fun ProxyGroup.needsAutomaticSubscriptionUpdates(): Boolean {
    if (type != GroupType.SUBSCRIPTION) return false
    val subscription = subscription ?: return false
    return subscription.autoUpdate ||
        (subscription.routingEnabled && subscription.autoRoutingUrl.isNotBlank())
}

internal interface SubscriptionWorkScheduler {
    suspend fun unfinishedSubscriptionWorkIds(): Set<UUID>
    suspend fun canonicalWorkIds(): Set<UUID>
    suspend fun cancel(id: UUID)
    suspend fun cancelCanonicalWork()
    suspend fun schedule(schedule: SubscriptionUpdateSchedulePolicy.Schedule)
}

internal suspend fun cleanupSubscriptionWork(
    scheduler: SubscriptionWorkScheduler,
    hasAutomaticUpdates: suspend () -> Boolean,
) {
    val subscriptionWork = scheduler.unfinishedSubscriptionWorkIds()
    val canonicalWork = scheduler.canonicalWorkIds()
    val retainedWork = if (hasAutomaticUpdates()) canonicalWork else emptySet()
    for (id in subscriptionWork - retainedWork) {
        scheduler.cancel(id)
    }
}

internal object RemoteSubscriptionWorkScheduler : SubscriptionWorkScheduler {
    private val manager get() = RemoteWorkManager.getInstance(app)
    private val unfinishedStates = listOf(
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.RUNNING,
        WorkInfo.State.BLOCKED,
    )

    override suspend fun unfinishedSubscriptionWorkIds(): Set<UUID> = manager.getWorkInfos(
        WorkQuery.Builder.fromTags(listOf(SubscriptionUpdater.UpdateTask::class.java.name))
            .addStates(unfinishedStates).build(),
    ).awaitResult().mapTo(mutableSetOf()) { it.id }

    override suspend fun canonicalWorkIds(): Set<UUID> = manager.getWorkInfos(
        WorkQuery.Builder.fromUniqueWorkNames(listOf(SUBSCRIPTION_UPDATE_WORK_NAME))
            .addStates(unfinishedStates).build(),
    ).awaitResult().mapTo(mutableSetOf()) { it.id }

    override suspend fun cancel(id: UUID) {
        manager.cancelWorkById(id).awaitResult()
    }

    override suspend fun cancelCanonicalWork() {
        manager.cancelUniqueWork(SUBSCRIPTION_UPDATE_WORK_NAME).awaitResult()
    }

    override suspend fun schedule(schedule: SubscriptionUpdateSchedulePolicy.Schedule) {
        val request = PeriodicWorkRequest.Builder(
            SubscriptionUpdater.UpdateTask::class.java,
            schedule.intervalMinutes,
            TimeUnit.MINUTES,
        ).apply {
            if (schedule.initialDelaySeconds > 0) {
                setInitialDelay(schedule.initialDelaySeconds, TimeUnit.SECONDS)
            }
        }.build()
        manager.enqueueUniquePeriodicWork(
            SUBSCRIPTION_UPDATE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        ).awaitResult()
    }
}

private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(false) }
    addListener({
        try {
            continuation.resume(get())
        } catch (error: ExecutionException) {
            continuation.resumeWithException(error.cause ?: error)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }, Executor { it.run() })
}
