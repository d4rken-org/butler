package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.handlers.BaseOperationHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Context object that encapsulates operation execution state and capabilities.
 * This avoids passing multiple parameters through operation handler chains.
 */
data class OperationContext(
    val operationId: Operation.Id,
    val startedAt: Instant = Clock.System.now(),
    private val emitState: suspend (OperationState) -> Unit,
    private val emitPathEvent: suspend (FileSystemEvent) -> Unit,
    val reportBuilder: OperationReport.Builder = OperationReport.Builder(startedAt),
) {
    suspend fun emit(state: OperationState) {
        emitState(state)
    }

    /**
     * Extension function to execute operation handlers within this context.
     * This enables the pattern: with(context) { handler.execute(operation) }
     */
    suspend fun <T : ExplorerOperation> BaseOperationHandler<T>.execute(
        operation: T
    ): OperationResult = execute(this@OperationContext, operation)

    suspend fun trackPathsRemoved(paths: Collection<APath>) {
        emitPathEvent(FileSystemEvent.FilesRemoved(operationId = operationId, paths = paths.toSet()))
    }

    suspend fun trackPathsAdded(paths: Collection<APath>) {
        emitPathEvent(FileSystemEvent.FilesAdded(operationId = operationId, paths = paths.toSet()))
    }

    suspend fun trackPathsModified(paths: Collection<APath>) {
        emitPathEvent(FileSystemEvent.FilesModified(operationId = operationId, paths = paths.toSet()))
    }

    suspend fun updateProgress(bytes: Long) {
        reportBuilder.updateBytesProcessed(bytes)
    }


    suspend fun getMetrics(): OperationReport = reportBuilder.build()
}