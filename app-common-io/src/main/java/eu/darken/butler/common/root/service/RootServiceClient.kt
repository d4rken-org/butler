package eu.darken.butler.common.root.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.bugreport.RecorderPathPublisher
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.ipc.IpcContract
import eu.darken.butler.common.ipc.IpcHostAttempt
import eu.darken.butler.common.ipc.gateOnHostIdentity
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.common.ipc.IpcClientModule
import eu.darken.butler.common.pkgs.pkgops.ipc.PkgOpsClient
import eu.darken.butler.common.root.RootSettings
import eu.darken.butler.common.root.RootUnavailableException
import eu.darken.butler.common.root.service.internal.RootConnection
import eu.darken.butler.common.root.service.internal.RootHostLauncher
import eu.darken.butler.common.root.service.internal.RootHostOptions
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

@Singleton
class RootServiceClient @Inject constructor(
    @ApplicationContext context: Context,
    @AppScope coroutineScope: CoroutineScope,
    dispatcherProvider: DispatcherProvider,
    private val rootHostLauncher: RootHostLauncher,
    private val rootSettings: RootSettings,
    private val debugSettings: DebugSettings,
    private val recorderPathPublisher: RecorderPathPublisher,
    private val fileOpsClientFactory: FileOpsClient.Factory,
    private val pkgOpsClientFactory: PkgOpsClient.Factory,
    private val shellOpsClientFactory: ShellOpsClient.Factory,
) : SharedResource<RootServiceClient.Connection>(
    tag = TAG,
    // Run source lifecycle + teardown on IO, not the @AppScope Default pool. On low-core devices the
    // slow host-disconnect IPC during teardown would otherwise starve Dispatchers.Default — the same
    // pool the UI's vmScope uses — wedging the dashboard. Mirrors ShellOps/PkgOps.
    parentScope = coroutineScope + dispatcherProvider.IO,
    source = callbackFlow<IpcHostAttempt<RootServiceConnection>> {
        log(TAG) { "Instantiating Root launcher..." }
        if (rootSettings.useRoot.value() != true) throw RootUnavailableException("Root is not enabled")

        val initialOptions = RootHostOptions(
            isDebug = debugSettings.isDebugMode.value(),
            isTrace = debugSettings.isTraceMode.value(),
            recorderPath = recorderPathPublisher.path.value,
        )

        val lastInternal = MutableStateFlow<RootConnection?>(null)
        rootHostLauncher
            .createHostConnection(
                options = initialOptions,
                hostIdentity = IpcContract.current(context).encode(),
            )
            .onEach { wrapper ->
                lastInternal.value = wrapper.host
                // No teardown signal: RootHostLauncher tears down inside the producer coroutine, so
                // the identity gate's re-collection already happens after the host is gone.
                send(IpcHostAttempt(wrapper.service))
            }
            .launchIn(this)

        combine(
            debugSettings.isDebugMode.flow,
            debugSettings.isTraceMode.flow,
            recorderPathPublisher.path,
            lastInternal.filterNotNull(),
        ) { isDebug, isTrace, recorderPath, lastConnection ->
            val dynamicOptions = RootHostOptions(
                isDebug = isDebug,
                isTrace = isTrace,
                recorderPath = recorderPath,
            )
            log(TAG) { "Updating debug settings: $dynamicOptions" }
            lastConnection.updateHostOptions(dynamicOptions)
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
    // Root teardown hangs were a silent-failure support pain point (#2453); surface its lifecycle
    // breadcrumbs at DEBUG even outside trace mode.
    verboseLifecycle = true,
    // A cached generation may predate an in-place app update. Compared against the identity captured
    // when the connection was gated, so this stays local: the validator runs on every acquire, and
    // another checkBase() round-trip would run `id` in the host each time.
    isReusable = { it.hostIdentity == IpcContract.current(context) },
) {

    data class Connection(
        val ipc: RootServiceConnection,
        /** Identity of the app installation that launched the host, verified when it was handed out. */
        val hostIdentity: IpcContract.HostIdentity,
        val clientModules: List<IpcClientModule>
    )

    companion object {

        fun RootHostLauncher.createHostConnection(
            /**
             * Keep this false by default — working without mount-master is more reliable.
             * Only needed if [DataAreaManager] can't get the altered paths or the rest of IO can't cope.
             */
            useMountMaster: Boolean = false,
            options: RootHostOptions,
            hostIdentity: String,
        ) = this
            .createConnection(
                serviceClass = RootServiceConnection::class,
                hostClass = RootHost::class,
                useMountMaster = useMountMaster,
                options = options,
                hostIdentity = hostIdentity,
            )
            .onStart { log(TAG) { "Initiating connection to host." } }
            .onEach { log(TAG) { "Connection available: $it" } }
            .catch {
                log(TAG, ERROR) { "Failed to establish connection: ${it.asLog()}" }
                throw RootUnavailableException("Failed to establish connection", cause = it)
            }
            .onCompletion { log(TAG) { "Connection unavailable." } }

        internal val TAG = logTag("Root", "Service", "Client")
    }
}