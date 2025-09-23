package eu.darken.butler.explorer.core.operations.handlers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CreateNewFolder
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.operations.ExplorerOperation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CreateOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Create,
    private val issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Create")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.CreateNewFolder
        override val title = caString { "Create" } // TODO
        override val description = caString { "Create selected files" } // TODO
    }

    override suspend fun execute(
        operationContext: Operation.Context
    ): Flow<State> = flow {
        log(tag) { "execute(): $command" }

//        var currentOperation = operation
//        var destinationPath: APath
//
//        // Loop to handle conflicts until we have a clear destination path
//        while (currentCoroutineContext().isActive) {
//            destinationPath = currentOperation.parentPath.child(currentOperation.name)
//
//            if (!destinationPath.exists(gatewaySwitch)) {
//                break // No conflict, proceed to creation
//            }
//
//            val issue = PathActionIssue.PathAlreadyExists(
//                destination = destinationPath.lookup(gatewaySwitch),
//                canRenameSource = true,
//                canRenameDestination = true,
//                canOverwrite = true,
//            )
//            log(tag, INFO) { "execute(): Issue: $issue" }
//
//            val resolution = issueHandler.handleIssue(
//                context = context,
//                issue = issue,
//            ) as PathActionIssue.PathAlreadyExists.Resolution
//            log(tag, INFO) { "execute(): Issue: $issue - Resolution: $resolution" }
//
//            when (resolution) {
//                is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
//                    currentOperation = currentOperation.copy(name = resolution.newName)
//                    // Continue loop to check if new name also conflicts
//                }
//
//                is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
//                    val resolvedPath = resolveNestedConflicts(
//                        context = context,
//                        parentPath = currentOperation.parentPath,
//                        initialName = resolution.newName,
//                        parentIssue = issue,
//                    )
//
//                    // Now perform the move with the resolved destination
//                    while (currentCoroutineContext().isActive) {
//                        try {
//                            // TODO: This is a workaround - gateway move operation is not yet implemented
//                            // This should perform a rename/move operation from destinationPath to resolvedPath
//                            setOf(destinationPath)
//                                .move(
//                                    gateway = gatewaySwitch,
//                                    destination = currentOperation.parentPath,
//                                    options = MoveAction.Options(),
//                                )
//                                .last()
//                            break // Move succeeded, exit loop
//                        } catch (e: Exception) {
//                            val moveIssue = PathActionIssue.UnknownError(
//                                exception = e,
//                                errorMessage = (e.message ?: e.toString()).toCaString(),
//                                source = destinationPath.lookup(gatewaySwitch),
//                                destination = resolvedPath.lookup(gatewaySwitch),
//                                canRetry = true,
//                            )
//                            when (issueHandler.handleIssue(context, moveIssue) as PathActionIssue.UnknownError.Resolution) {
//                                is PathActionIssue.UnknownError.Resolution.Retry -> continue
//                                is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException("Operation cancelled")
//                                is PathActionIssue.UnknownError.Resolution.Skip -> throw IllegalStateException("canSkip = false")
//                            }
//                        }
//                    }
//                    break // Path is now clear, proceed to creation
//                }
//
//                is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
//                    while (currentCoroutineContext().isActive) {
//                        try {
//                            destinationPath.delete(
//                                gateway = gatewaySwitch,
//                                options = DeleteAction.Options(
//                                    recursive = true,
//                                    onIssue = { issue -> issueHandler.handleIssue(context, issue) }
//                                )
//                            ).last()
//                            break // Delete succeeded, exit loop
//                        } catch (e: Exception) {
//                            val deleteIssue = PathActionIssue.UnknownError(
//                                exception = e,
//                                errorMessage = (e.message ?: e.toString()).toCaString(),
//                                destination = destinationPath.lookup(gatewaySwitch),
//                                canRetry = true,
//                                canSkip = false,
//                            )
//                            when (issueHandler.handleIssue(context, deleteIssue) as PathActionIssue.UnknownError.Resolution) {
//                                is PathActionIssue.UnknownError.Resolution.Retry -> continue
//                                is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException("Operation cancelled")
//                                is PathActionIssue.UnknownError.Resolution.Skip -> throw IllegalStateException("canSkip = false")
//                            }
//                        }
//                    }
//                    break // Path is now clear, proceed to creation
//                }
//
//                is PathActionIssue.PathAlreadyExists.Resolution.Merge -> throw IllegalArgumentException("Can't merge on create")
//                is PathActionIssue.PathAlreadyExists.Resolution.Skip -> throw IllegalStateException("canSkip = false")
//                is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException("Operation cancelled")
//            }
//        }
//
//        // At this point, destinationPath should be conflict-free
//        destinationPath = currentOperation.parentPath.child(currentOperation.name)
//
//        while (currentCoroutineContext().isActive) {
//            try {
//                when (operation.type) {
//                    ExplorerOperation.FileOp.Create.Type.FILE -> {
//                        gatewaySwitch.createFile(destinationPath)
//                    }
//                    ExplorerOperation.FileOp.Create.Type.FOLDER -> {
//                        gatewaySwitch.createDir(destinationPath)
//                    }
//                }
//                break // Creation succeeded, exit loop
//            } catch (e: Exception) {
//                val createIssue = PathActionIssue.UnknownError(
//                    exception = e,
//                    errorMessage = (e.message ?: e.toString()).toCaString(),
//                    destination = destinationPath.lookup(gatewaySwitch),
//                    canRetry = true,
//                )
//                when (issueHandler.handleIssue(context, createIssue) as PathActionIssue.UnknownError.Resolution) {
//                    is PathActionIssue.UnknownError.Resolution.Retry -> continue
//                    is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException("Operation cancelled")
//                    is PathActionIssue.UnknownError.Resolution.Skip -> throw IllegalStateException("canSkip = false")
//                }
//            }
//        }
//
//        trackPathsAdded(setOf(destinationPath))
//
//        OperationResult.Success(
//            summary = caString { "Created  $destinationPath" }  // TODO localize
//        )
    }

