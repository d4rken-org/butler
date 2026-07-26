package eu.darken.butler.workspace.ui.scroll

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.runtime.snapshotFlow
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A list/grid a scroll position can be restored into. A plain interface (not a composable) so the
 * restore state machine is unit-testable against a fake.
 */
interface ScrollTarget {
    val totalItemsCount: Int
    val position: WorkspaceScrollPosition
    val interactions: Flow<Interaction>
    suspend fun scrollTo(position: WorkspaceScrollPosition)
}

enum class Outcome {
    NOT_NEEDED,
    APPLIED,
    SUPERSEDED,
    TIMED_OUT,
}

private val TAG = logTag("Workspace", "ScrollRestore")

private val DEFAULT_TIMEOUT = 5.seconds

/**
 * Waits for the list to hold enough items for [saved] to be reachable, then scrolls there.
 *
 * The wait is on `totalItemsCount > saved.index`, not on "list is non-empty": several pages render a
 * single loading/empty placeholder item, against which a saved index of 100 would clamp to the top
 * and be recorded as the new truth.
 *
 * User intent is taken from interaction events, never from the position: a position that moved away
 * from the top may just be layout clamping, and reading that as intent would both suppress a
 * legitimate restore and license a bad write.
 */
suspend fun ScrollTarget.restore(
    saved: WorkspaceScrollPosition?,
    timeout: Duration = DEFAULT_TIMEOUT,
): Outcome {
    if (saved == null || saved.isTop) return Outcome.NOT_NEEDED

    val outcome = withTimeoutOrNull(timeout) {
        coroutineScope {
            val dragged = async { interactions.first { it is DragInteraction.Start } }
            val filled = async { snapshotFlow { totalItemsCount }.first { it > saved.index } }

            val raced = select {
                dragged.onAwait { Outcome.SUPERSEDED }
                filled.onAwait { Outcome.APPLIED }
            }
            dragged.cancel()
            filled.cancel()

            if (raced == Outcome.APPLIED) scrollTo(saved)
            raced
        }
    } ?: Outcome.TIMED_OUT

    log(TAG) { "restore($saved) -> $outcome" }
    return outcome
}
