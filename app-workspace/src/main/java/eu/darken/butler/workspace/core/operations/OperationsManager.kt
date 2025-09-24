package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OperationsManager @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
) {

    private val opsScope = CoroutineScope(dispatcherProvider.IO + SupervisorJob())
    private val _operations = MutableStateFlow<List<ManagedOperation>>(emptyList())
    val operations: Flow<List<ManagedOperation>> = _operations.asStateFlow()
    private val mutex = Mutex()
    private val stateObservers = mutableMapOf<Operation.Id, Job>()

    suspend fun submit(operation: Operation): Operation.Id = mutex.withLock {
        val id = Operation.Id()
        log(TAG, INFO) { "submit(): New operation $id" }

        val managed = ManagedOperation(
            id = id,
            operation = operation,
            parentScope = opsScope,
        )
        log(TAG) { "submit(): $id -> $managed" }

        val stateObserver = managed.state
            .map { state -> state::class }
            .distinctUntilChanged()
            .onEach {
                log(TAG, VERBOSE) { "submit(): State type changed for $id, triggering operations update" }
                _operations.update { currentList -> currentList.toList() }
            }
            .launchIn(opsScope)

        stateObservers[id] = stateObserver
        _operations.update { it + managed }

        log(TAG) { "submit(): Starting $id" }
        managed.start()
        log(TAG) { "submit(): Started $id" }

        id
    }

    suspend fun cancel(id: Operation.Id) = mutex.withLock {
        log(TAG, INFO) { "cancel(): Cancelling $id" }
        val operation = _operations.value.find { it.id == id }
        if (operation == null) log(TAG, WARN) { "cancel(): Operation not found $id" }
        else log(TAG, INFO) { "cancel(): Cancelling $operation" }
        operation?.cancel()
    }

    suspend fun remove(id: Operation.Id) = mutex.withLock {
        log(TAG, INFO) { "remove(): Remove $id" }

        stateObservers[id]?.cancel()
        stateObservers.remove(id)

        _operations.update { ops ->
            val target = ops.find { it.id == id }
            if (target == null) log(TAG, WARN) { "remove(): Can't find operation $id" }
            else log(TAG) { "remove(): Removing $target" }
            ops - listOfNotNull(target)
        }
    }

    suspend fun clearCompleted() = mutex.withLock {
        log(TAG, INFO) { "clearCompleted(): Clearing completed" }
        _operations.update { ops ->
            ops.filter { op ->
                val isCompleted = op.state.value is Operation.State.Completed
                if (isCompleted) {
                    log(TAG) { "clearCompleted(): Clearing $op" }
                    stateObservers[op.id]?.cancel()
                    stateObservers.remove(op.id)
                }
                !isCompleted
            }
        }
    }

    suspend fun removeWorkspace(id: Workspace.Id): Unit = mutex.withLock {
        log(TAG, INFO) { "removeWorkspace(): Clearing operations workspace $id" }
        _operations.update { ops ->
            val fromWorkspace = ops.filter { op -> op.metadata.origin.workspaceId == id }
            log(TAG) { "removeWorkspace(): Removing ${fromWorkspace.size} operations" }
            fromWorkspace.forEach {
                log(TAG) { "removeWorkspace(): Cancelling $it" }
                it.cancel()
                stateObservers[it.id]?.cancel()
                stateObservers.remove(it.id)
            }
            ops - fromWorkspace
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "Operations", "Manager")
    }
}