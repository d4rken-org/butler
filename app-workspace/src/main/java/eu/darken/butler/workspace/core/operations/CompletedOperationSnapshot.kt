package eu.darken.butler.workspace.core.operations

/**
 * Immutable snapshot of an operation that reached a [Operation.State.Completed] state.
 *
 * Emitted exactly once per operation by [OperationsManager.completedOperations]. Consumers
 * (e.g. the global Operation History repo) subscribe to this stream instead of diffing
 * the conflated [OperationsManager.operations] list — that approach races with
 * [OperationsManager.removeWorkspace] cancellation cleanup and can drop entries.
 */
data class CompletedOperationSnapshot(
    val id: Operation.Id,
    val metadata: Operation.Metadata,
    val state: Operation.State.Completed,
)
