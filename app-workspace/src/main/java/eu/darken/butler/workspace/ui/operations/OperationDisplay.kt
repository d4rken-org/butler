package eu.darken.butler.workspace.ui.operations

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.error.causes
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
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
) {
    sealed interface State {
        data object Queued : State
        data class Running(
            val primaryProgress: Progress.Data = Progress.Data(),
            val secondaryProgress: Progress.Data? = null,
            val canPause: Boolean = false,
        ) : State

        data class Waiting(val reason: CaString) : State
        data class Completed(
            val summary: CaString,
            val completedAt: Instant,
            val report: Operation.Report,
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
        state = when (state) {
            is Operation.State.Queued -> OperationDisplay.State.Queued
            is Operation.State.Active -> OperationDisplay.State.Running(
                primaryProgress = state.primaryProgress,
                secondaryProgress = state.secondaryProgress,
                canPause = canPause,
            )
            is Operation.State.Waiting -> OperationDisplay.State.Waiting(reason = state.reason)
            is Operation.State.Completed -> {
                val errorValue = state.error
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
                        )
                    }
                }
            }
            else -> throw IllegalStateException("Unknown state: $state")
        },
    )
}
