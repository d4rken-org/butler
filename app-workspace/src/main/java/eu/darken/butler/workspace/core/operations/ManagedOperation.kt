package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.throttleLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ManagedOperation(
    val id: Operation.Id,
    private val operation: Operation,
    parentScope: CoroutineScope,
) {
    val tag = logTag("Workspace", "Operations", "ManagedOperation", id.shortTag)
    private val startTime = Clock.System.now()

    private val scope = CoroutineScope(parentScope.coroutineContext + Job())

    private val _state = MutableStateFlow<Operation.State>(
        Operation.State.Queued(
            startedAt = startTime
        )
    )
    val state: StateFlow<Operation.State> = _state

    val metadata: Operation.Metadata = operation.metadata

    val canCancel: Boolean
        get() = when (state.value) {
            is Operation.State.Queued -> true  // Can cancel before it starts
            is Operation.State.Active -> scope.coroutineContext[Job]?.isActive == true  // Can cancel if running
            is Operation.State.Waiting -> scope.coroutineContext[Job]?.isActive == true  // Can cancel if waiting
            else -> false  // Cannot cancel completed/failed/cancelled
        }

    val canPause: Boolean get() = false

    fun start() {
        log(tag, INFO) { "start(): Starting operation $id" }
        val operationContext = Operation.Context(
            id = id,
            startedAt = startTime,
        )

        operation
            .perform(operationContext)
            .throttleLatest(250.milliseconds) {
                it is Operation.State.Active
            }
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
                            it.getString(R.string.general_error_label) + " (${error.toString()})"
                        }
                        override val report: Operation.Report? = null
                        override val error: Throwable = error
                    }
                )
            }
            .onCompletion { cause ->
                if (cause is CancellationException) {
                    log(tag, INFO) { "Operation $id was cancelled" }
                    _state.emit(
                        object : Operation.State.Completed {
                            override val startedAt: Instant = startTime
                            override val completedAt: Instant = Clock.System.now()
                            override val summary: CaString = R.string.general_result_user_cancel_msg.toCaString()
                            override val report: Operation.Report? = null
                            override val error: Throwable = cause
                        }
                    )
                }
            }
            .launchIn(scope)
    }

    fun cancel() {
        log(tag) { "cancel()" }
        scope.cancel()
    }
}