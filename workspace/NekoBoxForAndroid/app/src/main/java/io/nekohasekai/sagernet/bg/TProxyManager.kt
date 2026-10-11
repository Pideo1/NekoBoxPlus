package io.nekohasekai.sagernet.bg

import android.content.Context
import android.os.Process
import android.os.SystemClock
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libcore.Libcore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.Semaphore

/** Root networking is owned by the broker, never by VpnService or the UI. */
internal object TProxyManager {
    private val mutex = Mutex()
    private val sessionAccess = Semaphore(1)
    private var process: java.lang.Process? = null
    var ports: List<Int> = emptyList()
        private set
    var healthPort: Int = 0
        private set
    @Volatile
    var activeTethering: Boolean? = null
        private set

    fun markCaptureActive(tethering: Boolean) {
        activeTethering = tethering
    }

    private fun marker(context: Context) = File(context.noBackupFilesDir, "tproxy-session.json")
    private fun journal(context: Context) = File(context.noBackupFilesDir, "tproxy-firewall.json")
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun helper(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libnb4atproxy.so")

    private inline fun <T> withSessionLock(context: Context, block: () -> T): T {
        sessionAccess.acquire()
        try {
            return RandomAccessFile(File(context.noBackupFilesDir, "tproxy-manager.lock"), "rw").use { file ->
                file.channel.use { channel -> channel.lock().use { block() } }
            }
        } finally {
            sessionAccess.release()
        }
    }

    private fun rootProcess(command: String): java.lang.Process {
        val candidates = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter(String::isNotBlank).map { File(it, "su") } + listOf(
                File("/system/bin/su"), File("/system/xbin/su"),
                File("/sbin/su"), File("/debug_ramdisk/su"),
            )
        val executable = candidates.firstOrNull { it.canExecute() }?.absolutePath ?: "su"
        return ProcessBuilder(executable, "-c", command).redirectErrorStream(true).start()
    }

    private fun drainOutput(child: java.lang.Process) {
        Thread {
            runCatching {
                child.inputStream.use { input ->
                    input.copyTo(object : java.io.OutputStream() {
                        override fun write(value: Int) = Unit
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                    })
                }
            }
        }.start()
    }

    fun request(operation: String, config: JSONObject? = null): JSONObject {
        val request = JSONObject().put("op", operation)
        if (config != null) request.put("config", config)
        return JSONObject(Libcore.tProxyRequest(request.toString()))
    }

    private fun setSession(session: JSONObject) {
        Libcore.setTProxySession(session.getString("socket"), session.getString("token"))
    }

    suspend fun reconcile(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock { withSessionLock(context) { reconcileLocked(context) } }
    }

    private fun reconcileLocked(context: Context) {
        val file = marker(context)
        if (!file.exists() && !journal(context).exists()) return
        val session = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (session != null) {
            setSession(session)
            val live = runCatching { request("status") }.isSuccess
            if (live) {
                if (ports.isEmpty()) Libcore.setTProxySession("", "")
                return // A healthy service, including Connecting, owns this session.
            }
        }
        if (!journal(context).exists()) {
            // Root denial cannot leave a networking mutation: the broker writes
            // its journal before its first rule. Do not require root to use VPN.
            file.delete()
            Libcore.setTProxySession("", "")
            return
        }
        try {
            cleanupWithRoot(context, session?.optString("token").orEmpty())
            check(!journal(context).exists()) { context.getString(R.string.tproxy_cleanup_failed) }
            file.delete()
            File(context.noBackupFilesDir, "tproxy-broker.sock").delete()
        } finally {
            Libcore.setTProxySession("", "")
        }
    }

    private fun cleanupWithRoot(context: Context, token: String) {
        val binary = helper(context)
        check(binary.canExecute()) { context.getString(R.string.tproxy_helper_missing) }
        val command = listOf(binary.absolutePath, "--cleanup", "--uid", Process.myUid().toString(),
            "--journal", journal(context).absolutePath, "--token", token).joinToString(" ", transform = ::quote)
        val child = rootProcess(command)
        // Drain output without printing root commands, session secrets or device dumps.
        drainOutput(child)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline && runCatching { child.exitValue() }.isFailure) Thread.sleep(50)
        if (runCatching { child.exitValue() }.isFailure) {
            child.destroy()
            error(context.getString(R.string.tproxy_root_timeout))
        }
        check(child.exitValue() == 0) { context.getString(R.string.tproxy_cleanup_failed) }
    }

