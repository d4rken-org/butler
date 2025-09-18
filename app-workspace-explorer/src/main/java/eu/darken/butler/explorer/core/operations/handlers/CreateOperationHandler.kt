package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.deleteWalk
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.common.files.operations.MoveOperation
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.isActive

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

    private suspend fun resolveNestedConflicts(
        context: OperationContext,
        parentPath: APath,
        initialName: String,
        parentIssue: Issue.PathAlreadyExists,
    ): APath = with(context) {
        var currentName = initialName

        while (currentCoroutineContext().isActive) {
            val currentPath = parentPath.child(currentName)
            if (!currentPath.exists(gatewaySwitch)) break

            val nestedIssue = parentIssue.copy(
                destination = currentPath.lookup(gatewaySwitch),
            )
            log(tag, INFO) { "resolveNestedConflicts(): Nested conflict: $nestedIssue" }

            val nestedResolution = issueHandler.handleIssue(
                context = context,
                issue = nestedIssue,
            ) as Issue.PathAlreadyExists.Resolution
            log(tag, INFO) { "resolveNestedConflicts(): Resolution: $nestedResolution" }

            when (nestedResolution) {
                is Issue.PathAlreadyExists.Resolution.RenameSource -> {
                    currentName = nestedResolution.newName
                }
                is Issue.PathAlreadyExists.Resolution.RenameDestination -> {
                    throw IllegalArgumentException("Cannot rename destination when renaming existing file")
                }
                is Issue.PathAlreadyExists.Resolution.Overwrite -> {
                    currentPath.deleteWalk(gatewaySwitch)
                    break // Exit conflict loop, path is now clear
                }
                is Issue.PathAlreadyExists.Resolution.Skip -> throw IllegalStateException("canSkip = false")
                is Issue.PathAlreadyExists.Resolution.Merge -> throw IllegalArgumentException("Cannot merge when renaming existing file")
                is Issue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException("Operation cancelled")

            }
        }

        return parentPath.child(currentName)
    }

    override suspend fun execute(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Create,
    ): Unit = with(context) {
        log(tag) { "execute(): $operation" }

        var currentOperation = operation
        var destinationPath: APath

        // Loop to handle conflicts until we have a clear destination path
        while (currentCoroutineContext().isActive) {
            destinationPath = currentOperation.parentPath.child(currentOperation.name)

            if (!destinationPath.exists(gatewaySwitch)) {
                break // No conflict, proceed to creation
            }

            val issue = Issue.PathAlreadyExists(
                destination = destinationPath.lookup(gatewaySwitch),
                canRenameSource = true,
                canRenameDestination = true,
                canOverwrite = true,
            )
            log(tag, INFO) { "execute(): Issue: $issue" }

            val resolution = issueHandler.handleIssue(
                context = context,
                issue = issue,
            ) as Issue.PathAlreadyExists.Resolution
            log(tag, INFO) { "execute(): Issue: $issue - Resolution: $resolution" }

            when (resolution) {
                is Issue.PathAlreadyExists.Resolution.RenameSource -> {
                    currentOperation = currentOperation.copy(name = resolution.newName)
                    // Continue loop to check if new name also conflicts
                }

                is Issue.PathAlreadyExists.Resolution.RenameDestination -> {
                    val resolvedPath = resolveNestedConflicts(
                        context = context,
                        parentPath = currentOperation.parentPath,
                        initialName = resolution.newName,
                        parentIssue = issue,
                    )

                    // Now perform the move with the resolved destination
                    while (currentCoroutineContext().isActive) {
                        try {
                            gatewaySwitch.move(destinationPath, resolvedPath, MoveOperation.Options()).last()
                            break // Move succeeded, exit loop
                        } catch (e: Exception) {
                            val moveIssue = Issue.UnknownError(
                                exception = e,
                                errorMessage = (e.message ?: e.toString()).toCaString(),
                                source = destinationPath.lookup(gatewaySwitch),
                                destination = resolvedPath.lookup(gatewaySwitch),
                                canRetry = true,
                            )
                            when (issueHandler.handleIssue(context, moveIssue) as Issue.UnknownError.Resolution) {
                                is Issue.UnknownError.Resolution.Retry -> continue
                                is Issue.UnknownError.Resolution.Cancel -> throw CancellationException("Operation cancelled")
                                is Issue.UnknownError.Resolution.Skip -> throw IllegalStateException("canSkip = false")
                            }
                        }
                    }
                    break // Path is now clear, proceed to creation
                }

                is Issue.PathAlreadyExists.Resolution.Overwrite -> {
                    while (currentCoroutineContext().isActive) {
                        try {
                            destinationPath.deleteWalk(gatewaySwitch)
                            break // Delete succeeded, exit loop
                        } catch (e: Exception) {
                            val deleteIssue = Issue.UnknownError(
                                exception = e,
                                errorMessage = (e.message ?: e.toString()).toCaString(),
                                destination = destinationPath.lookup(gatewaySwitch),
                                canRetry = true,
                                canSkip = false,
                            )
                            when (issueHandler.handleIssue(context, deleteIssue) as Issue.UnknownError.Resolution) {
                                is Issue.UnknownError.Resolution.Retry -> continue
                                is Issue.UnknownError.Resolution.Cancel -> throw CancellationException("Operation cancelled")
                                is Issue.UnknownError.Resolution.Skip -> throw IllegalStateException("canSkip = false")
                            }
                        }
                    }
                    break // Path is now clear, proceed to creation
                }

                is Issue.PathAlreadyExists.Resolution.Merge -> throw IllegalArgumentException("Can't merge on create")
                is Issue.PathAlreadyExists.Resolution.Skip -> throw IllegalStateException("canSkip = false")
                is Issue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException("Operation cancelled")
            }
        }

        // At this point, destinationPath should be conflict-free
        destinationPath = currentOperation.parentPath.child(currentOperation.name)

        while (currentCoroutineContext().isActive) {
            try {
                when (operation.type) {
                    ExplorerOperation.FileOp.Create.Type.FILE -> {
                        gatewaySwitch.createFile(destinationPath)
                    }
                    ExplorerOperation.FileOp.Create.Type.FOLDER -> {
                        gatewaySwitch.createDir(destinationPath)
                    }
                }
                break // Creation succeeded, exit loop
            } catch (e: Exception) {
                val createIssue = Issue.UnknownError(
                    exception = e,
                    errorMessage = (e.message ?: e.toString()).toCaString(),
                    destination = destinationPath.lookup(gatewaySwitch),
                    canRetry = true,
                )
                when (issueHandler.handleIssue(context, createIssue) as Issue.UnknownError.Resolution) {
                    is Issue.UnknownError.Resolution.Retry -> continue
                    is Issue.UnknownError.Resolution.Cancel -> throw CancellationException("Operation cancelled")
                    is Issue.UnknownError.Resolution.Skip -> throw IllegalStateException("canSkip = false")
                }
            }
        }

        OperationNotifier.Hint.FilesAdded(
            operationId = operation.operationId,
            affectedFolder = operation.parentPath,
            files = listOf(destinationPath),
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