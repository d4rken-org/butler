package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OperationsManager @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
) {

    private val scope = CoroutineScope(dispatcherProvider.IO + SupervisorJob())
    private val _operations = MutableStateFlow<List<ManagedOperation>>(emptyList())
    val operations: StateFlow<List<ManagedOperation>> = _operations.asStateFlow()
    private val mutex = Mutex()

    suspend fun submit(operation: Operation): Operation.Id = mutex.withLock {
        val id = Operation.Id()

        val managed = ManagedOperation(
            id = id,
            operation = operation,
            scope = scope,
        )

        _operations.update { it + managed }

        scope.launch {
            managed.execute()
        }

        id
    }

    suspend fun cancel(id: Operation.Id) = mutex.withLock {
        _operations.value.find { it.id == id }?.cancel()
    }

    suspend fun removeOperation(id: Operation.Id) = mutex.withLock {
        _operations.update { ops -> ops.filter { it.id != id } }
    }

    suspend fun clearCompleted() = mutex.withLock {
        _operations.update { ops ->
            ops.filter { op -> op.state.value !is Operation.State.Completed }
        }
    }

    suspend fun clearWorkspaceById(id: Workspace.Id): Unit = mutex.withLock {
        log(TAG, INFO) { "Clearing workspace $id" }
        TODO("Not yet implemented")
    }

    companion object {
        private val TAG = logTag("Workspace", "Operations", "Manager")
    }
}