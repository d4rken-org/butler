package eu.darken.butler.workspace.core.operations

import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine as kotlinCombine


fun OperationsManager.operationsForWorkspace(
    workspaceId: Workspace.Id
): Flow<List<ManagedOperation>> = operations
    .map { allOperations ->
        allOperations.filter { it.metadata.origin.workspaceId == workspaceId }
    }

fun Flow<List<ManagedOperation>>.withStateUpdates(): Flow<List<ManagedOperation>> =
    flatMapLatest { operations ->
        when {
            operations.isEmpty() -> flowOf(operations)
            else -> kotlinCombine(operations.map { it.state }) { _ -> operations }
        }
    }


fun Flow<List<ManagedOperation>>.withOnlyStateChanges(): Flow<List<ManagedOperation>> =
    flatMapLatest { operations ->
        when {
            operations.isEmpty() -> flowOf(operations)
            else -> kotlinCombine(
                operations.map { operation ->
                    operation.state.map { it::class }.distinctUntilChanged()
                }
            ) { _ -> operations }
        }
    }

suspend fun OperationsManager.current() = this.operations.first()

suspend fun OperationsManager.get(id: Operation.Id): ManagedOperation? = current().find { it.id == id }