package eu.darken.butler.common.adb.service

import eu.darken.butler.common.adb.AdbServiceConnection
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.service.internal.AdbConnection
import eu.darken.butler.common.adb.service.internal.AdbHostLauncher
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.common.ipc.IpcClientModule
import eu.darken.butler.common.pkgs.pkgops.ipc.PkgOpsClient
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.shell.ipc.ShellOpsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdbServiceClient @Inject constructor(
    serviceLauncher: AdbHostLauncher,
    @AppScope coroutineScope: CoroutineScope,
    private val adbSettings: AdbSettings,
    private val debugSettings: DebugSettings,
    private val fileOpsClientFactory: FileOpsClient.Factory,
    private val pkgOpsClientFactory: PkgOpsClient.Factory,
    private val shellOpsClientFactory: ShellOpsClient.Factory,
) : SharedResource<AdbServiceClient.Connection>(
    TAG,
    coroutineScope,
    callbackFlow {
        log(TAG) { "Instantiating ADB launcher..." }

        if (adbSettings.useAdb.value() != true) throw AdbUnavailableException("ADB is not enabled")

        val optionsInitial = AdbHostOptions(
            isDebug = debugSettings.isDebugMode.value(),
            isTrace = debugSettings.isTraceMode.value(),
            recorderPath = debugSettings.recorderPath.value(),
        )

        val lastInternal = MutableStateFlow<AdbConnection?>(null)
        serviceLauncher
            .createServiceHostConnection(optionsInitial)
            .onEach { wrapper ->
                lastInternal.value = wrapper.host
                send(wrapper.service)
            }
            .launchIn(this)

        combine(
            debugSettings.isDebugMode.flow,
            debugSettings.isTraceMode.flow,
            debugSettings.recorderPath.flow,
            lastInternal.filterNotNull(),
        ) { isDebug, isTrace, recorderPath, lastConnection ->
            val optionsDynamic = AdbHostOptions(
                isDebug = isDebug,
                isTrace = isTrace,
                recorderPath = recorderPath,
            )
            log(TAG) { "Updating debug settings: $optionsDynamic" }
            lastConnection.updateHostOptions(optionsDynamic)
        }
            .setupCommonEventHandlers(TAG) { "dynamic-debug-settings" }
            .launchIn(this)

        log(TAG) { "awaitClose()..." }
        awaitClose {
            log(TAG) { "awaitClose() CLOSING" }
        }
    }
        .map {
            Connection(
                ipc = it,
                clientModules = listOf(
                    fileOpsClientFactory.create(it.fileOps),
                    pkgOpsClientFactory.create(it.pkgOps),
                    shellOpsClientFactory.create(it.shellOps),
                )
            )
        }
) {

    data class Connection(
        val ipc: AdbServiceConnection,
        val clientModules: List<IpcClientModule>
    )

    companion object {

        fun AdbHostLauncher.createServiceHostConnection(
            options: AdbHostOptions,
        ) = this
            .createConnection(
                serviceClass = AdbServiceConnection::class,
                hostClass = AdbHost::class,
                options = options,
            )
            .onStart { log(TAG) { "Initiating connection to host." } }
            .onEach { log(TAG) { "Connection available: $it" } }
            .catch {
                log(TAG, ERROR) { "Failed to establish connection: ${it.asLog()}" }
                throw AdbUnavailableException("Failed to establish connection", cause = it)
            }
            .onCompletion { log(TAG) { "Connection closed" } }

        internal val TAG = logTag("ADB", "Service", "Client")
    }
}