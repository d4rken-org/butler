package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CopyAll
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
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.copy
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock

class CopyOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Copy,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Copy")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.CopyAll
        override val title = R.string.explorer_operation_copy_title.toCaString()
        override val description = caString { cx ->
            cx.getQuantityString2(
                R.plurals.explorer_operation_copy_description,
                command.sources.size,
                command.sources.size,
                command.destination.userReadablePath.get(cx)
            )
        }
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = flow {
        log(tag) { "perform(): $command" }

        var stateActive = State.Active(
            startedAt = operationContext.startedAt,
        )
        val reportBuilder = CopyOperationReport.Builder()

        command.sources
            .copy(
                gateway = gatewaySwitch,
                destination = command.destination,
                options = CopyAction.Options(
                    preserveAttributes = command.options.preserveAttributes,
                    onIssue = { issue ->
                        emit(
                            State.Waiting(
                                startedAt = operationContext.startedAt,
                                waitingSince = Clock.System.now(),
                                issue = issue,
                            )
                        )
                        issueHandler.handleIssue(operationContext.id, issue) as PathActionIssue.Resolution
                    }
                )
            )
            .onEach { copyState ->
                when (copyState) {
                    is CopyAction.State.Progress<APath, APathLookup<APath>> -> {
                        stateActive = stateActive.copy(
                            primaryProgress = copyState.primaryProgress,
                            secondaryProgress = copyState.secondaryProgress,
                        )
                        emit(stateActive)
                    }
                    is CopyAction.State.Result<APath, APathLookup<APath>> -> {
                        val copiedDestinations = copyState.copied.map { it.second }.toSet()
                        val copiedLookups = copiedDestinations.map { gatewaySwitch.lookup(it) }
                        fileSystemHinter.trackPathsAdded(copiedLookups.toSet())

                        reportBuilder.addCopiedItems(copiedLookups)
                        reportBuilder.setSkipped(copyState.skipped)
                        reportBuilder.setBytesCopied(copyState.bytesCopied)
                    }
                }
            }
            .last()

        emit(
            State.Completed(
                startedAt = operationContext.startedAt,
                report = reportBuilder.build()
            )
        )
    }


    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.Copy,
        ): CopyOperation
    }
}