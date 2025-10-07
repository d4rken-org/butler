package eu.darken.butler.explorer.core.operations

import android.text.format.Formatter
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
import kotlin.time.Instant
import kotlin.time.TimeSource

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

        data class SpeedSample(
            val timestamp: Instant,
            val bytesPerSecond: Long
        )

        val speedHistory = ArrayDeque<SpeedSample>(30) // 30 samples max
        var lastCopiedBytes = 0L
        var lastSpeedUpdate = TimeSource.Monotonic.markNow()

        var stateActive = State.Active(startedAt = operationContext.startedAt)
        emit(stateActive)

        val reportBuilder = CopyOperationReport.Builder()

        val result = command.sources
            .copy(
                gateway = gatewaySwitch,
                destination = command.destination,
                options = CopyAction.Options(
                    preserveAttributes = command.options.preserveAttributes,
                    followSymlinks = command.options.followSymlinks,
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
                if (copyState !is CopyAction.State.Progress<APath, APathLookup<APath>>) return@onEach
                log(tag) { "Progress: $copyState" }

                val now = Clock.System.now()
                val elapsed = lastSpeedUpdate.elapsedNow().inWholeMilliseconds / 1000.0

                // Calculate instantaneous speed (every ~1 second)
                if (elapsed >= 1.0) {
                    val bytesDelta = copyState.copiedBytes - lastCopiedBytes
                    val currentSpeed = (bytesDelta / elapsed).toLong()

                    speedHistory.addLast(SpeedSample(now, currentSpeed))
                    if (speedHistory.size > 30) speedHistory.removeFirst()

                    lastCopiedBytes = copyState.copiedBytes
                    lastSpeedUpdate = TimeSource.Monotonic.markNow()
                }

                // Calculate overall metrics (from speed history)
                val avgSpeed = if (speedHistory.isNotEmpty()) {
                    speedHistory.map { it.bytesPerSecond }.average().toLong()
                } else 0L

                val overallEta = if (avgSpeed > 0 && copyState.totalBytes > 0) {
                    val remaining = copyState.totalBytes - copyState.copiedBytes
                    (remaining / avgSpeed) // seconds
                } else null

                // Calculate per-file metrics
                val fileStartTime = copyState.currentFileStartTime
                val (fileSpeed, fileEta) = if (fileStartTime != null && copyState.currentFileSize > 0) {
                    val fileElapsed = (now - fileStartTime).inWholeMilliseconds / 1000.0
                    if (fileElapsed > 0) {
                        val speed = (copyState.currentFileBytes / fileElapsed).toLong()
                        val remaining = copyState.currentFileSize - copyState.currentFileBytes
                        val eta = if (speed > 0) (remaining / speed).toLong() else null
                        speed to eta
                    } else 0L to null
                } else 0L to null

                // Format overall metrics for primary progress
                val overallMetrics = if (avgSpeed > 0) {
                    caString { ctx ->
                        val speedFormatted = Formatter.formatShortFileSize(ctx, avgSpeed)
                        val etaPart = if (overallEta != null) " • ${overallEta}s remaining" else ""
                        "$speedFormatted/s$etaPart"
                    }
                } else null

                // Format per-file metrics for secondary progress
                val fileMetrics = if (fileSpeed > 0) {
                    caString { ctx ->
                        val speedFormatted = Formatter.formatShortFileSize(ctx, fileSpeed)
                        val etaPart = if (fileEta != null) " • ${fileEta}s remaining" else ""
                        "$speedFormatted/s$etaPart"
                    }
                } else null

                // Build enhanced primary progress with overall metrics
                val enhancedPrimary = copyState.primaryProgress.copy(
                    secondary = overallMetrics ?: copyState.primaryProgress.secondary
                )

                // Build enhanced secondary progress with file metrics
                val enhancedSecondary = copyState.secondaryProgress?.let { secondaryProgress ->
                    secondaryProgress.copy(
                        secondary = fileMetrics ?: secondaryProgress.secondary,
                        extra = mapOf(
                            "overallSpeed" to avgSpeed,
                            "fileSpeed" to fileSpeed,
                            "speedHistory" to speedHistory.toList(),
                            "overallEta" to overallEta,
                            "fileEta" to fileEta
                        )
                    )
                }

                stateActive = stateActive.copy(
                    primaryProgress = enhancedPrimary,
                    secondaryProgress = enhancedSecondary,
                )
                emit(stateActive)
            }
            .last()

        result as CopyAction.State.Result<APath, APathLookup<APath>>

        val copiedDestinations = result.copied.map { it.second }.toSet()
        val copiedLookups = copiedDestinations.map { gatewaySwitch.lookup(it) }
        fileSystemHinter.trackPathsAdded(copiedLookups.toSet())

        reportBuilder.addCopiedItems(copiedLookups)
        reportBuilder.setSkipped(result.skipped)
        reportBuilder.setCopiedBytes(result.copiedBytes)

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