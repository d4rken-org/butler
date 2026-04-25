package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class OperationsManager @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
) {

    private val opsScope = CoroutineScope(dispatcherProvider.IO + SupervisorJob())
    private val _operations = MutableStateFlow<List<ManagedOperation>>(emptyList())
    val operations: Flow<List<ManagedOperation>> = _operations.asStateFlow()
    private val mutex = Mutex()
    private val stateObservers = mutableMapOf<Operation.Id, Job>()

    /**
     * Side-channel emitting exactly one [CompletedOperationSnapshot] per operation when it reaches
     * a terminal [Operation.State.Completed] — including operations cancelled via [removeWorkspace].
     *
     * Race-free contract: when [removeWorkspace] cancels in-flight ops, it synthesizes a cancellation
     * snapshot BEFORE cancelling the per-op state observer (otherwise the asynchronous cancellation
     * Completed state would arrive after the observer is gone). Dedup is enforced by [emittedCompletions]
     * so the eventual real Completed (if it sneaks past) is suppressed.
     */
    private val _completedOperations = MutableSharedFlow<CompletedOperationSnapshot>(
        extraBufferCapacity = 64,
    )
    val completedOperations: SharedFlow<CompletedOperationSnapshot> = _completedOperations.asSharedFlow()

    /**
     * Operation IDs whose Completed snapshot has been emitted. Concurrent because writers come from
     * both `opsScope` (state observers) and the main mutex (synthesis path).
     */
    private val emittedCompletions: MutableSet<Operation.Id> = ConcurrentHashMap.newKeySet()

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
            .onEach { state ->
                // Side-channel: emit Completed snapshot exactly once per op (concurrent dedup set)
                if (state is Operation.State.Completed && emittedCompletions.add(id)) {
                    _completedOperations.emit(
                        CompletedOperationSnapshot(
                            id = id,
                            metadata = managed.metadata,
                            state = state,
                        )
                    )
                }
            }
            .map { state -> state::class }
            .distinctUntilChanged()
            .onEach {
                log(TAG, VERBOSE) { "submit(): State type changed for $id, triggering operations update" }
                _operations.update { currentList -> currentList.toList() }
            }
            .launchIn(opsScope)

        stateObservers[id] = stateObserver
        _operations.update { it + managed }

        log(TAG, VERBOSE) { "submit(): Starting $id" }
        managed.start()
        log(TAG) { "submit(): Started $id" }

        id
    }

    suspend fun cancel(id: Operation.Id) = mutex.withLock {
        log(TAG, INFO) { "cancel(): Cancelling $id" }
        val operation = _operations.value.find { it.id == id }
        if (operation == null) log(TAG, WARN) { "cancel(): Operation not found $id" }
        else log(TAG, VERBOSE) { "cancel(): Cancelling $operation" }
        // Don't synthesize here — observer stays alive and will emit the real Completed
        // when ManagedOperation.onCompletion fires after scope cancellation.
        operation?.cancel()
    }

    suspend fun remove(id: Operation.Id) = mutex.withLock {
        log(TAG, INFO) { "remove(): Remove $id" }

        stateObservers[id]?.cancel()
        stateObservers.remove(id)

        _operations.update { ops ->
            val target = ops.find { it.id == id }
            if (target == null) log(TAG, WARN) { "remove(): Can't find operation $id" }
            else log(TAG, VERBOSE) { "remove(): Removing $target" }
            ops - listOfNotNull(target)
        }
    }

    suspend fun clearCompleted() = mutex.withLock {
        log(TAG, INFO) { "clearCompleted(): Clearing completed" }
        // Completed ops were already emitted via their state observers, no synthesis needed.
        _operations.update { ops ->
            ops.filter { op ->
                val isCompleted = op.state.value is Operation.State.Completed
                if (isCompleted) {
                    log(TAG, VERBOSE) { "clearCompleted(): Clearing $op" }
                    stateObservers[op.id]?.cancel()
                    stateObservers.remove(op.id)
                }
                !isCompleted
            }
        }
    }

    suspend fun removeWorkspace(id: Workspace.Id) {
        // Build snapshots inside the lock (state-snapshot consistency), emit OUTSIDE (avoid blocking
        // ops-manager mutations on SharedFlow backpressure).
        val snapshots = mutex.withLock {
            log(TAG, INFO) { "removeWorkspace(): Clearing operations workspace $id" }
            val fromWorkspace = _operations.value.filter { op -> op.metadata.origin.workspaceId == id }
            log(TAG) { "removeWorkspace(): Removing ${fromWorkspace.size} operations" }

            // Synthesize cancellation snapshots BEFORE cancelling — closes the observer race window.
            // If an op already completed naturally, its observer already emitted; emittedCompletions.add
            // returns false and we skip. If an op is still in-flight, the eventual real Completed is
            // suppressed because we claimed the id first.
            val toEmit = fromWorkspace.mapNotNull { managed ->
                if (managed.state.value is Operation.State.Completed) {
                    null
                } else if (emittedCompletions.add(managed.id)) {
                    buildCancellationSnapshot(managed, reason = "Workspace removed")
                } else {
                    null
                }
            }

            fromWorkspace.forEach {
                log(TAG, VERBOSE) { "removeWorkspace(): Cancelling $it" }
                it.cancel()
                stateObservers[it.id]?.cancel()
                stateObservers.remove(it.id)
            }
            _operations.update { ops -> ops - fromWorkspace }

            toEmit
        }
        snapshots.forEach { _completedOperations.emit(it) }
    }

    private fun buildCancellationSnapshot(
        managed: ManagedOperation,
        reason: String,
    ): CompletedOperationSnapshot {
        val now = Clock.System.now()
        val started = managed.state.value.startedAt
        val state = object : Operation.State.Completed {
            override val startedAt: Instant = started
            override val completedAt: Instant = now
            override val summary: CaString = R.string.general_result_user_cancel_msg.toCaString()
            override val report: Operation.Report? = null
            override val error: Throwable = CancellationException(reason)
        }
        return CompletedOperationSnapshot(
            id = managed.id,
            metadata = managed.metadata,
            state = state,
        )
    }

    companion object {
        private val TAG = logTag("Workspace", "Operations", "Manager")
    }
}
