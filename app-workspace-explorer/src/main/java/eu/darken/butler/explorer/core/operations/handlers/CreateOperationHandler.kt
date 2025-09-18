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
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.last

class CreateOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Create>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Create")

    override suspend fun executeInContext(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Create,
    ): Unit = with(context) {
        log(tag) { "executeCreateFolder(): $operation" }
        var targetPath = operation.parentPath.child(operation.name)

        if (targetPath.exists(gatewaySwitch)) {
            val issue = Issue.PathAlreadyExists(
                destination = targetPath.lookup(gatewaySwitch),
            )

            val resolution = issueHandler.handleIssue(
                context = context,
                issue = issue,
            ) as Issue.PathAlreadyExists.Resolution

            when (resolution) {
                is Issue.PathAlreadyExists.Resolution.Skip -> {
                    return
                }
                is Issue.PathAlreadyExists.Resolution.RenameSource -> {
                    targetPath = operation.parentPath.child(resolution.newName)
                }
                is Issue.PathAlreadyExists.Resolution.RenameDestination -> {
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
                is Issue.PathAlreadyExists.Resolution.Overwrite -> {
                    // Delete existing file/folder before creating new one
                    targetPath.deleteWalk(gatewaySwitch)
                }
                is Issue.PathAlreadyExists.Resolution.Merge -> {
                    throw IllegalArgumentException("Can't merge on create")
                }
                is Issue.PathAlreadyExists.Resolution.Cancel -> {
                    throw CancellationException("Operation cancelled")
                }
            }
        }

        gatewaySwitch.createDir(targetPath)

        OperationNotifier.Hint.FilesAdded(
            operationId = operation.operationId,
            affectedFolder = operation.parentPath,
            files = listOf(targetPath),
        ).run { emit(this) }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            issueHandler: IssueHandler,
        ): CreateOperationHandler
    }
}