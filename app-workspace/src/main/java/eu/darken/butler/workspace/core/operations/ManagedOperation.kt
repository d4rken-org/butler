package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock
import kotlin.time.Instant

class ManagedOperation(
    val id: Operation.Id,
    private val operation: Operation,
    private val scope: CoroutineScope,
) {
    val tag = logTag("Workspace", "Operations", "ManagedOperation", id.shortTag)
    private val startTime = Clock.System.now()

    private val _state = MutableStateFlow<Operation.State>(
        Operation.State.Queued(
            startedAt = startTime
        )
    )
    val state: StateFlow<Operation.State> = _state

    val metadata: Operation.Metadata = operation.metadata

    private var job: Job? = null

    val canCancel: Boolean
        get() = when (state.value) {
            is Operation.State.Queued -> true  // Can cancel before it starts
            is Operation.State.Active -> job?.isActive == true  // Can cancel if running
            is Operation.State.Waiting -> job?.isActive == true  // Can cancel if waiting
            else -> false  // Cannot cancel completed/failed/cancelled
        }

    val canPause: Boolean get() = false

    suspend fun execute() {
        log(tag, INFO) { "execute(): Starting operation $id" }
        val operationContext = Operation.Context(
            id = id,
            startedAt = startTime,
        )
        job = operation
            .execute(operationContext)
            .onEach { state ->
                log(tag, VERBOSE) { "Operation $id state: $state" }
                _state.emit(state)
            }
            .catch { error ->
                log(tag, INFO) { "Operation $id completed with error: $error" }
                _state.emit(
                    object : Operation.State.Completed {
                        override val startedAt: Instant = startTime
                        override val completedAt: Instant = Clock.System.now()
                        override val summary: CaString = caString {
                            "Unknown error" // TODO
                        }
                        override val error: Throwable = error
                    }
                )
            }
            .launchIn(scope)
    }

    fun cancel() {
        log(tag) { "cancel()" }
        job?.cancel()
    }
}