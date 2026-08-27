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
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import eu.darken.butler.workspace.core.operations.buildTransferProgressMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock

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
            if (command.sources.size == 1) {
                val source = command.sources.first()
                cx.getString(
                    R.string.explorer_operation_move_description_single,
                    source.name,
                    source.parent?.userReadablePath?.get(cx) ?: source.userReadablePath.get(cx),
                    command.destination.path.userReadablePath.get(cx)
                )
            } else {
                cx.getQuantityString2(
                    R.plurals.explorer_operation_move_description,
                    command.sources.size,
                    command.sources.size,
                    command.destination.path.userReadablePath.get(cx)
                )
            }
        }
        override val kind = Operation.Metadata.Kind.MOVE
        override val intent = command.intent
        override val pathPlan = OperationPathPlan(
            targets = command.sources.toList(),
            destination = command.destination,
        )
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = channelFlow {
        log(tag) { "perform(): $command" }

        var stateActive = State.Active(startedAt = operationContext.startedAt)
        send(stateActive)

        val reportBuilder = MoveOperationReport.Builder()
        var lastPerformanceHistory: PerformanceHistory? = null

        val result = command.sources
            .move(
                gateway = gatewaySwitch,
                destination = command.destination.path,
                options = MoveAction.Options(
                    preserveAttributes = command.options.preserveAttributes,
                ),
                onIssue = { issue ->
                    send(
                        State.Waiting(
                            startedAt = operationContext.startedAt,
                            waitingSince = Clock.System.now(),
                            issue = issue,
                        )
                    )
                    val resolution = issueHandler.handleIssue(operationContext.id, issue) as PathActionIssue.Resolution
                    send(stateActive)
                    resolution
                },
            )
            .onEach { moveState ->
                if (moveState !is MoveAction.State.Active<*, *, *, *>) return@onEach

                // Extract performance history from low-level operation
                val perfHistory = moveState.primaryProgress.extra as? PerformanceHistory
                lastPerformanceHistory = perfHistory

                val metrics = buildTransferProgressMetrics(
                    performanceHistory = perfHistory,
                    totalBytes = moveState.totalBytes,
                    processedBytes = moveState.movedBytes,
                    currentFileSize = moveState.currentFileSize,
                    currentFileBytes = moveState.currentFileBytes,
                    currentFileStartTime = moveState.currentFileStartTime,
                    truncateItemSpeed = true,
                    requireTotalBytesForEta = true,
                )

                // Build enhanced primary progress with overall metrics
                val enhancedPrimary = moveState.primaryProgress.copy(
                    secondary = metrics.overall ?: moveState.primaryProgress.secondary
                )

                // Build enhanced secondary progress with file metrics
                val enhancedSecondary = moveState.secondaryProgress?.let { secondaryProgress ->
                    secondaryProgress.copy(
                        secondary = metrics.currentFile ?: secondaryProgress.secondary,
                        extra = mapOf(
                            "overallBytesSpeed" to metrics.overallBytesSpeed,
                            "overallItemsSpeed" to metrics.overallItemsSpeed.toLong(),
                            "fileSpeed" to metrics.fileSpeed,
                            "overallEta" to metrics.overallEta,
                            "fileEta" to metrics.fileEta
                        )
                    )
                }

                stateActive = stateActive.copy(
                    primaryProgress = enhancedPrimary,
                    secondaryProgress = enhancedSecondary,
                    performanceHistory = perfHistory,
                )
                send(stateActive)
            }
            .last()

        result as MoveAction.State.Completed<*, *, *, *>

        // Track filesystem changes - sources were removed.
        // Use movedFiles (not command.sources) so skipped files aren't reported as removed.
        val movedSources = result.movedFiles.map { it.first }.toSet()

        fileSystemHinter.trackPathsRemoved(operationContext.id, movedSources.toSet())

        val movedDestinations = result.movedFiles.map { it.second }.toSet()
        fileSystemHinter.trackPathsAdded(operationContext.id, movedDestinations.toSet())

        // Identified by the source it pairs with, so a directory move names the top-level
        // destination instead of whichever descendant the engine happened to move first.
        val firstSource = command.sources.firstOrNull()
        reportBuilder.setSubjectPath(
            result.movedFiles.firstOrNull { (source, _) -> source.lookedUp == firstSource }?.second?.lookedUp
        )

        // Build report
        reportBuilder.addMovedItems(result.movedFiles)
        reportBuilder.setSkipped(result.skippedFiles)
        reportBuilder.setBytesMoved(result.bytesMoved)
        reportBuilder.setPerformanceHistory(lastPerformanceHistory)

        send(
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