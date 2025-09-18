package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.common.progress.Progress
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

sealed interface OperationState {
    val operationId: OperationId
    val startedAt: Instant

    data class OnGoing(
        override val operationId: OperationId,
        override val startedAt: Instant,
        val currentPath: APath? = null,
        val operationProgress: Progress.Data = Progress.Data(),
        val actionProgress: Progress.Data? = null,
        val itemsPerSecond: Float? = null,
        val bytesPerSecond: Long? = null,
    ) : OperationState

    data class AwaitingInput(
        override val operationId: OperationId,
        override val startedAt: Instant,
        val awaitingSince: Instant = Clock.System.now(),
        val issue: Issue,
    ) : OperationState

    data class Completed(
        override val operationId: OperationId,
        override val startedAt: Instant,
        val completedAt: Instant = Clock.System.now(),
        val result: OperationResult,
    ) : OperationState {
        val duration: Duration = completedAt - startedAt
    }
}
