package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import java.io.IOException
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class TProxyService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag = "SagerNetTProxyService"
    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null
    private var brokerMonitor: Job? = null

    override fun createNotification(profile: ProxyEntity?) = ServiceNotification(this,
        profile?.let { ServiceNotification.genNotificationTitle(it, DataStore.notificationCountryIndicator) }.orEmpty(),
        "service-proxy", true, profile)

    private class StartException(message: String, cause: Throwable) : IOException(message, cause), BaseService.ExpectedException

    override suspend fun prepareService() {
        try {
            TProxyManager.prepare(this)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Logs.w(error)
            throw StartException(error.message.takeIf { error is IllegalStateException }
                ?: getString(R.string.tproxy_root_denied), error)
        }
    }

    override suspend fun startProcesses() {
        try {
            TProxyManager.validate(data.proxy!!.config.config)
            val captureConfig = TProxyManager.captureConfig(this)
            TProxyManager.request("prepare", captureConfig)
            super.startProcesses()
            TProxyManager.request("activate")
            TProxyManager.markCaptureActive(captureConfig.getBoolean("tether"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Logs.w(error)
            throw StartException(getString(R.string.tproxy_start_failed), error)
        }
        brokerMonitor = runOnDefaultDispatcher {
            while (isActive) {
                delay(1_000)
                val error = runCatching { check(TProxyManager.request("status").getBoolean("active")) }.exceptionOrNull()
                if (error != null) {
                    if (error is CancellationException) throw error
                    Logs.w(error)
                    stopRunner(
                        restart = DataStore.connectionGuard && data.desiredProfileId != 0L,
                        msg = getString(R.string.tproxy_core_failed),
                        restartOrigin = ServiceRestartOrigin.Automatic,
                    )
                    return@runOnDefaultDispatcher
                }
            }
        }
    }

    override fun beforeCoreCleanup() {
        brokerMonitor?.cancel()
        brokerMonitor = null
        try {
            TProxyManager.stop(this)
        } catch (error: Throwable) {
            throw StartException(getString(R.string.tproxy_cleanup_failed), error)
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:tproxy").apply { acquire() }
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode != Key.MODE_TPROXY) { stopSelf(); return START_NOT_STICKY }
        return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
    }
}
