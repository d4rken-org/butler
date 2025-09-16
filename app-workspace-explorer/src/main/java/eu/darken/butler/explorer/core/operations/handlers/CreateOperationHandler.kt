package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.OperationMetrics
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.explorer.core.operations.OperationNotifier.Hint.*
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.explorer.core.operations.conflicts.Conflict
import eu.darken.butler.explorer.core.operations.conflicts.ConflictHandler
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

class CreateOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val conflictHandler: ConflictHandler,
    @Assisted operationNotifier: OperationNotifier,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Create>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
    operationNotifier
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Create")

    override suspend fun execute(
        operation: ExplorerOperation.FileOp.Create,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        log(tag) { "executeCreateFolder(): $operation" }
        val folderPath = operation.parentPath.child(operation.name)

        if (folderPath.exists(gatewaySwitch)) {
            val conflict = Conflict.PathAlreadyExists(
                destination = folderPath.lookup(gatewaySwitch),
            )

            val resolution = conflictHandler.handleConflict(
                operationId = operation.operationId,
                conflict = conflict,
                emitState = emitState
            ) as Conflict.PathAlreadyExists.Resolution

            when (resolution) {
                is Conflict.PathAlreadyExists.Resolution.Skip -> {
                    return OperationMetrics().withSkippedFile()
                }
                is Conflict.PathAlreadyExists.Resolution.Rename -> {
                    val newPath = operation.parentPath.child(resolution.newName)
                    gatewaySwitch.createDir(newPath)

                    operationNotifier.publish(
                        FilesAdded(
                            targetPath = operation.parentPath,
                            files = listOf(newPath),
                            operationId = operation.operationId,
                        )
                    )

                    return OperationMetrics().withAddedDirectory()
                }
                is Conflict.PathAlreadyExists.Resolution.Overwrite -> {
                    TODO()
                }
                is Conflict.PathAlreadyExists.Resolution.Merge -> {
                    throw IllegalArgumentException("Can't merge on create")
                }
                is Conflict.PathAlreadyExists.Resolution.Cancel -> {
                    throw CancellationException("Operation cancelled")
                }
            }
        }

        gatewaySwitch.createDir(folderPath)

        // Emit hint for the created folder
        operationNotifier.publish(
            OperationNotifier.Hint.FilesAdded(
                targetPath = operation.parentPath,
                files = listOf(folderPath),
                operationId = operation.operationId,
            )
        )

        return OperationMetrics().withAddedDirectory()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            operationNotifier: OperationNotifier,
            conflictHandler: ConflictHandler,
        ): CreateOperationHandler
    }
}