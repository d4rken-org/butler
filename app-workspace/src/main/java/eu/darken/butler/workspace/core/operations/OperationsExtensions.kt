package eu.darken.butler.workspace.core.operations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Submits an operation and returns the ManagedOperation for observation.
 */
suspend fun OperationsManager.submitAndGet(operation: Operation): ManagedOperation {
    val id = submit(operation)
    return operations
        .map { ops -> ops.find { it.id == id } }
        .filterNotNull()
        .first()
}

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
