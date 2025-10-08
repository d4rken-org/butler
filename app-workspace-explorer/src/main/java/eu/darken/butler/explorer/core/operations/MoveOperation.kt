package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.DriveFileMove
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.move
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

class MoveOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Move,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Move")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.AutoMirrored.TwoTone.DriveFileMove
        override val title = R.string.explorer_operation_move_title.toCaString()
        override val description = caString { cx ->
            cx.getQuantityString2(
                R.plurals.explorer_operation_move_description,
                command.sources.size,
                command.sources.size,
                command.destination.userReadablePath.get(cx),
            )
        }
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = flow {
        log(tag) { "perform(): $command" }

        var stateActive = State.Active(startedAt = operationContext.startedAt)
        emit(stateActive)

        val reportBuilder = MoveOperationReport.Builder()

        command.sources
            .move(
                gateway = gatewaySwitch,
                destination = command.destination,
                options = MoveAction.Options(
                    preserveAttributes = command.options.preserveAttributes,
                    onIssue = { issue ->
                        emit(
                            State.Waiting(
                                startedAt = operationContext.startedAt,
                                waitingSince = kotlin.time.Clock.System.now(),
                                issue = issue,
                            )
                        )
                        issueHandler.handleIssue(operationContext.id, issue) as PathActionIssue.Resolution
                    },
                ),
            )
            .onEach { moveState ->
                when (moveState) {
                    is MoveAction.State.Progress<*> -> {
                        val primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                            primary = caString { it.getString(R.string.explorer_operation_move_progress_title) },
                            secondary = caString {
                                it.getQuantityString2(
                                    R.plurals.explorer_operation_progress_sources,
                                    moveState.sourcesCompleted,
                                    moveState.sourcesCompleted,
                                    moveState.totalSources,
                                )
                            },
                            count = eu.darken.butler.common.progress.Progress.Count.Counter(
                                current = moveState.filesProcessed,
                                max = moveState.totalFiles,
                            ),
                        )

                        val secondaryProgress = if (moveState.bytesMoved > 0) {
                            eu.darken.butler.common.progress.Progress.Data(
                                primary = moveState.currentSource.name.toCaString(),
                                secondary = caString {
                                    val bytesFormatted =
                                        android.text.format.Formatter.formatShortFileSize(it, moveState.bytesMoved)
                                    it.getString(R.string.explorer_operation_progress_bytes_moved, bytesFormatted)
                                },
                            )
                        } else null

                        stateActive = stateActive.copy(
                            primaryProgress = primaryProgress,
                            secondaryProgress = secondaryProgress,
                        )
                        emit(stateActive)
                    }
                    is MoveAction.State.Result<*> -> {
                        // Track filesystem changes - sources were removed
                        val movedSources = moveState.movedFiles.map { it.first }.toSet()
                        val sourceLookupsForHinter = movedSources.map { path ->
                            // Create a minimal lookup for removed paths (may no longer exist)
                            object : eu.darken.butler.common.files.APathLookup<eu.darken.butler.common.files.APath> {
                                override val lookedUp = path
                                override val fileType = eu.darken.butler.common.files.metadata.FileType.UNKNOWN
                                override val size = 0L
                                override val modifiedAt = kotlin.time.Instant.DISTANT_PAST
                                override val target: eu.darken.butler.common.files.APath? = null
                            }
                        }
                        fileSystemHinter.trackPathsRemoved(operationContext.id, sourceLookupsForHinter.toSet())

                        val movedDestinations = moveState.movedFiles.map { it.second }.toSet()
                        val movedLookups = movedDestinations.map { gatewaySwitch.lookup(it) }
                        fileSystemHinter.trackPathsAdded(operationContext.id, movedLookups.toSet())

                        // Build report
                        val sourceAndDestLookup = moveState.movedFiles.map { (source, dest) ->
                            source to gatewaySwitch.lookup(dest)
                        }
                        reportBuilder.addMovedItems(sourceAndDestLookup)
                        reportBuilder.setSkipped(moveState.skippedFiles)
                        reportBuilder.setBytesMoved(moveState.bytesMoved)
                    }
                }
            }
            .last()

        emit(
            State.Completed(
                startedAt = operationContext.startedAt,
                report = reportBuilder.build(),
            )
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.Move,
        ): MoveOperation
    }
}