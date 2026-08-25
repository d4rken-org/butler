package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CopyAll
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.copy
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

class CopyOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Copy,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Copy")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.CopyAll
        override val title = R.string.explorer_operation_copy_title.toCaString()
        override val description = caString { cx ->
            if (command.sources.size == 1) {
                val source = command.sources.first()
                cx.getString(
                    R.string.explorer_operation_copy_description_single,
                    source.name,
                    source.parent?.userReadablePath?.get(cx) ?: source.userReadablePath.get(cx),
                    command.destination.path.userReadablePath.get(cx)
                )
            } else {
                cx.getQuantityString2(
                    R.plurals.explorer_operation_copy_description,
                    command.sources.size,
                    command.sources.size,
                    command.destination.path.userReadablePath.get(cx)
                )
            }
        }
        override val kind = Operation.Metadata.Kind.COPY
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

        val reportBuilder = CopyOperationReport.Builder()
        var lastPerformanceHistory: PerformanceHistory? = null

        val result = command.sources
            .copy(
                gateway = gatewaySwitch,
                destination = command.destination.path,
                options = CopyAction.Options(
                    preserveAttributes = command.options.preserveAttributes,
                    followSymlinks = command.options.followSymlinks,
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
            .onEach { copyState ->
                if (copyState !is CopyAction.State.Active<*, *, *, *>) return@onEach
                if (Bugs.isTrace) log(tag, VERBOSE) { "Progress: $copyState" }

                // Extract performance history from low-level operation
                val perfHistory = copyState.primaryProgress.extra as? PerformanceHistory
                lastPerformanceHistory = perfHistory

                val metrics = buildTransferProgressMetrics(
                    performanceHistory = perfHistory,
                    totalBytes = copyState.totalBytes,
                    processedBytes = copyState.copiedBytes,
                    currentFileSize = copyState.currentFileSize,
                    currentFileBytes = copyState.currentFileBytes,
                    currentFileStartTime = copyState.currentFileStartTime,
                    truncateItemSpeed = true,
                    requireTotalBytesForEta = true,
                )

                // Build enhanced primary progress with overall metrics
                val enhancedPrimary = copyState.primaryProgress.copy(
                    secondary = metrics.overall ?: copyState.primaryProgress.secondary
                )

                // Build enhanced secondary progress with file metrics
                val enhancedSecondary = copyState.secondaryProgress?.let { secondaryProgress ->
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

        result as CopyAction.State.Completed<*, *, *, *>

        val copiedDestinations = result.copied.map { it.second }.toSet()
        fileSystemHinter.trackPathsAdded(operationContext.id, copiedDestinations.toSet())

        reportBuilder.addCopiedItems(copiedDestinations)
        reportBuilder.setSkipped(result.skipped)
        reportBuilder.setCopiedBytes(result.copiedBytes)
        reportBuilder.setPerformanceHistory(lastPerformanceHistory)

        send(
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