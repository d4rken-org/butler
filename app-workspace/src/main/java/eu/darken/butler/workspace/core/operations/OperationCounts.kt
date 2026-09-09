package eu.darken.butler.workspace.core.operations

import kotlin.coroutines.cancellation.CancellationException

/**
 * What a set of operations adds up to for the surfaces that mark a tab.
 *
 * @param unfinished everything that has not reached a terminal state: queued, running and waiting.
 * @param active the subset of [unfinished] that is actually running right now.
 * @param attention work stopped on a user answer, plus anything that failed for a reason other than
 * the user cancelling it.
 */
data class OperationCounts(
    val unfinished: Int = 0,
    val active: Int = 0,
    val attention: Int = 0,
)

/**
 * One rule in one place: every workspace's [eu.darken.butler.workspace.core.Workspace.Info] counters
 * come from here, so a marker summed across an ownership unit cannot disagree with itself.
 */
fun List<ManagedOperation>.toOperationCounts(): OperationCounts {
    var unfinished = 0
    var active = 0
    var attention = 0
    forEach { operation ->
        when (val state = operation.state.value) {
            is Operation.State.Queued -> unfinished++

            is Operation.State.Active -> {
                unfinished++
                active++
            }

            is Operation.State.Waiting -> {
                unfinished++
                attention++
            }

            is Operation.State.Completed -> {
                if (state.error != null && state.error !is CancellationException) attention++
            }
        }
    }
    return OperationCounts(unfinished = unfinished, active = active, attention = attention)
}