//    private suspend fun resolveNestedConflicts(
//        context: OperationContext,
//        parentPath: APath,
//        initialName: String,
//        parentIssue: PathActionIssue.PathAlreadyExists,
//    ): APath = with(context) {
//        var currentName = initialName
//
//        while (currentCoroutineContext().isActive) {
//            val currentPath = parentPath.child(currentName)
//            if (!currentPath.exists(gatewaySwitch)) break
//
//            val nestedIssue = parentIssue.copy(
//                destination = currentPath.lookup(gatewaySwitch),
//            )
//            log(tag, INFO) { "resolveNestedConflicts(): Nested conflict: $nestedIssue" }
//
//            val nestedResolution = issueHandler.handleIssue(
//                context = context,
//                issue = nestedIssue,
//            ) as PathActionIssue.PathAlreadyExists.Resolution
//            log(tag, INFO) { "resolveNestedConflicts(): Resolution: $nestedResolution" }
//
//            when (nestedResolution) {
//                is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
//                    currentName = nestedResolution.newName
//                }
//                is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
//                    throw IllegalArgumentException("Cannot rename destination when renaming existing file")
//                }
//                is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
//                    currentPath.delete(
//                        gateway = gatewaySwitch,
//                        options = DeleteAction.Options(
//                            recursive = true,
//                            onIssue = { issue ->
//                                issueHandler.handleIssue(context, issue)
//                            }
//                        )
//                    ).last()
//                    break // Exit conflict loop, path is now clear
//                }
//                is PathActionIssue.PathAlreadyExists.Resolution.Skip -> throw IllegalStateException("canSkip = false")
//                is PathActionIssue.PathAlreadyExists.Resolution.Merge -> throw IllegalArgumentException("Cannot merge when renaming existing file")
//                is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException("Operation cancelled")
//
//            }
//        }
//
//        return parentPath.child(currentName)
//    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.Create,
        ): CreateOperation
    }
}