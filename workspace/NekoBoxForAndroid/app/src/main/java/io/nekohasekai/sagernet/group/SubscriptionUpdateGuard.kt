package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class SubscriptionUpdateGuard(
    private val groupId: Long,
    private val expectedLink: String,
    private val byUser: Boolean,
    private val loadGroup: suspend (Long) -> ProxyGroup?,
) {
    suspend fun currentGroup(): ProxyGroup? {
        val group = loadGroup(groupId) ?: return null
        if (group.type != GroupType.SUBSCRIPTION) return null
        val subscription = group.subscription ?: return null
        if (subscription.link != expectedLink || (!byUser && !subscription.autoUpdate)) return null
        return group
    }

    suspend fun run(
        update: suspend (ProxyGroup) -> Unit,
        onFailure: suspend (ProxyGroup, Throwable) -> Unit,
    ): Boolean {
        coroutineContext.ensureActive()
        val group = currentGroup() ?: return false
        try {
            update(group)
            return true
        } catch (error: Throwable) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            coroutineContext.ensureActive()
            val current = currentGroup() ?: return false
            onFailure(current, error)
            return false
        }
    }
}