    suspend fun prepare(context: Context) = withContext(Dispatchers.IO) {
        activeTethering = null
        mutex.withLock {
            withSessionLock(context) {
                reconcileLocked(context)
                // Any same-process previous session must have been stopped before replacement.
                if (marker(context).exists()) {
                    setSession(JSONObject(marker(context).readText()))
                    request("shutdown")
                    process?.destroy()
                    process = null
                    marker(context).delete()
                }
                val binary = helper(context)
                check(binary.canExecute()) { context.getString(R.string.tproxy_helper_missing) }
                val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                val socket = File(context.noBackupFilesDir, "tproxy-broker.sock")
                val session = JSONObject().put("socket", socket.absolutePath).put("token", token)
                val args = listOf(binary.absolutePath, "--uid", Process.myUid().toString(),
                    "--parent", Process.myPid().toString(), "--socket", socket.absolutePath,
                    "--token", token, "--journal", journal(context).absolutePath)
                val cleanup = listOf(binary.absolutePath, "--cleanup", "--uid", Process.myUid().toString(),
                    "--journal", journal(context).absolutePath, "--token", token).joinToString(" ", transform = ::quote)
                // The root shell survives a broker failure and executes journaled cleanup.
                val command = "trap ${quote(cleanup)} EXIT HUP INT TERM; " + args.joinToString(" ", transform = ::quote)
                marker(context).writeText(session.toString())
                marker(context).setReadable(false, false)
                marker(context).setReadable(true, true)
                marker(context).setWritable(false, false)
                marker(context).setWritable(true, true)
                setSession(session)
                process = rootProcess(command)
                val child = process!!
                drainOutput(child)
                val deadline = SystemClock.elapsedRealtime() + 20_000
                var status: JSONObject? = null
                while (SystemClock.elapsedRealtime() < deadline && runCatching { child.exitValue() }.isFailure) {
                    status = runCatching { request("status") }.getOrNull()
                    if (status != null) break
                    delay(100)
                }
                check(status != null) {
                    if (runCatching { child.exitValue() }.isFailure) context.getString(R.string.tproxy_root_timeout)
                    else context.getString(R.string.tproxy_root_denied)
                }
                healthPort = status.getInt("health_port")
                ports = buildSet {
                    while (size < 4) add(Libcore.reserveTProxyPort().toInt())
                }.toList()
            }
        }
    }

    fun captureConfig(context: Context): JSONObject {
        check(ports.size == 4)
        PackageCache.awaitLoadSync()
        val rules = SagerDatabase.rulesDao.enabledRules().filter { it.outbound != -1L }
            .flatMap { it.packages }.map(String::trim).filter(String::isNotEmpty).toSet()
        val plan = AppCapturePlan.build(context.packageName, DataStore.proxyApps, DataStore.bypass,
            DataStore.individual.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet(),
            rules, DataStore.adblockIncludedPackages.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet(),
            DataStore.adblockSystemWideFilter, false, PackageCache.installedPackages.keys)
        val uids = plan.packages.mapNotNull { PackageCache[it] }.distinct()
        return JSONObject().put("uid", Process.myUid()).put("port4", ports[0]).put("port6", ports[1])
            .put("tether_port4", ports[2]).put("tether_port6", ports[3])
            .put("ipv4", DataStore.ipv6Mode != IPv6Mode.ONLY)
            .put("ipv6", DataStore.ipv6Mode != IPv6Mode.DISABLE)
            .put("all_apps", plan.bypass == null).put("exclude", plan.bypass == true)
            .put("uids", JSONArray(uids)).put("bypass_lan", DataStore.bypassLan)
            .put("tether", DataStore.tproxyTethering)
    }

    fun validate(config: String) {
        Libcore.validateTProxyConfig(config, ports[0], ports[1], ports[2], ports[3],
            DataStore.ipv6Mode != IPv6Mode.ONLY, DataStore.ipv6Mode != IPv6Mode.DISABLE, DataStore.tproxyTethering)
    }

    fun stop(context: Context) {
        activeTethering = null
        withSessionLock(context) {
            if (!marker(context).exists()) return
            // Keep the journal and supervisor if cleanup fails; a subsequent start retries it.
            val stopped = runCatching { request("shutdown") }
            if (stopped.isFailure && journal(context).exists()) throw stopped.exceptionOrNull()!!
            process?.destroy()
            process = null
            marker(context).delete()
            File(context.noBackupFilesDir, "tproxy-broker.sock").delete()
            ports = emptyList()
            healthPort = 0
            Libcore.setTProxySession("", "")
        }
    }
}
