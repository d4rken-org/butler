package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CreateNewFolder
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.filesystem.FileSystemEvent
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.isActive
import kotlin.time.Clock

class CreateOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Create,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Create")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.CreateNewFolder
        override val title = when (command.type) {
            ExplorerCommand.Create.Type.FILE -> R.string.explorer_operation_create_title_file
            ExplorerCommand.Create.Type.DIRECTORY -> R.string.explorer_operation_create_title_directory
        }.toCaString()
        override val description = caString {
            it.getString(
                when (command.type) {
                    ExplorerCommand.Create.Type.FILE -> R.string.explorer_operation_create_description_file
                    ExplorerCommand.Create.Type.DIRECTORY -> R.string.explorer_operation_create_description_directory
                },
                command.name, command.parentPath.userReadablePath.get(it)
            )
        }
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = flow {
        log(tag) { "perform(): $command" }

        val operationState = State.Active(
            startedAt = operationContext.startedAt,
        )
        emit(operationState)

        val reportBuilder = CreateOperationReport.Builder()

        var currentCommand = command
        var destinationPath: APath

        // Loop to handle conflicts until we have a clear destination path
        while (currentCoroutineContext().isActive) {
            destinationPath = currentCommand.parentPath.child(currentCommand.name)

            if (!gatewaySwitch.exists(destinationPath)) {
                break // No conflict, proceed to creation
            }

            val issue = PathActionIssue.PathAlreadyExists(
                destination = gatewaySwitch.lookup(destinationPath),
                canRenameSource = true,
                canRenameDestination = true,
                canOverwrite = true,
            )
            log(tag) { "perform(): Issue: $issue" }

            State.Waiting(
                startedAt = operationContext.startedAt,
                issue = issue,
            ).run { emit(this) }

            val resolution = issueHandler.handleIssue(
                operationId = operationContext.id,
                issue = issue
            ) as PathActionIssue.PathAlreadyExists.Resolution
            log(tag) { "perform(): Issue: $issue - Resolution: $resolution" }

            when (resolution) {
                is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                    currentCommand = currentCommand.copy(name = resolution.newName)
                    // Continue loop to check if new name also conflicts
                }

                is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                    while (currentCoroutineContext().isActive) {
                        try {
                            destinationPath.delete(
                                gateway = gatewaySwitch,
                                options = DeleteAction.Options(
                                    recursive = true,
                                    onIssue = { issue ->
                                        State.Waiting(
                                            startedAt = operationContext.startedAt,
                                            waitingSince = Clock.System.now(),
                                            issue = issue,
                                        ).run { emit(this) }

                                        issueHandler.handleIssue(
                                            operationContext.id,
                                            issue
                                        ) as PathActionIssue.Resolution
                                    }
                                )
                            ).last()
                            break // Delete succeeded, exit loop
                        } catch (e: Exception) {
                            val deleteIssue = PathActionIssue.UnknownError(
                                exception = e,
                                errorMessage = (e.message ?: e.toString()).toCaString(),
                                destination = gatewaySwitch.lookup(destinationPath),
                                canRetry = true,
                                canSkip = false,
                            )

                            State.Waiting(
                                startedAt = operationContext.startedAt,
                                waitingSince = Clock.System.now(),
                                issue = deleteIssue,
                            ).run { emit(this) }
                            val resolution = issueHandler.handleIssue(
                                operationId = operationContext.id,
                                issue = deleteIssue
                            ) as PathActionIssue.UnknownError.Resolution
                            when (resolution) {
                                is PathActionIssue.UnknownError.Resolution.Retry -> continue
                                is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException("Operation cancelled")
                                is PathActionIssue.UnknownError.Resolution.Skip -> throw IllegalStateException("canSkip = false")
                            }
                        }
                    }
                    break // Path is now clear, proceed to creation
                }

                is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> throw IllegalArgumentException("Can't rename destination on create")
                is PathActionIssue.PathAlreadyExists.Resolution.Merge -> throw IllegalArgumentException("Can't merge on create")
                is PathActionIssue.PathAlreadyExists.Resolution.Skip -> throw IllegalStateException("canSkip = false")
                is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw CancellationException("Operation cancelled")
            }
        }

        // At this point, destinationPath should be conflict-free
        destinationPath = currentCommand.parentPath.child(currentCommand.name)

        // Create the file or directory
        while (currentCoroutineContext().isActive) {
            try {
                when (currentCommand.type) {
                    ExplorerCommand.Create.Type.FILE -> {
                        gatewaySwitch.createFile(destinationPath)
                    }
                    ExplorerCommand.Create.Type.DIRECTORY -> {
                        gatewaySwitch.createDir(destinationPath)
                    }
                }
                break // Creation succeeded, exit loop
            } catch (e: Exception) {
                val createIssue = PathActionIssue.UnknownError(
                    exception = e,
                    errorMessage = (e.message ?: e.toString()).toCaString(),
                    destination = gatewaySwitch.lookup(destinationPath),
                    canRetry = true,
                    canSkip = false,
                )

                State.Waiting(
                    startedAt = operationContext.startedAt,
                    waitingSince = Clock.System.now(),
                    issue = createIssue,
                ).run { emit(this) }

                val resolution = issueHandler.handleIssue(
                    operationId = operationContext.id,
                    issue = createIssue
                ) as PathActionIssue.UnknownError.Resolution

                when (resolution) {
                    is PathActionIssue.UnknownError.Resolution.Retry -> continue
                    is PathActionIssue.UnknownError.Resolution.Cancel -> throw CancellationException("Operation cancelled")
                    is PathActionIssue.UnknownError.Resolution.Skip -> throw IllegalStateException("canSkip = false")
                }
            }
        }

        val createdLookup = gatewaySwitch.lookup(destinationPath)
        fileSystemHinter.trackPathsAdded(setOf(createdLookup))

        FileSystemEvent.Added(
            operationId = operationContext.id,
            paths = setOf(createdLookup)
        ).run { reportBuilder.addPathEvent(this) }


        State.Completed(
            startedAt = operationContext.startedAt,
            report = reportBuilder.build()
        ).run { emit(this) }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.Create,
        ): CreateOperation
    }
}