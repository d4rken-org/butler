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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
     * [collectorScope] and is unsubscribed once the install is done.
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

        // Replayed, not just buffered: launchIn schedules the collector rather than starting it, and
        // a SharedFlow drops what it emits while nobody is subscribed. The flow is created per
        // install and carries only this install's events, so there is nothing stale to replay.
        val events = MutableSharedFlow<AppInstallEvent>(replay = 16, extraBufferCapacity = 16)
        val collector = events
            .filterIsInstance<AppInstallEvent.ObbFailed>()
            .onEach { onObbFailed(it.reason) }
            .launchIn(collectorScope)

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

        collectorScope.launch {
            operationsManager.completedOperations.first { it.id == operationId }
            collector.cancel()
        }

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
