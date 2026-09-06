package eu.darken.butler.workspace.core.operations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/**
 * Suspends until the operation completes (success or failure).
 * Throws [CancellationException] if the operation was cancelled.
 */
suspend fun ManagedOperation.awaitCompletion(): Operation.State.Completed {
    val completed = state.filterIsInstance<Operation.State.Completed>().first()
    if (completed.error is CancellationException) {
        throw completed.error as CancellationException
    }
    return completed
}
