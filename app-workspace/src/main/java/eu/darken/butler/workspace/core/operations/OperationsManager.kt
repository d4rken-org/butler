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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
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
    // Concurrent: entries are removed by each observer's own completion callback, which runs off the
    // mutex once that observer's operation is done. internal (module-scoped) so a test can assert an
    // observer is released with its operation — the codebase uses no @VisibleForTesting.
    internal val stateObservers = ConcurrentHashMap<Operation.Id, Job>()

    /**
     * Side-channel emitting exactly one [CompletedOperationSnapshot] per operation when it reaches
     * a terminal [Operation.State.Completed] — including operations cancelled via [removeWorkspace].
     *
     * Race-free contract: when [removeWorkspace] cancels in-flight ops, it synthesizes a cancellation
     * snapshot BEFORE cancelling the per-op state observer (otherwise the asynchronous cancellation
     * Completed state would arrive after the observer is gone). Dedup is enforced by
     * [ManagedOperation.claimCompletionEmission] so the eventual real Completed (if it sneaks past)
     * is suppressed.
     */
    private val _completedOperations = MutableSharedFlow<CompletedOperationSnapshot>(
        extraBufferCapacity = 64,
    )
    val completedOperations: SharedFlow<CompletedOperationSnapshot> = _completedOperations.asSharedFlow()

    suspend fun submit(operation: Operation): Operation.Id = submitManaged(operation).id

    /**
     * [submit], but handing back the [ManagedOperation] itself.
     *
     * Submitting and then looking the operation up in [operations] races retention instead: an
     * operation that finishes before the caller looks can be evicted first, and the lookup then waits
     * for an entry that is never coming back.
     */
    suspend fun submitManaged(operation: Operation): ManagedOperation = mutex.withLock {
        val id = Operation.Id()
        log(TAG, INFO) { "submit(): New operation $id" }

        val managed = ManagedOperation(
            id = id,
            operation = operation,
            parentScope = opsScope,
        )
        log(TAG) { "submit(): $id -> $managed" }

        val stateObserver = managed.state
            // Completed is terminal, so there is nothing left to observe once one passes through.
            // Without this the observer keeps collecting a StateFlow that never completes, and every
            // finished operation leaves a live collector behind for the rest of the process' life.
            .transformWhile { state ->
                emit(state)
                state !is Operation.State.Completed
            }
            .onEach { state ->
                // Side-channel: emit Completed snapshot exactly once per op
                if (state is Operation.State.Completed && managed.claimCompletionEmission()) {
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
                _operations.update { it.pruneTerminal() }
            }
            // Runs on a natural finish and on cancellation alike, so this is the single owner of the
            // map entry. Nothing suspending in here: under cancellation that throws instead of
            // cleaning up.
            .onCompletion { stateObservers.remove(id) }
            .launchIn(opsScope)

        stateObservers[id] = stateObserver
        _operations.update { (it + managed).pruneTerminal() }

        log(TAG, VERBOSE) { "submit(): Starting $id" }
        managed.start()
        log(TAG) { "submit(): Started $id" }

        managed
    }

    suspend fun cancel(id: Operation.Id) = mutex.withLock {
        log(TAG, INFO) { "cancel(): Cancelling $id" }
        val operation = _operations.value.find { it.id == id }
        if (operation == null) log(TAG, WARN) { "cancel(): Operation not found $id" }
        else log(TAG, VERBOSE) { "cancel(): Cancelling $operation" }
        // The notification's Cancel action routes here, and a stale notification outlives the UI
        // gate that hid the button, so the refusal has to live at the entry point too.
        if (operation != null && !operation.metadata.isCancellable) {
            log(TAG, WARN) { "cancel(): $id is not user-cancellable, ignoring" }
            return@withLock
        }
        // Don't synthesize here — observer stays alive and will emit the real Completed
        // when ManagedOperation.onCompletion fires after scope cancellation.
        operation?.cancel()
    }

    /**
     * The subset of [workspaceIds] that currently hosts at least one unfinished operation with
     * [Operation.Metadata.ClosePolicy.REQUIRE_ORIGIN], i.e. the ones whose teardown a caller must
     * refuse instead of performing.
     *
     * Non-suspending on purpose: [eu.darken.butler.workspace.core.WorkspaceRepo] asks this while
     * holding its own lock, before it releases anything.
     *
     * Answers about the instant it is called. An operation submitted after the answer and before the
     * teardown is cancelled by [removeWorkspace] like any other - that submit-vs-close race predates
     * this and is not closed by it.
     */
    fun closeBlockers(workspaceIds: Collection<Workspace.Id>): Set<Workspace.Id> {
        if (workspaceIds.isEmpty()) return emptySet()
        val wanted = workspaceIds.toSet()
        return _operations.value
            .filter { op ->
                op.metadata.closePolicy == Operation.Metadata.ClosePolicy.REQUIRE_ORIGIN &&
                    op.metadata.origin.workspaceId in wanted &&
                    op.isUnfinished
            }
            .mapTo(mutableSetOf()) { it.metadata.origin.workspaceId }
    }

    suspend fun remove(id: Operation.Id) = mutex.withLock {
        log(TAG, INFO) { "remove(): Remove $id" }

        stateObservers.remove(id)?.cancel()

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
        val completed = _operations.value.filter { it.state.value is Operation.State.Completed }
        completed.forEach { log(TAG, VERBOSE) { "clearCompleted(): Clearing $it" } }
        _operations.update { ops -> ops - completed.toSet() }
        // Outside the update lambda: that lambda is a CAS that can be retried, and cancelling from
        // inside it would run once per attempt.
        completed.forEach { stateObservers.remove(it.id)?.cancel() }
    }

    /**
     * Cancels and drops everything that named [id] as its origin.
     *
     * Only reached for workspaces [closeBlockers] did not name, so a
     * [Operation.Metadata.ClosePolicy.REQUIRE_ORIGIN] operation that still arrives here - submitted
     * inside the race that method documents - is cancelled like any other.
     */
    suspend fun removeWorkspace(id: Workspace.Id) {
        // Build snapshots inside the lock (state-snapshot consistency), emit OUTSIDE (avoid blocking
        // ops-manager mutations on SharedFlow backpressure).
        val snapshots = mutex.withLock {
            log(TAG, INFO) { "removeWorkspace(): Clearing operations workspace $id" }
            val fromWorkspace = _operations.value.filter { op -> op.metadata.origin.workspaceId == id }
            log(TAG) { "removeWorkspace(): Removing ${fromWorkspace.size} operations" }

            // Synthesize cancellation snapshots BEFORE cancelling — closes the observer race window.
            // If an op already completed naturally, its observer already emitted; the claim fails and
            // we skip. If an op is still in-flight, the eventual real Completed is suppressed because
            // we claimed the emission first.
            val toEmit = fromWorkspace.mapNotNull { managed ->
                if (managed.state.value is Operation.State.Completed) {
                    null
                } else if (managed.claimCompletionEmission()) {
                    buildCancellationSnapshot(managed, reason = "Workspace removed")
                } else {
                    null
                }
            }

            fromWorkspace.forEach {
                log(TAG, VERBOSE) { "removeWorkspace(): Cancelling $it" }
                it.cancel()
                stateObservers.remove(it.id)?.cancel()
            }
            _operations.update { ops -> ops - fromWorkspace.toSet() }

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

    /**
     * Keeps every unfinished operation plus the [MAX_RETAINED_TERMINAL] most recently finished ones,
     * dropping the rest. Returns the receiver unchanged when nothing is over the limit, so a prune
     * that evicts nothing stays conflated away by [_operations].
     *
     * A finished operation is a receipt the user may dismiss, and nothing makes them, so uncapped they
     * pile up for the process' lifetime together with whatever their report holds. The bound is
     * deliberately independent of the workspace an operation came from: workspace close is not what
     * has to make this terminate.
     */
    private fun List<ManagedOperation>.pruneTerminal(): List<ManagedOperation> {
        // One state read per operation: this runs inside a CAS lambda that may be retried, and an
        // operation whose state changed between the count and the sort would evict the wrong entry.
        val finished = mapNotNull { op ->
            (op.state.value as? Operation.State.Completed)?.let { op to it.completedAt }
        }
        if (finished.size <= MAX_RETAINED_TERMINAL) return this

        val evicted = finished
            .sortedByDescending { (_, completedAt) -> completedAt }
            .drop(MAX_RETAINED_TERMINAL)
            .map { (op, _) -> op }
            .toSet()
        evicted.forEach { log(TAG, VERBOSE) { "pruneTerminal(): Evicting $it" } }
        // Their observers ended with their operation, so there is no job left here to cancel.
        return filter { it !in evicted }
    }

    companion object {
        private val TAG = logTag("Workspace", "Operations", "Manager")

        /**
         * Finished operations kept in memory for the operations bar. The global Operation History
         * persists them separately and with its own cap.
         */
        internal const val MAX_RETAINED_TERMINAL = 50
    }
}
