package eu.darken.butler.workspace.ui.operations

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.error.causes
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

data class OperationDisplay(
    val id: Operation.Id,
    val startedAt: Instant,
    val icon: ImageVector,
    val title: CaString,
    val description: CaString,
    val state: State = State.Queued,
    val canCancel: Boolean = false,
    val pathPlan: OperationPathPlan? = null,
) {
    sealed interface State {
        data object Queued : State
        data class Running(
            val primaryProgress: Progress.Data = Progress.Data(),
            val secondaryProgress: Progress.Data? = null,
            val canPause: Boolean = false,
            val performanceHistory: PerformanceHistory? = null,
        ) : State

        data class Waiting(val reason: CaString) : State
        data class Completed(
            val summary: CaString,
            val completedAt: Instant,
            val report: Operation.Report,
            val performanceHistory: PerformanceHistory? = null,
        ) : State

        data class Failed(
            val summary: CaString,
            val completedAt: Instant,
            val report: Operation.Report?,
        ) : State

        data class Cancelled(
            val completedAt: Instant,
            val report: Operation.Report?,
        ) : State
    }
}

fun ManagedOperation.toDisplayModel(): OperationDisplay {
    val state = this@toDisplayModel.state.value
    return OperationDisplay(
        id = id,
        startedAt = state.startedAt,
        icon = metadata.icon,
        title = metadata.title,
        description = metadata.description,
        canCancel = canCancel,
        pathPlan = metadata.pathPlan,
        state = when (state) {
            is Operation.State.Queued -> OperationDisplay.State.Queued
            is Operation.State.Active -> {
                // Extract performanceHistory from primaryProgress.extra where it's stored
                val performanceHistory = (state as? Operation.HasPerformanceHistory)?.performanceHistory
                OperationDisplay.State.Running(
                    primaryProgress = state.primaryProgress,
                    secondaryProgress = state.secondaryProgress,
                    canPause = canPause,
                    performanceHistory = performanceHistory,
                )
            }
            is Operation.State.Waiting -> OperationDisplay.State.Waiting(reason = state.reason)
            is Operation.State.Completed -> {
                val errorValue = state.error
                // Extract performanceHistory from report if available
                val performanceHistory = (state as? Operation.HasPerformanceHistory)?.performanceHistory
                when {
                    errorValue?.causes?.any { it is CancellationException } == true -> {
                        OperationDisplay.State.Cancelled(
                            completedAt = state.completedAt,
                            report = state.report,
                        )
                    }
                    errorValue != null -> {
                        OperationDisplay.State.Failed(
                            summary = state.summary,
                            completedAt = state.completedAt,
                            report = state.report,
                        )
                    }
                    else -> {
                        OperationDisplay.State.Completed(
                            summary = state.summary,
                            completedAt = state.completedAt,
                            report = state.report!!,
                            performanceHistory = performanceHistory,
                        )
                    }
                }
            }
            else -> throw IllegalStateException("Unknown state: $state")
        },
    )
}
