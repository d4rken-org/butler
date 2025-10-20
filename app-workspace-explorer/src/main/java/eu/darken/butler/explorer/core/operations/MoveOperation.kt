package eu.darken.butler.explorer.core.operations

import android.text.format.Formatter
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
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import java.util.ArrayDeque
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

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
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = flow {
        log(tag) { "perform(): $command" }

        var stateActive = State.Active(startedAt = operationContext.startedAt)
        emit(stateActive)

        data class SpeedSample(
            val timestamp: Instant,
            val bytesPerSecond: Long,
            val itemsPerSecond: Long
        )

        val speedHistory = ArrayDeque<SpeedSample>(30) // 30 samples max
        var lastMovedBytes = 0L
        var lastProcessedItems = 0L
        var lastSpeedUpdate = TimeSource.Monotonic.markNow()

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
                    emit(
                        State.Waiting(
                            startedAt = operationContext.startedAt,
                            waitingSince = Clock.System.now(),
                            issue = issue,
                        )
                    )
                    val resolution = issueHandler.handleIssue(operationContext.id, issue) as PathActionIssue.Resolution
                    emit(stateActive)
                    resolution
                },
            )
            .onEach { moveState ->
                if (moveState !is MoveAction.State.Active<*, *, *, *>) return@onEach

                val now = Clock.System.now()
                val elapsed = lastSpeedUpdate.elapsedNow().inWholeMilliseconds / 1000.0

                // Calculate instantaneous speed (every ~1 second)
                if (elapsed >= 1.0) {
                    val bytesDelta = moveState.movedBytes - lastMovedBytes
                    val currentBytesSpeed = (bytesDelta / elapsed).toLong()

                    val currentItems = moveState.primaryProgress.count.current
                    val itemsDelta = currentItems - lastProcessedItems
                    val currentItemsSpeed = (itemsDelta / elapsed).toLong()

                    speedHistory.addLast(SpeedSample(now, currentBytesSpeed, currentItemsSpeed))
                    if (speedHistory.size > 30) speedHistory.removeFirst()

                    lastMovedBytes = moveState.movedBytes
                    lastProcessedItems = currentItems
                    lastSpeedUpdate = TimeSource.Monotonic.markNow()
                }

                // Calculate overall metrics (from speed history)
                val avgBytesSpeed = if (speedHistory.isNotEmpty()) {
                    speedHistory.map { it.bytesPerSecond }.average().toLong()
                } else 0L

                val avgItemsSpeed = if (speedHistory.isNotEmpty()) {
                    speedHistory.map { it.itemsPerSecond }.average().toLong()
                } else 0L

                val overallEta = if (avgBytesSpeed > 0 && moveState.totalBytes > 0) {
                    val remaining = moveState.totalBytes - moveState.movedBytes
                    (remaining / avgBytesSpeed) // seconds
                } else null

                // Calculate per-file metrics
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
                        val bytesSpeedFormatted = Formatter.formatShortFileSize(ctx, avgBytesSpeed)
                        val bytesSpeedPart =
                            ctx.getString(R.string.explorer_operation_progress_bytes_speed, bytesSpeedFormatted)

                        val itemsSpeedPart = if (avgItemsSpeed > 0) {
                            " • " + ctx.getQuantityString2(
                                eu.darken.butler.workspace.R.plurals.workspace_operation_progress_items_speed,
                                avgItemsSpeed.toInt(),
                                avgItemsSpeed
                            )
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
                        val speedFormatted = Formatter.formatShortFileSize(ctx, fileSpeed)
                        val speedPart = ctx.getString(R.string.explorer_operation_progress_bytes_speed, speedFormatted)
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
                            "speedHistory" to speedHistory.toList(),
                            "overallEta" to overallEta,
                            "fileEta" to fileEta
                        )
                    )
                }

                // Extract performance history from low-level operation
                val perfHistory = moveState.primaryProgress.extra as? PerformanceHistory
                lastPerformanceHistory = perfHistory

                stateActive = stateActive.copy(
                    primaryProgress = enhancedPrimary,
                    secondaryProgress = enhancedSecondary,
                    performanceHistory = perfHistory,
                )
                emit(stateActive)
            }
            .last()

        result as MoveAction.State.Completed<*, *, *, *>

        // Track filesystem changes - sources were removed
        // TODO don't we have the lookup from earlier?
        val movedSources = result.movedFiles.map { it.first }.toSet()

        fileSystemHinter.trackPathsRemoved(operationContext.id, movedSources.toSet())

        val movedDestinations = result.movedFiles.map { it.second }.toSet()
        fileSystemHinter.trackPathsAdded(operationContext.id, movedDestinations.toSet())

        // Build report
        reportBuilder.addMovedItems( result.movedFiles)
        reportBuilder.setSkipped(result.skippedFiles)
        reportBuilder.setBytesMoved(result.bytesMoved)
        reportBuilder.setPerformanceHistory(lastPerformanceHistory)

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