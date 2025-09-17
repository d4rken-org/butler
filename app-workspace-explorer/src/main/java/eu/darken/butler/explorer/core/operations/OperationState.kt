package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.operations.conflicts.Conflict
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

sealed interface OperationState {
    val operationId: OperationId
    val startTime: Instant

    data class OnGoing(
        override val operationId: OperationId,
        override val startTime: Instant,
        val progress: Progress.Data = Progress.Data(),
        val currentItem: APath? = null,
        val processedCount: Int = 0,
        val totalCount: Int? = null,
        val bytesProcessed: Long = 0L,
        val totalBytes: Long? = null,
        val currentSpeed: Long? = null, // bytes per second
        val estimatedTimeRemaining: Duration? = null,
        val canCancel: Boolean = true,
    ) : OperationState

    data class AwaitingInput(
        override val operationId: OperationId,
        override val startTime: Instant,
        val conflict: Conflict,
        val previousProgress: Progress.Data? = null,
        val timeout: Duration? = null,
    ) : OperationState

    data class Completed(
        override val operationId: OperationId,
        override val startTime: Instant,
        val result: OperationResult,
        val endTime: Instant = Clock.System.now(),
    ) : OperationState {
        val duration: Duration = endTime - startTime
    }
}
