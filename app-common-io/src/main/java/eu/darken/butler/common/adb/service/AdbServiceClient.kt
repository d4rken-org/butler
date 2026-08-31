package eu.darken.butler.common.adb.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
import eu.darken.butler.common.debug.bugreport.RecorderPathPublisher
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.ipc.IpcContract
import eu.darken.butler.common.ipc.IpcContractMismatchException
import eu.darken.butler.common.ipc.IpcHostAttempt
import eu.darken.butler.common.ipc.gateOnHostIdentity
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
    @ApplicationContext context: Context,
    serviceLauncher: AdbHostLauncher,
    @AppScope coroutineScope: CoroutineScope,
    dispatcherProvider: DispatcherProvider,
    private val adbSettings: AdbSettings,
    private val debugSettings: DebugSettings,
    private val recorderPathPublisher: RecorderPathPublisher,
    private val fileOpsClientFactory: FileOpsClient.Factory,
    private val pkgOpsClientFactory: PkgOpsClient.Factory,
    private val shellOpsClientFactory: ShellOpsClient.Factory,
) : SharedResource<AdbServiceClient.Connection>(
    TAG,
    // Run source lifecycle + teardown on IO, not the @AppScope Default pool. On low-core devices the
    // slow host-disconnect IPC during keep-alive expiry would otherwise starve Dispatchers.Default —
    // the same pool the UI's vmScope uses — wedging the dashboard. Mirrors ShellOps/PkgOps.
    coroutineScope + dispatcherProvider.IO,
    callbackFlow<IpcHostAttempt<AdbServiceConnection>> {
        log(TAG) { "Instantiating ADB launcher..." }

        if (adbSettings.useAdb.value() != true) throw AdbUnavailableException("ADB is not enabled")

        val optionsInitial = AdbHostOptions(
            isDebug = debugSettings.isDebugMode.value(),
            isTrace = debugSettings.isTraceMode.value(),
            recorderPath = recorderPathPublisher.path.value,
            // Shizuku has no init args, so this initial push doubles as the host's launch arguments.
            hostIdentity = IpcContract.current(context).encode(),
        )

        val lastInternal = MutableStateFlow<AdbConnection?>(null)
        serviceLauncher
            .createServiceHostConnection(optionsInitial)
            .onEach { wrapper ->
                lastInternal.value = wrapper.host
                // The teardown signal rides along: Shizuku's unbind is detached and bounded, so the
                // identity gate must not rebind on a mismatch until this generation is really gone.
                send(IpcHostAttempt(wrapper.service, wrapper.disconnectConfirmed))
            }
            .launchIn(this)

        combine(
            debugSettings.isDebugMode.flow,
            debugSettings.isTraceMode.flow,
            recorderPathPublisher.path,
            lastInternal.filterNotNull(),
        ) { isDebug, isTrace, recorderPath, lastConnection ->
            val optionsDynamic = AdbHostOptions(
                isDebug = isDebug,
                isTrace = isTrace,
                recorderPath = recorderPath,
                // No identity: it is a launch stamp, and this push happens after the host was stamped.
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
        .gateOnHostIdentity(
            tag = TAG,
            expected = { IpcContract.current(context) },
            checkBase = { it.checkBase() },
        ) { ipc, identity ->
            Connection(
                ipc = ipc,
                hostIdentity = identity,
                clientModules = listOf(
                    fileOpsClientFactory.create(ipc.fileOps),
                    pkgOpsClientFactory.create(ipc.pkgOps),
                    shellOpsClientFactory.create(ipc.shellOps),
                )
            )
        },
    stopTimeout = ADB_HOST_KEEPALIVE,
    // ADB teardown hangs were a silent-failure support pain point (#2453); surface its lifecycle
    // breadcrumbs at DEBUG even outside trace mode.
    verboseLifecycle = true,
    isRetryableStartupFailure = RETRYABLE_STARTUP_FAILURE,
    // A cached generation may predate an in-place app update, and the keep-alive above makes that
    // window longer than elsewhere. Compared against the identity captured when the connection was
    // gated, so this stays local: the validator runs on every acquire, and another checkBase()
    // round-trip would run `id` in the host each time.
    isReusable = { it.hostIdentity == IpcContract.current(context) },
) {

    data class Connection(
        val ipc: AdbServiceConnection,
        /** Identity of the app installation that launched the host, verified when it was handed out. */
        val hostIdentity: IpcContract.HostIdentity,
        val clientModules: List<IpcClientModule>
    )

    companion object {

        // Keep the privileged ADB host bound briefly after the last lease is released so in-session
        // re-acquisition (screen-bouncing between dashboard and tools, back-to-back privileged ops)
        // reuses the warm host instead of paying the multi-second Shizuku/ADB cold-start. Bounded on
        // purpose: a uid-2000 host should not linger in the background for long. Teardown still happens
        // (and is prompt/safe); this only delays its start.
        private val ADB_HOST_KEEPALIVE: Duration = 30.seconds

        // A connect timeout means the Shizuku handshake burned its entire budget without an answer, so
        // the default "the starter owns this failure, retry fresh" is a bad trade here: each retry is
        // another full budget. On a device where Shizuku's user service never comes up (the MediaTek/
        // HyperOS defect) that turns one 15s stall into up to five for every concurrent probe. The
        // caller that started the generation always got the real error; this gives it to the others too.
        //
        // An identity mismatch is excluded for a different reason: the gate has already decided whether
        // rebinding is safe (it reconnects only when the stale host's teardown was confirmed), and a
        // fresh source collection started here would bypass that decision — binding a replacement that
        // the still-in-flight `remove=true` unbind can take out.
        //
        // Shared with the regression test that covers the mismatch case, so a change here can't quietly
        // leave the test guarding a stale copy.
        internal val RETRYABLE_STARTUP_FAILURE: (Throwable) -> Boolean = {
            !it.isAdbConnectTimeout() && it !is IpcContractMismatchException
        }

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