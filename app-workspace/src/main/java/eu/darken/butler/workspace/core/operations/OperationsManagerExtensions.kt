package eu.darken.butler.workspace.core.operations

import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine as kotlinCombine

/**
 * Filters operations for a specific workspace.
 * @param workspaceId The workspace to filter operations for
 * @return Flow of operations belonging to the specified workspace
 */
fun OperationsManager.operationsForWorkspace(
    workspaceId: Workspace.Id
): Flow<List<ManagedOperation>> = operations
    .map { allOperations ->
        allOperations.filter { it.metadata.origin.workspaceId == workspaceId }
    }

/**
 * Observes state changes for a collection of operations.
 * Emits whenever any operation's state changes.
 * @return Flow that emits the operation list whenever any state changes
 */
fun Flow<List<ManagedOperation>>.withStateUpdates(): Flow<List<ManagedOperation>> =
    flatMapLatest { operations ->
        when {
            operations.isEmpty() -> flowOf(operations)
            else -> kotlinCombine(operations.map { it.state }) { _ -> operations }
        }
    }