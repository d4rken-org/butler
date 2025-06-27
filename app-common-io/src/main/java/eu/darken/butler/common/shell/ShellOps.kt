package eu.darken.butler.common.shell

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.adb.service.runModuleAction
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootUnavailableException
import eu.darken.butler.common.root.canUseRootNow
import eu.darken.butler.common.root.service.runModuleAction
import eu.darken.butler.common.sharedresource.HasSharedResource
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.sharedresource.keepResourcesAlive
import eu.darken.butler.common.shell.ipc.ShellOpsClient
import eu.darken.butler.common.shell.ipc.ShellOpsCmd
import eu.darken.butler.common.shell.ipc.ShellOpsResult
import eu.darken.flowshell.core.cmd.FlowCmd
import eu.darken.flowshell.core.cmd.execute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShellOps @Inject constructor(
    @param:AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
) : HasSharedResource<Any> {

    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    private suspend fun <T> adbOps(action: suspend (ShellOpsClient) -> T): T {
        if (!adbManager.canUseAdbNow()) throw AdbUnavailableException()
        return keepResourcesAlive(adbManager.serviceClient) {
            adbManager.serviceClient.runModuleAction(ShellOpsClient::class.java) { action(it) }
        }
    }

    private suspend fun <T> rootOps(action: suspend (ShellOpsClient) -> T): T {
        if (!rootManager.canUseRootNow()) throw RootUnavailableException()
        return keepResourcesAlive(rootManager.serviceClient) {
            rootManager.serviceClient.runModuleAction(ShellOpsClient::class.java) { action(it) }
        }
    }

    suspend fun execute(cmd: ShellOpsCmd, mode: Mode): ShellOpsResult = withContext(dispatcherProvider.IO) {
        try {
            var result: ShellOpsResult? = null
            if (mode == Mode.NORMAL) {
                log(TAG, VERBOSE) { "execute(mode->NORMAL): $cmd" }
                result = cmd.toFlowCmd().execute().toShellOpsResult()
            }

            if (result == null && rootManager.canUseRootNow() && mode == Mode.ROOT) {
                log(TAG, VERBOSE) { "execute(mode->ROOT): $cmd" }
                result = rootOps { it.execute(cmd) }
            }

            if (result == null && adbManager.canUseAdbNow() && mode == Mode.ADB) {
                log(TAG, VERBOSE) { "execute(mode->ADB): $cmd" }
                result = adbOps { it.execute(cmd) }
            }

            if (Bugs.isTrace) {
                log(TAG, VERBOSE) { "execute($cmd, $mode): $result" }
            }

            if (result == null) throw ShellOpsException("No matching mode", cmd)

            result
        } catch (e: IOException) {
            log(TAG, WARN) { "execute($cmd) failed: ${e.asLog()}" }
            throw ShellOpsException(cmd = cmd, cause = e)
        }
    }

    private fun ShellOpsCmd.toFlowCmd() = FlowCmd(cmds)

    private fun FlowCmd.Result.toShellOpsResult() = ShellOpsResult(
        exitCode = exitCode.value,
        output = output,
        errors = errors
    )

    enum class Mode {
        NORMAL, ROOT, ADB
    }

    companion object {
        val TAG = logTag("ShellOps")
    }
}