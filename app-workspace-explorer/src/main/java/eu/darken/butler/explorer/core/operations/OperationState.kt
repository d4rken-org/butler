package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.operations.Operation
import kotlin.time.Clock
import kotlin.time.Instant

sealed interface OperationState {
    val operationId: Operation.Id

    data class OnGoing(
        override val operationId: Operation.Id,
        val startedAt: Instant,
        val operationProgress: Progress.Data = Progress.Data(),
        val actionProgress: Progress.Data? = null,
        val bytesProcessed: Long? = null,
    ) : OperationState

    data class AwaitingInput(
        override val operationId: Operation.Id,
        val startedAt: Instant,
        val awaitingSince: Instant = Clock.System.now(),
        val issue: Issue,
    ) : OperationState

    data class Completed(
        override val operationId: Operation.Id,
        val startedAt: Instant,
        val completedAt: Instant = Clock.System.now(),
        val metrics: OperationReport,
        val result: OperationResult,
    ) : OperationState
}
