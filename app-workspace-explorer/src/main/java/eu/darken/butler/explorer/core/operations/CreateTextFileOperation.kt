package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.NoteAdd
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
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

class CreateTextFileOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.CreateTextFile,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "CreateTextFile")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.AutoMirrored.TwoTone.NoteAdd
        override val title = R.string.explorer_operation_create_text_file_title.toCaString()
        override val description = caString {
            it.getString(
                R.string.explorer_operation_create_text_file_description,
                command.path.name,
            )
        }
        override val kind = Operation.Metadata.Kind.CREATE_FILE
        override val pathPlan = OperationPathPlan(
            targets = listOf(command.path),
        )
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = channelFlow {
        gatewaySwitch.useRes {
            log(tag) { "perform(): $command" }

            val stateActive = State.Active(
                startedAt = operationContext.startedAt,
            )
            send(stateActive)

            val reportBuilder = CreateOperationReport.Builder()

            // Create the file
            val result = command.path
                .create(
                    gateway = gatewaySwitch,
                    type = CreateAction.CreateType.FILE,
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

            // A rename resolution for a name conflict moves the file we created, writing to
            // command.path would truncate the very file the rename was meant to preserve.
            val createdPath = (result.created as APathLookup<*>).lookedUp

            log(tag) { "Writing ${command.content.length} characters to $createdPath" }
            gatewaySwitch.openOutputStream(createdPath, append = false).use { outputStream ->
                outputStream.write(command.content.toByteArray(Charsets.UTF_8))
            }

            // Re-lookup, result.created predates the content. BASE so the item enters the listing
            // with a size instead of showing "?" until the next refresh.
            val lookup = gatewaySwitch.lookup(createdPath, LookupOptions.BASE)

            // Track filesystem changes
            @Suppress("UNCHECKED_CAST")
            fileSystemHinter.trackPathsAdded(
                operationContext.id,
                setOf(lookup as APathLookup<*>)
            )

            // Build report
            reportBuilder.setSubjectPath(createdPath)

            @Suppress("UNCHECKED_CAST")
            reportBuilder.addPathEvent(
                FileSystemEvent.Added(
                    operationId = operationContext.id,
                    paths = setOf(lookup as APathLookup<*>)
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
            command: ExplorerCommand.CreateTextFile,
        ): CreateTextFileOperation
    }
}
