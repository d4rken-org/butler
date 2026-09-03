package eu.darken.butler.workspace.core.operations

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.installer.AppInstallEvent
import eu.darken.butler.common.pkgs.installer.AppInstallInspector
import eu.darken.butler.common.pkgs.installer.AppInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
     * [collectorScope], the first time inline before this returns, and is unsubscribed once the
     * install is done.
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

        // Replayed, not just buffered: a SharedFlow drops what it emits while nobody is subscribed.
        // The flow is created per install and carries only this install's events, so there is nothing
        // stale to replay.
        val events = MutableSharedFlow<AppInstallEvent>(replay = 16, extraBufferCapacity = 16)

        val submittedId = CompletableDeferred<Operation.Id>()
        val installFinished = CompletableDeferred<Unit>()

        // Subscribed before the operation exists, hence the id arriving through a Deferred:
        // completedOperations has no replay, so an install that finishes while submit() is still
        // running would go unnoticed and leave the collector subscribed for the rest of
        // collectorScope's life. Undispatched so that subscribing happens here rather than whenever
        // collectorScope next runs.
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                operationsManager.completedOperations.first { it.id == submittedId.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // collectorScope is the caller's, letting this out would take down whatever else runs
                // in it over a watcher that only decides when to unsubscribe.
                log(TAG, ERROR) { "launch($path): waiting for the end of the install failed - ${e.asLog()}" }
            } finally {
                installFinished.complete(Unit)
            }
        }

        // Closing the calling tab cancels the operation outright, which abandons the install session.
        // If the system's confirm dialog is already on screen the platform owns it from there and may
        // still complete the install on its own.
        val operationId = operationsManager.submit(
            appInstallOperationFactory.create(
                installOrigin = origin,
                plan = plan,
                events = events,
            )
        )
        log(TAG, INFO) { "launch($path): submitted as $operationId" }
        submittedId.complete(operationId)

        // Undispatched: a scheduled collector only starts once collectorScope next runs, and the
        // operation starts inside submit(), so an ObbFailed it already reported would reach the
        // caller late or, if the scope ends first, never. Starting here drains the replay cache
        // before this returns.
        val collector = collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events
                .filterIsInstance<AppInstallEvent.ObbFailed>()
                .collect { onObbFailed(it.reason) }
        }
        installFinished.invokeOnCompletion { collector.cancel() }

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
