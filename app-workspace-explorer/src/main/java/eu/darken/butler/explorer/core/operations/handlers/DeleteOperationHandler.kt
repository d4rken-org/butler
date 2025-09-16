package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.common.files.extensions.deleteWalk
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.OperationMetrics
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.workspace.core.Workspace
import kotlin.time.Instant

class DeleteOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted operationNotifier: OperationNotifier,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Delete>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
    operationNotifier
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Delete")

    override suspend fun execute(
        operation: ExplorerOperation.FileOp.Delete,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        log(tag) { "execute(): $operation" }
        var metrics = OperationMetrics()
        val totalFiles = operation.paths.size
        var processedCount = 0

        // Emit hint that files will be removed
        val parentPath = when (val first = operation.paths.firstOrNull()) {
            is LocalPath -> first.parent()
            else -> null
        }
        if (parentPath != null) {
            this@DeleteOperationHandler.operationNotifier.publish(
                OperationNotifier.Hint.FilesRemoved(
                    targetPath = parentPath,
                    files = operation.paths.toList(),
                    operationId = operation.operationId,
                )
            )
        }

        for (path in operation.paths) {
            try {
                // Get size before deletion for metrics
                val size = if (path.exists(gatewaySwitch)) {
                    gatewaySwitch.lookup(path).size
                } else 0L

                // Perform deletion
                if (operation.recursive) {
                    path.deleteWalk(gatewaySwitch)
                } else {
                    path.delete(gatewaySwitch)
                }

                processedCount++
                metrics = metrics.withRemovedFile(size)

                emitState(
                    OperationState.OnGoing(
                        operationId = operation.operationId,
                        startTime = startTime,
                        progress = Progress.Data(count = Progress.Count.Counter(processedCount, totalFiles)),
                        currentItem = path,
                        processedCount = processedCount,
                        totalCount = totalFiles,
                        bytesProcessed = metrics.bytesProcessed,
                    )
                )
            } catch (e: Exception) {
                if (operation.options.skipOnError) {
                    metrics = metrics.withFailedFile()
                    log(tag, WARN) { "Failed to delete $path: ${e.asLog()}" }
                    continue
                } else {
                    throw e
                }
            }
        }

        return metrics
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            operationNotifier: OperationNotifier,
        ): DeleteOperationHandler
    }
}