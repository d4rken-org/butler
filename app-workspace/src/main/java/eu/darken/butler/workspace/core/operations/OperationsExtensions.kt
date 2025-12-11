package eu.darken.butler.workspace.core.operations

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
 */
suspend fun ManagedOperation.awaitCompletion(): Operation.State.Completed {
    return state.filterIsInstance<Operation.State.Completed>().first()
}
