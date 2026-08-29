package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorIncidentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Freezes an error report for every operation that fails, at the moment it fails.
 *
 * Application-scoped and constructed eagerly at startup: operations outlive the pages that start
 * them and keep running in a foreground service, and [OperationsManager.completedOperations] has no
 * replay, so a subscription with page lifetime misses whatever completes while no page exists.
 */
@Singleton
class OperationErrorRecorder @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val operationsManager: OperationsManager,
    private val errorIncidentStore: ErrorIncidentStore,
) {

    init {
        log(TAG, INFO) { "init(): subscribing to OperationsManager.completedOperations" }

        operationsManager.completedOperations
            .onEach { snapshot ->
                val error = snapshot.state.error
                if (error == null || error is CancellationException) return@onEach
                // An escaped throwable would cancel the collector, and every later failure would go
                // unrecorded until the app restarts.
                try {
                    errorIncidentStore.remember(
                        error = error,
                        context = operationContext(snapshot.id, snapshot.metadata, snapshot.state),
                        occurredAt = snapshot.state.completedAt,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    log(TAG, ERROR) { "Failed to freeze op ${snapshot.id}: ${t.asLog()}" }
                }
            }
            .launchIn(appScope)
    }

    companion object {
        /** Shared with the share action, which has to name the same operation the same way. */
        fun operationContext(
            id: Operation.Id,
            metadata: Operation.Metadata,
            state: Operation.State.Completed,
        ): Map<String, String?> = mapOf(
            "op.id" to id.toString(),
            "op.origin" to metadata.origin.toString(),
            "op.completedAt" to state.completedAt.toString(),
        )

        private val TAG = logTag("Workspace", "Operations", "ErrorRecorder")
    }
}
