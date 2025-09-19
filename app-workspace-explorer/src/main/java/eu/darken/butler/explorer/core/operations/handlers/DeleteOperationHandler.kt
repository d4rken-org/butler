package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.common.files.operations.DeleteOperation
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

class DeleteOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Delete>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Delete")

    override suspend fun execute(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Delete,
    ): Unit = with(context) {
        log(tag) { "execute(): $operation" }

        val deletedPaths = mutableListOf<APath>()

        for (path in operation.paths) {
            log(tag, DEBUG) { "execute(): Processing path: $path" }

            // Skip if file doesn't exist
            if (!path.exists(gatewaySwitch)) {
                log(tag, DEBUG) { "execute(): Path doesn't exist, skipping: $path" }
                continue
            }

            // Retry loop for deletion
            while (currentCoroutineContext().isActive) {
                try {
                    // Perform deletion
                    path.delete(
                        gateway = gatewaySwitch,
                        options = DeleteOperation.Options(
                            recursive = true,
                            onIssue = { issue ->
                                issueHandler.handleIssue(context, issue)
                            },
                        )
                    )


                    deletedPaths.add(path)
                    log(tag, DEBUG) { "execute(): Successfully deleted: $path" }
                    break // Success, move to next file

                } catch (e: Exception) {
                    log(tag, WARN) { "execute(): Failed to delete $path: ${e.asLog()}" }

                    // Check if we should skip on error (legacy mode)
                    if (operation.options.skipOnError) {
                        break // Skip to next file
                    }

                    // Create issue for user interaction
                    val deleteIssue = Issue.UnknownError(
                        exception = e,
                        errorMessage = (e.message ?: e.toString()).toCaString(),
                        destination = path.lookup(gatewaySwitch),
                        canRetry = true,
                        canSkip = true,
                    )

                    when (issueHandler.handleIssue(context, deleteIssue) as Issue.UnknownError.Resolution) {
                        is Issue.UnknownError.Resolution.Retry -> {
                            // Continue the loop to retry deletion
                            continue
                        }
                        is Issue.UnknownError.Resolution.Skip -> {
                            break // Skip to next file
                        }
                        is Issue.UnknownError.Resolution.Cancel -> {
                            throw CancellationException("Delete operation cancelled")
                        }
                    }
                }
            }
        }

        // Emit notification only for successfully deleted files
        if (deletedPaths.isNotEmpty()) {
            // Find a common parent for notification
            val parentPath = findCommonParent(deletedPaths)
            if (parentPath != null) {
                OperationNotifier.Hint.FilesRemoved(
                    affectedFolder = parentPath,
                    files = deletedPaths,
                    operationId = operation.operationId,
                ).run { emit(this) }
            }
        }

        log(tag, INFO) { "execute(): Completed deleted: ${deletedPaths.size}" }
    }

    private fun findCommonParent(paths: List<APath>): APath? {
        if (paths.isEmpty()) return null

        return when (val first = paths.first()) {
            is LocalPath -> {
                // For LocalPath, try to find parent
                first.parent()
            }
            is SAFPath -> {
                // For SAFPath, use the first segments to find parent
                // This is a simplified approach - could be improved
                null
            }
            else -> null
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            issueHandler: IssueHandler,
        ): DeleteOperationHandler
    }
}