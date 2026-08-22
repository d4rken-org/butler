package eu.darken.butler.common.adb.service

import eu.darken.butler.common.adb.AdbServiceConnection
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.isAdbConnectTimeout
import eu.darken.butler.common.adb.service.internal.AdbConnection
import eu.darken.butler.common.adb.service.internal.AdbHostLauncher
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.ipc.IpcContract
import eu.darken.butler.common.ipc.IpcContractMismatchException
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
import kotlinx.coroutines.plus
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Singleton
class AdbServiceClient @Inject constructor(
    serviceLauncher: AdbHostLauncher,
    @AppScope coroutineScope: CoroutineScope,
    dispatcherProvider: DispatcherProvider,
    private val adbSettings: AdbSettings,
    private val debugSettings: DebugSettings,
    private val fileOpsClientFactory: FileOpsClient.Factory,
    private val pkgOpsClientFactory: PkgOpsClient.Factory,
    private val shellOpsClientFactory: ShellOpsClient.Factory,
) : SharedResource<AdbServiceClient.Connection>(
    TAG,
    // Run source lifecycle + teardown on IO, not the @AppScope Default pool. On low-core devices the
    // slow host-disconnect IPC during keep-alive expiry would otherwise starve Dispatchers.Default —
    // the same pool the UI's vmScope uses — wedging the dashboard. Mirrors ShellOps/PkgOps.
    coroutineScope + dispatcherProvider.IO,
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
            val reply = it.checkBase()
            if (!IpcContract.isCompatible(reply)) {
                // A host from a different app revision: its AIDL transaction codes need not line up
                // with ours, so refuse before any module client can issue a call against it.
                log(TAG, WARN) { "Incompatible host, expected ipc-version ${IpcContract.VERSION}, got: $reply" }
                throw IpcContractMismatchException("Host does not speak ipc-version ${IpcContract.VERSION}")
            }
            Connection(
                ipc = it,
                clientModules = listOf(
                    fileOpsClientFactory.create(it.fileOps),
                    pkgOpsClientFactory.create(it.pkgOps),
                    shellOpsClientFactory.create(it.shellOps),
                )
            )
        },
    stopTimeout = ADB_HOST_KEEPALIVE,
    // ADB teardown hangs were a silent-failure support pain point (#2453); surface its lifecycle
    // breadcrumbs at DEBUG even outside trace mode.
    verboseLifecycle = true,
    // A connect timeout means the Shizuku handshake burned its entire budget without an answer, so
    // the default "the starter owns this failure, retry fresh" is a bad trade here: each retry is
    // another full budget. On a device where Shizuku's user service never comes up (the MediaTek/
    // HyperOS defect) that turns one 15s stall into up to five for every concurrent probe. The
    // caller that started the generation always got the real error; this gives it to the others too.
    isRetryableStartupFailure = { !it.isAdbConnectTimeout() },
) {

    data class Connection(
        val ipc: AdbServiceConnection,
        val clientModules: List<IpcClientModule>
    )

    companion object {

        // Keep the privileged ADB host bound briefly after the last lease is released so in-session
        // re-acquisition (screen-bouncing between dashboard and tools, back-to-back privileged ops)
        // reuses the warm host instead of paying the multi-second Shizuku/ADB cold-start. Bounded on
        // purpose: a uid-2000 host should not linger in the background for long. Teardown still happens
        // (and is prompt/safe); this only delays its start.
        private val ADB_HOST_KEEPALIVE: Duration = 30.seconds

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