package eu.darken.butler.workspace.core.operations

import android.text.format.Formatter
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.formatDuration
import eu.darken.butler.common.formatItemSpeed
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class CoreDeleteExecutor @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val trashManager: TrashManager,
    private val trashSettings: TrashSettings,
) {

    fun execute(
        targets: Set<APath<*>>,
        config: Config,
    ): Flow<State> = flow {
        log(config.tag) { "execute(): Deleting ${targets.size} items" }

        var lastPerformanceHistory: PerformanceHistory? = null

        // Check if trash is enabled and paths are supported (forcePermDelete bypasses trash)
        val trashEnabled = trashSettings.enabled.value() && !config.forcePermDelete
        val supportsTrash = targets.all { it is LocalPath }

        // Initialize collections for tracking items during trash/delete operations
        val itemsForDirectDelete = mutableListOf<APath<*>>()
        val skippedItems = mutableSetOf<APathLookup<*>>()
        var trashedItems = emptySet<APathLookup<*>>()
        var trashedBytes = 0L

        if (trashEnabled && supportsTrash) {
            log(config.tag, INFO) { "Attempting to move ${targets.size} items to trash" }

            // Calculate total size of all items (using du for accurate recursive size)
            val maxTrashSize = trashSettings.maxTrashSize.value()
            var totalSize = 0L
            for (target in targets) {
                totalSize += gatewaySwitch.du(target)
            }

            // Check if total exceeds trash limit (all-or-nothing approach)
            if (totalSize > maxTrashSize) {
                log(config.tag, INFO) {
                    "Total size ($totalSize bytes) exceeds trash limit ($maxTrashSize bytes)"
                }

                val issue = PathActionIssue.TrashSizeLimitExceeded(
                    totalSize = totalSize,
                    itemCount = targets.size,
                    trashMaxSize = maxTrashSize,
                )
                emit(State.Waiting(issue = issue))
                val resolution = config.onIssue(issue)

                when (resolution) {
                    is PathActionIssue.TrashSizeLimitExceeded.Resolution.DeletePermanently -> {
                        itemsForDirectDelete.addAll(targets)
                    }
                    is PathActionIssue.TrashSizeLimitExceeded.Resolution.Cancel -> {
                        throw CancellationException("User cancelled operation")
                    }
                }
            } else {
                // Total fits within trash limit, move all items to trash
                try {
                    trashManager.moveToTrash(paths = targets.toList())
                        .onEach { trashState ->
                            when (trashState) {
                                is TrashManager.TrashMoveState.Active -> {
                                    val activeState = buildTrashActiveState(trashState)
                                    lastPerformanceHistory = activeState.performanceHistory
                                    emit(activeState)
                                }

                                is TrashManager.TrashMoveState.Completed -> {
                                    val trashReport = trashState.report

                                    if (trashReport.failedToMove.isNotEmpty()) {
                                        log(config.tag, WARN) {
                                            "${trashReport.failedToMove.size} items failed to move to trash"
                                        }

                                        // Ask user what to do with failed items
                                        val issue = PathActionIssue.TrashMoveFailed(
                                            failedItems = trashReport.failedToMove.toList(),
                                        )
                                        emit(State.Waiting(issue = issue))
                                        val resolution = config.onIssue(issue)

                                        when (resolution) {
                                            is PathActionIssue.TrashMoveFailed.Resolution.DeletePermanently -> {
                                                itemsForDirectDelete.addAll(trashReport.failedToMove.map { it.lookedUp })
                                            }
                                            is PathActionIssue.TrashMoveFailed.Resolution.Skip -> {
                                                skippedItems.addAll(trashReport.failedToMove)
                                            }
                                            is PathActionIssue.TrashMoveFailed.Resolution.Cancel -> {
                                                throw CancellationException("User cancelled operation")
                                            }
                                        }
                                    }

                                    if (trashReport.movedToTrash.isNotEmpty()) {
                                        log(config.tag, INFO) {
                                            "Successfully moved ${trashReport.movedToTrash.size} items to trash"
                                        }
                                        trashedItems = trashReport.movedToTrash
                                        trashedBytes = trashReport.bytesMoved

                                        @Suppress("UNCHECKED_CAST")
                                        config.onPathsRemoved(trashedItems as Set<APathLookup<APath<*>>>)
                                    }
                                }
                            }
                        }
                        .last()
                } catch (e: Exception) {
                    log(config.tag, WARN) { "Trash move failed: ${e.asLog()}" }

                    // Ask user what to do when entire trash operation fails
                    val failedLookups = targets.map { path ->
                        gatewaySwitch.lookup(path, LookupOptions(fallbackToUnknown = true))
                    }
                    val issue = PathActionIssue.TrashMoveFailed(
                        failedItems = failedLookups,
                        exception = e,
                    )
                    emit(State.Waiting(issue = issue))
                    val resolution = config.onIssue(issue)

                    when (resolution) {
                        is PathActionIssue.TrashMoveFailed.Resolution.DeletePermanently -> {
                            itemsForDirectDelete.addAll(targets)
                        }
                        is PathActionIssue.TrashMoveFailed.Resolution.Skip -> {
                            skippedItems.addAll(failedLookups)
                        }
                        is PathActionIssue.TrashMoveFailed.Resolution.Cancel -> {
                            throw CancellationException("User cancelled operation")
                        }
                    }
                }
            }

            // If no direct deletes needed and we have trashed items, we're done
            if (itemsForDirectDelete.isEmpty() && trashedItems.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                emit(
                    State.Completed(
                        result = Result(
                            deleted = emptySet(),
                            trashed = trashedItems as Set<APathLookup<APath<*>>>,
                            skipped = skippedItems,
                            bytesFreed = trashedBytes,
                            performanceHistory = null,
                        )
                    )
                )
                return@flow
            }

            // If we have items for direct delete, fall through to direct deletion below
            if (itemsForDirectDelete.isNotEmpty()) {
                log(config.tag, INFO) { "Performing direct delete for ${itemsForDirectDelete.size} items" }
            }
        } else {
            if (trashEnabled && !supportsTrash) {
                log(config.tag, INFO) {
                    "Trash enabled but paths not supported (non-LocalPath), performing direct delete"
                }
            } else {
                log(config.tag) { "Trash disabled, performing direct delete" }
            }
        }

        // Direct deletion (if recycle bin failed, disabled, or items too large)
        val targetsForDirectDelete = if (trashEnabled && supportsTrash) {
            // Use itemsForDirectDelete if we went through the trash path
            itemsForDirectDelete.toSet()
        } else {
            targets
        }

        if (targetsForDirectDelete.isEmpty()) {
            // Nothing to delete directly - return with skipped items if any
            @Suppress("UNCHECKED_CAST")
            emit(
                State.Completed(
                    result = Result(
                        deleted = emptySet(),
                        trashed = trashedItems as Set<APathLookup<APath<*>>>,
                        skipped = skippedItems,
                        bytesFreed = trashedBytes,
                        performanceHistory = null,
                    )
                )
            )
            return@flow
        }

        targetsForDirectDelete
            .delete(
                gateway = gatewaySwitch,
                options = DeleteAction.Options(
                    recursive = true,
                    onIssue = { issue ->
                        emit(State.Waiting(issue = issue))
                        val resolution = config.onIssue(issue)
                        resolution
                    }
                )
            )
            .onEach { deleteState ->
                when (deleteState) {
                    is DeleteAction.State.Active<APath<*>, APathLookup<APath<*>>> -> {
                        val activeState = buildActiveState(deleteState)
                        lastPerformanceHistory = activeState.performanceHistory
                        emit(activeState)
                    }

                    is DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>> -> {
                        config.onPathsRemoved(deleteState.deleted)
                    }
                }
            }
            .last()
            .let { finalState ->
                @Suppress("UNCHECKED_CAST")
                val completedState = finalState as DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>

                @Suppress("UNCHECKED_CAST")
                emit(
                    State.Completed(
                        result = Result(
                            deleted = completedState.deleted,
                            trashed = trashedItems as Set<APathLookup<APath<*>>>,
                            skipped = completedState.skipped + skippedItems,
                            bytesFreed = completedState.deleted.mapNotNull { it.size }.sum() + trashedBytes,
                            performanceHistory = lastPerformanceHistory,
                        )
                    )
                )
            }
    }

    private fun buildActiveState(
        deleteState: DeleteAction.State.Active<APath<*>, APathLookup<APath<*>>>
    ): State.Active {
        // Extract performance history from low-level operation
        val perfHistory = deleteState.primaryProgress.extra as? PerformanceHistory

        // Calculate overall metrics using PerformanceHistory
        val avgItemsSpeed = perfHistory?.getRecentItemsPerSecond()?.toLong() ?: 0L
        val avgBytesSpeed = perfHistory?.getRecentBytesPerSecond() ?: 0L

        val overallEta = if (avgItemsSpeed > 0 && deleteState.primaryProgress.count.max > 0) {
            val remaining =
                deleteState.primaryProgress.count.max - deleteState.primaryProgress.count.current
            (remaining / avgItemsSpeed) // seconds
        } else null

        // Format overall metrics for primary progress
        val overallMetrics = if (avgItemsSpeed > 0 || avgBytesSpeed > 0) {
            caString { ctx ->
                val parts = mutableListOf<String>()
                if (avgItemsSpeed > 0) {
                    parts.add(formatItemSpeed(ctx, avgItemsSpeed.toDouble()))
                }
                if (avgBytesSpeed > 0) {
                    val bytesFormatted = Formatter.formatShortFileSize(ctx, avgBytesSpeed)
                    parts.add(
                        ctx.getString(
                            eu.darken.butler.workspace.R.string.workspace_operation_progress_bytes_speed_freed,
                            bytesFormatted
                        )
                    )
                }
                val speedPart = parts.joinToString(" • ")
                val etaPart = if (overallEta != null && overallEta > 0) {
                    val duration = formatDuration(ctx, overallEta.seconds)
                    " • " + ctx.getString(
                        eu.darken.butler.workspace.R.string.workspace_operation_progress_time_remaining,
                        duration
                    )
                } else ""
                speedPart + etaPart
            }
        } else null

        // Build enhanced primary progress with overall metrics
        val enhancedPrimary = deleteState.primaryProgress.copy(
            secondary = overallMetrics ?: deleteState.primaryProgress.secondary
        )

        // Build secondary progress with bytes freed info
        val secondaryProgress = if (deleteState.deletedBytes > 0) {
            Progress.Data(
                primary = deleteState.target.lookedUp.name.toCaString(),
                secondary = caString { ctx ->
                    val bytesFormatted = Formatter.formatShortFileSize(ctx, deleteState.deletedBytes)
                    ctx.getString(
                        eu.darken.butler.workspace.R.string.workspace_operation_progress_bytes_freed,
                        bytesFormatted
                    )
                }
            )
        } else null

        return State.Active(
            primaryProgress = enhancedPrimary,
            secondaryProgress = secondaryProgress,
            performanceHistory = perfHistory,
        )
    }

    private fun buildTrashActiveState(
        trashState: TrashManager.TrashMoveState.Active
    ): State.Active {
        // Extract performance history from low-level move operation
        val perfHistory = trashState.primaryProgress.extra as? PerformanceHistory

        // Calculate overall metrics using PerformanceHistory
        val avgItemsSpeed = perfHistory?.getRecentItemsPerSecond()?.toLong() ?: 0L
        val avgBytesSpeed = perfHistory?.getRecentBytesPerSecond() ?: 0L

        val overallEta = if (avgItemsSpeed > 0 && trashState.itemsTotal > 0) {
            val remaining = trashState.itemsTotal - trashState.itemsProcessed
            (remaining / avgItemsSpeed) // seconds
        } else null

        // Format overall metrics for primary progress
        val overallMetrics = if (avgItemsSpeed > 0 || avgBytesSpeed > 0) {
            caString { ctx ->
                val parts = mutableListOf<String>()
                if (avgItemsSpeed > 0) {
                    parts.add(formatItemSpeed(ctx, avgItemsSpeed.toDouble()))
                }
                if (avgBytesSpeed > 0) {
                    val bytesFormatted = Formatter.formatShortFileSize(ctx, avgBytesSpeed)
                    parts.add("$bytesFormatted/s")
                }
                val speedPart = parts.joinToString(" • ")
                val etaPart = if (overallEta != null && overallEta > 0) {
                    val duration = formatDuration(ctx, overallEta.seconds)
                    " • " + ctx.getString(
                        eu.darken.butler.workspace.R.string.workspace_operation_progress_time_remaining,
                        duration
                    )
                } else ""
                speedPart + etaPart
            }
        } else null

        // Build enhanced primary progress
        val enhancedPrimary = trashState.primaryProgress.copy(
            primary = eu.darken.butler.workspace.R.string.workspace_operation_progress_moving_to_trash.toCaString(),
            secondary = overallMetrics ?: trashState.primaryProgress.secondary,
        )

        // Build secondary progress showing current file
        val secondaryProgress = Progress.Data(
            primary = trashState.currentItem.lookedUp.name.toCaString(),
            count = if (trashState.totalBytesEstimate > 0) {
                Progress.Count.Size(
                    current = trashState.bytesMovedSoFar,
                    max = trashState.totalBytesEstimate,
                )
            } else {
                Progress.Count.None()
            },
        )

        return State.Active(
            primaryProgress = enhancedPrimary,
            secondaryProgress = secondaryProgress,
            performanceHistory = perfHistory,
        )
    }

    data class Config(
        val tag: String,
        val forcePermDelete: Boolean = false,
        val onIssue: suspend (PathActionIssue) -> PathActionIssue.Resolution,
        val onPathsRemoved: suspend (Set<APathLookup<*>>) -> Unit = {},
    )

    sealed interface State {
        data class Active(
            val primaryProgress: Progress.Data,
            val secondaryProgress: Progress.Data?,
            val performanceHistory: PerformanceHistory?,
        ) : State

        data class Waiting(
            val issue: PathActionIssue,
        ) : State

        data class Completed(
            val result: Result,
        ) : State
    }

    data class Result(
        val deleted: Set<APathLookup<*>>,
        val trashed: Set<APathLookup<*>>,
        val skipped: Set<APathLookup<*>>,
        val bytesFreed: Long,
        val performanceHistory: PerformanceHistory?,
    )
}
