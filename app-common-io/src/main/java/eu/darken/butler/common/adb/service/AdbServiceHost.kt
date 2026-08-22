package eu.darken.butler.common.adb.service

import android.content.Context
import androidx.annotation.Keep
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.adb.AdbServiceConnection
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.ipc.IpcContract
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.local.ipc.FileOpsConnection
import eu.darken.butler.common.files.local.ipc.FileOpsHost
import eu.darken.butler.common.pkgs.pkgops.ipc.PkgOpsConnection
import eu.darken.butler.common.pkgs.pkgops.ipc.PkgOpsHost
import eu.darken.butler.common.shell.ipc.ShellOpsConnection
import eu.darken.butler.common.shell.ipc.ShellOpsHost
import eu.darken.flowshell.core.cmd.FlowCmd
import eu.darken.flowshell.core.cmd.execute
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Keep
class AdbServiceHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileOpsHost: Lazy<FileOpsHost>,
    private val pkgOpsHost: Lazy<PkgOpsHost>,
    private val shellOpsHost: Lazy<ShellOpsHost>,
) : AdbServiceConnection.Stub() {

    init {
        log(TAG, INFO) { "init()" }
    }

    override fun checkBase(): String {
        val sb = StringBuilder()
        // Must stay the FIRST line: the client gates the whole connection on it.
        sb.append("${IpcContract.marker()}\n")
        sb.append("Our pkg: ${context.packageName}\n")
        val ids = runBlocking { FlowCmd("id").execute() }
        sb.append("Shell ids are: ${ids.merged}\n")
        val result = sb.toString()
        log(TAG) { "checkBase(): $result" }
        return result
    }

    override fun getFileOps(): FileOpsConnection = fileOpsHost.get()

    override fun getPkgOps(): PkgOpsConnection = pkgOpsHost.get()

    override fun getShellOps(): ShellOpsConnection = shellOpsHost.get()

    companion object {
        private val TAG = logTag("ADB", "Service", "Host")
    }
}