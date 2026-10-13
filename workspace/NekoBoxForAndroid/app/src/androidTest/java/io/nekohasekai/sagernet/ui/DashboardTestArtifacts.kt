package io.nekohasekai.sagernet.ui

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Export only these controlled test screenshots before AGP uninstalls the debug package. */
internal object DashboardTestArtifacts {
    fun export(source: String, name: String) {
        require(source.matches(Regex("[a-zA-Z0-9/.-]+")) && name.matches(Regex("[a-z-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
            it.write(("run-as ${instrumentation.targetContext.packageName} cat $source > /data/local/tmp/nb4a-dashboard-$name.png\nexit\n").toByteArray())
        }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
    }
}
