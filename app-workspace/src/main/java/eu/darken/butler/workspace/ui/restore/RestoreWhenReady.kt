package eu.darken.butler.workspace.ui.restore

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class Outcome {
    NOT_NEEDED,
    APPLIED,
    SUPERSEDED,
    TIMED_OUT,
}

val DEFAULT_RESTORE_TIMEOUT = 5.seconds

/**
 * Applies a piece of saved UI state once the UI is ready to receive it.
 *
 * Restoring into a freshly composed page is always a race: the value is known immediately, the UI it
 * belongs to is not (list content loads asynchronously, floating bars register during composition).
 * The invariants, in order of importance:
 *
 * - **Never write a default over a good saved value.** A timeout reports [Outcome.TIMED_OUT] and
 *   applies nothing; the caller is expected to hold off recording rather than persist whatever the
 *   un-restored UI happens to show. The safe failure mode is "not restored", never "destroyed".
 * - **Whoever got there first wins.** Any flow in [supersededBy] emitting before the UI is ready
 *   means someone else (the user, or an effect) already decided the state, and the restore stands
 *   down with [Outcome.SUPERSEDED].
 * - **Apply exactly once**, never on recomposition.
 *
 * @param isNoOp saved values equivalent to "nothing to restore" (e.g. a top scroll position).
 * @param isReady read inside a `snapshotFlow`, so it must only touch snapshot state.
 */
suspend fun <T> restoreWhenReady(
    saved: T?,
    isNoOp: (T) -> Boolean,
    isReady: (T) -> Boolean,
    supersededBy: List<Flow<*>> = emptyList(),
    timeout: Duration = DEFAULT_RESTORE_TIMEOUT,
    apply: suspend (T) -> Unit,
): Outcome {
    if (saved == null || isNoOp(saved)) return Outcome.NOT_NEEDED

    return withTimeoutOrNull(timeout) {
        coroutineScope {
            val intents = supersededBy.map { intent -> async { intent.first() } }
            val ready = async { snapshotFlow { isReady(saved) }.first { it } }

            val raced = select<Outcome> {
                intents.forEach { intent -> intent.onAwait { Outcome.SUPERSEDED } }
                ready.onAwait { Outcome.APPLIED }
            }
            intents.forEach { it.cancel() }
            ready.cancel()

            if (raced == Outcome.APPLIED) apply(saved)
            raced
        }
    } ?: Outcome.TIMED_OUT
}
