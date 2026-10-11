package io.nekohasekai.sagernet.ui.dashboard

import libcore.DashboardStreamHandler
import libcore.Libcore
import java.util.concurrent.atomic.AtomicBoolean

/** Small injectable boundary; dashboard state and UI tests do not require JNI. */
interface DashboardBridge {
    fun call(method: String, request: String, timeoutMillis: Int): String
    fun stream(method: String, request: String, handler: DashboardStreamHandler): DashboardSubscription
    fun close()
}
fun interface DashboardSubscription { fun close() }
internal class NativeDashboardBridge(path: String) : DashboardBridge {
    private val client = Libcore.newDashboardClient(path)
    private val closed = AtomicBoolean()
    override fun call(method: String, request: String, timeoutMillis: Int): String = client.call(method, request, timeoutMillis)
    override fun stream(method: String, request: String, handler: DashboardStreamHandler): DashboardSubscription {
        val stream = client.stream(method, request, handler)
        return DashboardSubscription { stream.close() }
    }
    override fun close() { if (closed.compareAndSet(false, true)) client.close() }
}
