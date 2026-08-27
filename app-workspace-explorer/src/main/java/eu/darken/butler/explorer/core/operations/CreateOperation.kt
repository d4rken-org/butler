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
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.create
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
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
        override val kind = when (command.type) {
            ExplorerCommand.Create.Type.FILE -> Operation.Metadata.Kind.CREATE_FILE
            ExplorerCommand.Create.Type.DIRECTORY -> Operation.Metadata.Kind.CREATE_FOLDER
        }
        override val pathPlan = OperationPathPlan(
            targets = listOf(command.parentPath.child(command.name)),
        )
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = channelFlow {
        gatewaySwitch.useRes {
            log(tag) { "perform(): $command" }

            var stateActive = State.Active(
                startedAt = operationContext.startedAt,
            )
            send(stateActive)

            val reportBuilder = CreateOperationReport.Builder()

            val targetPath = command.parentPath.child(command.name)
            val createType = when (command.type) {
                ExplorerCommand.Create.Type.FILE -> CreateAction.CreateType.FILE
                ExplorerCommand.Create.Type.DIRECTORY -> CreateAction.CreateType.DIRECTORY
            }

            val result = targetPath
                .create(
                    gateway = gatewaySwitch,
                    type = createType,
                    options = CreateAction.Options(
                        onIssue = { issue ->
                            send(
                                State.Waiting(
                                    startedAt = operationContext.startedAt,
                                    waitingSince = Clock.System.now(),
                                    issue = issue,
                                )
                            )
                            val resolution = issueHandler.handleIssue(
                                operationContext.id,
                                issue
                            ) as PathActionIssue.Resolution
                            send(stateActive)
                            resolution
                        }
                    )
                )
                .last()

            result as CreateAction.State.Completed<*, *>

            // Track filesystem changes
            @Suppress("UNCHECKED_CAST")
            fileSystemHinter.trackPathsAdded(
                operationContext.id,
                setOf(result.created as APathLookup<*>)
            )

            // Build report
            @Suppress("UNCHECKED_CAST")
            reportBuilder.setSubjectPath((result.created as APathLookup<*>).lookedUp)

            @Suppress("UNCHECKED_CAST")
            reportBuilder.addPathEvent(
                FileSystemEvent.Added(
                    operationId = operationContext.id,
                    paths = setOf(result.created as APathLookup<*>)
                )
            )

            send(
                State.Completed(
                    startedAt = operationContext.startedAt,
                    report = reportBuilder.build()
                )
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.Create,
        ): CreateOperation
    }
}