package eu.darken.butler.workspace.core.operations

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.installer.AppInstallEvent
import eu.darken.butler.common.pkgs.installer.AppInstallInspector
import eu.darken.butler.common.pkgs.installer.AppInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "install this file" into a submitted [AppInstallOperation], for every workspace that offers
 * it. Only [Operation.Metadata.Origin] and the messages shown differ between them.
 */
@Singleton
class AppInstallLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appInstallInspector: AppInstallInspector,
    private val appInstaller: AppInstaller,
    private val operationsManager: OperationsManager,
    private val appInstallOperationFactory: AppInstallOperation.Factory,
) {

    /**
     * Inspection runs here rather than inside the operation so an unreadable, protected or
     * unsupported container is answered right away instead of behind a progress bar. The
     * unknown-sources check is a preflight for the same reason: without elevated access the platform
     * installer is the only route, and it refuses to run until Butler is an authorized install
     * source, so the user goes to the settings page and no operation is created.
     *
     * Failures propagate - the caller owns how it reports them. [onObbFailed] runs in
     * [collectorScope] until the install is done.
     */
    suspend fun launch(
        path: APath<*>,
        origin: Operation.Metadata.Origin,
        collectorScope: CoroutineScope,
        onObbFailed: suspend (reason: String) -> Unit,
    ): Result {
        val plan = appInstallInspector.inspect(path)
        if (!appInstaller.hasElevation() && !appInstaller.canUseSystemInstaller()) {
            log(TAG, INFO) { "launch($path): Butler is not an authorized install source yet" }
            context.startActivity(appInstaller.unknownSourcesSettings())
            return Result.UnknownSourcesRequired
        }

        // A channel rather than a flow: it buffers what nobody is receiving yet, and the operation
        // closing it on its way out both drains what is queued and ends the collector, so a listener
        // never outlives its install and nothing depends on which coroutine is scheduled first.
        val events = Channel<AppInstallEvent>(capacity = Channel.BUFFERED)

        // Receiving starts before the operation exists because the operation starts inside submit().
        // Undispatched so that happens here rather than whenever collectorScope next runs.
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.receiveAsFlow()
                .filterIsInstance<AppInstallEvent.ObbFailed>()
                .collect { onObbFailed(it.reason) }
        }

        // Closing the calling tab cancels the operation outright, which abandons the install session.
        // If the system's confirm dialog is already on screen the platform owns it from there and may
        // still complete the install on its own.
        val operationId = try {
            operationsManager.submit(
                appInstallOperationFactory.create(
                    installOrigin = origin,
                    plan = plan,
                    events = events,
                )
            )
        } catch (e: Throwable) {
            // No operation owns the channel, so nothing else would ever close it and the collector
            // would sit in collectorScope for the rest of its life.
            events.close()
            throw e
        }
        log(TAG, INFO) { "launch($path): submitted as $operationId" }

        return Result.Submitted(operationId)
    }

    sealed interface Result {
        data class Submitted(val operationId: Operation.Id) : Result

        /** Butler is not an authorized install source yet, the settings page was opened instead. */
        data object UnknownSourcesRequired : Result
    }

    companion object {
        private val TAG = logTag("Workspace", "Operation", "AppInstall", "Launcher")
    }
}
