package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.copyOperation
import eu.darken.butler.common.files.extensions.deleteWalk
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationMetrics
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.explorer.core.operations.conflicts.Conflict
import eu.darken.butler.explorer.core.operations.conflicts.ConflictHandler
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.last

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

    override suspend fun executeInContext(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Create,
    ): OperationMetrics {
        with(context) {
        log(tag) { "executeCreateFolder(): $operation" }
        var targetPath = operation.parentPath.child(operation.name)

        if (targetPath.exists(gatewaySwitch)) {
            val conflict = Conflict.PathAlreadyExists(
                destination = targetPath.lookup(gatewaySwitch),
            )

            val resolution = conflictHandler.handleConflict(
                context = context,
                conflict = conflict,
            ) as Conflict.PathAlreadyExists.Resolution

            when (resolution) {
                is Conflict.PathAlreadyExists.Resolution.Skip -> {
                    return OperationMetrics().withSkippedFile()
                }
                is Conflict.PathAlreadyExists.Resolution.Rename -> {
                    targetPath = operation.parentPath.child(resolution.newName)
                }
                is Conflict.PathAlreadyExists.Resolution.RenameExisting -> {
                    // Rename the existing file/folder to make room for the new one
                    val existingPath = targetPath
                    val newExistingPath = operation.parentPath.child(resolution.newName)
                    existingPath.copyOperation(
                        gateway = gatewaySwitch,
                        target = newExistingPath,
                        overwrite = false
                    ).last()
                    existingPath.deleteWalk(gatewaySwitch)
                }
                is Conflict.PathAlreadyExists.Resolution.Overwrite -> {
                    // Delete existing file/folder before creating new one
                    targetPath.deleteWalk(gatewaySwitch)
                }
                is Conflict.PathAlreadyExists.Resolution.Merge -> {
                    throw IllegalArgumentException("Can't merge on create")
                }
                is Conflict.PathAlreadyExists.Resolution.Cancel -> {
                    throw CancellationException("Operation cancelled")
                }
            }
        }

        gatewaySwitch.createDir(targetPath)

        // Emit hint for the created folder
        operationNotifier.publish(
            OperationNotifier.Hint.FilesAdded(
                targetPath = operation.parentPath,
                files = listOf(targetPath),
                operationId = operation.operationId,
            )
        )

            return OperationMetrics().withAddedDirectory()
        }
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