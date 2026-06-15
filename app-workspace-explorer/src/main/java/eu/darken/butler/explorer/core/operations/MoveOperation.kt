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
import eu.darken.butler.common.formatByteSpeed
import eu.darken.butler.common.formatItemSpeed
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
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
                    command.destination.userReadablePath.get(cx)
                )
            } else {
                cx.getQuantityString2(
                    R.plurals.explorer_operation_move_description,
                    command.sources.size,
                    command.sources.size,
                    command.destination.userReadablePath.get(cx)
                )
            }
        }
        override val kind = Operation.Metadata.Kind.MOVE
        override val intent = command.intent
        override val intendedPaths = command.sources + command.destination
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
                destination = command.destination,
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

                // Calculate overall metrics using PerformanceHistory
                val avgBytesSpeed = perfHistory?.getRecentBytesPerSecond() ?: 0L
                val avgItemsSpeed = perfHistory?.getRecentItemsPerSecond()?.toLong() ?: 0L

                val overallEta = if (avgBytesSpeed > 0 && moveState.totalBytes > 0) {
                    val remaining = moveState.totalBytes - moveState.movedBytes
                    (remaining / avgBytesSpeed) // seconds
                } else null

                // Calculate per-file metrics
                val now = Clock.System.now()
                val fileStartTime = moveState.currentFileStartTime
                val (fileSpeed, fileEta) = if (fileStartTime != null && moveState.currentFileSize > 0) {
                    val fileElapsed = (now - fileStartTime).inWholeMilliseconds / 1000.0
                    if (fileElapsed > 0) {
                        val speed = (moveState.currentFileBytes / fileElapsed).toLong()
                        val remaining = moveState.currentFileSize - moveState.currentFileBytes
                        val eta = if (speed > 0) (remaining / speed).toLong() else null
                        speed to eta
                    } else 0L to null
                } else 0L to null

                // Format overall metrics for primary progress
                val overallMetrics = if (avgBytesSpeed > 0) {
                    caString { ctx ->
                        val bytesSpeedPart = formatByteSpeed(ctx, avgBytesSpeed)

                        val itemsSpeedPart = if (avgItemsSpeed > 0) {
                            " • " + formatItemSpeed(ctx, avgItemsSpeed.toDouble())
                        } else ""

                        val etaPart = if (overallEta != null) {
                            val duration = ctx.getQuantityString2(
                                eu.darken.butler.common.R.plurals.common_duration_seconds_full,
                                overallEta.toInt(),
                                overallEta
                            )
                            " • " + ctx.getString(
                                eu.darken.butler.workspace.R.string.workspace_operation_progress_time_remaining,
                                duration
                            )
                        } else ""

                        bytesSpeedPart + itemsSpeedPart + etaPart
                    }
                } else null

                // Format per-file metrics for secondary progress
                val fileMetrics = if (fileSpeed > 0) {
                    caString { ctx ->
                        val speedPart = formatByteSpeed(ctx, fileSpeed)
                        val etaPart = if (fileEta != null) {
                            val duration = ctx.getQuantityString2(
                                eu.darken.butler.common.R.plurals.common_duration_seconds_full,
                                fileEta.toInt(),
                                fileEta
                            )
                            " • " + ctx.getString(
                                eu.darken.butler.workspace.R.string.workspace_operation_progress_time_remaining,
                                duration
                            )
                        } else ""
                        speedPart + etaPart
                    }
                } else null

                // Build enhanced primary progress with overall metrics
                val enhancedPrimary = moveState.primaryProgress.copy(
                    secondary = overallMetrics ?: moveState.primaryProgress.secondary
                )

                // Build enhanced secondary progress with file metrics
                val enhancedSecondary = moveState.secondaryProgress?.let { secondaryProgress ->
                    secondaryProgress.copy(
                        secondary = fileMetrics ?: secondaryProgress.secondary,
                        extra = mapOf(
                            "overallBytesSpeed" to avgBytesSpeed,
                            "overallItemsSpeed" to avgItemsSpeed,
                            "fileSpeed" to fileSpeed,
                            "overallEta" to overallEta,
                            "fileEta" to fileEta
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