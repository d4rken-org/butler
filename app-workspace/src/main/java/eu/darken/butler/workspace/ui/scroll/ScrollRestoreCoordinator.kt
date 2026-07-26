package eu.darken.butler.workspace.ui.scroll

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.runtime.snapshotFlow
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.ui.restore.DEFAULT_RESTORE_TIMEOUT
import eu.darken.butler.workspace.ui.restore.Outcome
import eu.darken.butler.workspace.ui.restore.restoreWhenReady
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlin.time.Duration

/**
 * A list/grid a scroll position can be restored into. A plain interface (not a composable) so the
 * restore is unit-testable against a fake.
 */
interface ScrollTarget {
    val totalItemsCount: Int
    val position: WorkspaceScrollPosition
    val isScrollInProgress: Boolean
    val interactions: Flow<Interaction>
    suspend fun scrollTo(position: WorkspaceScrollPosition)
}

private val TAG = logTag("Workspace", "ScrollRestore")

/**
 * Waits for the list to hold enough items for [saved] to be reachable, then scrolls there.
 *
 * The wait is on `totalItemsCount > saved.index`, not on "list is non-empty": several pages render a
 * single loading/empty placeholder item, against which a saved index of 100 would clamp to the top
 * and be recorded as the new truth.
 *
 * User intent is taken from interaction events and from scroll activity, never from the position: a
 * position that moved away from the top may just be layout clamping, and reading that as intent
 * would both suppress a legitimate restore and license a bad write. Until the restore itself scrolls
 * there is no scrolling of its own to confuse this with, so any scroll in progress belongs to
 * someone else - a drag, or a programmatic scroll-to-top from a sort/search/view-style effect, which
 * emits no drag interaction at all.
 */
suspend fun ScrollTarget.restore(
    saved: WorkspaceScrollPosition?,
    timeout: Duration = DEFAULT_RESTORE_TIMEOUT,
): Outcome {
    val outcome = restoreWhenReady(
        saved = saved,
        isNoOp = { it.isTop },
        isReady = { totalItemsCount > it.index },
        supersededBy = movementSignals(),
        timeout = timeout,
        apply = { scrollTo(it) },
    )
    // Every invocation logs exactly one line, including the "nothing to do" case: a silent early
    // return makes an absent line ambiguous between "ran, nothing to do" and "never ran".
    log(TAG) { "restore($saved) -> $outcome" }
    return outcome
}

/**
 * The signals that mean "someone other than the restore moved this list": a drag, or any scroll in
 * progress - which is what catches a programmatic `scrollToItem` from a sort, search or view-style
 * effect, since those emit no drag interaction at all.
 *
 * One definition, used both to supersede a pending restore and to arm recording after a timeout, so
 * the two can't drift apart.
 */
private fun ScrollTarget.movementSignals(): List<Flow<Any>> = listOf(
    interactions.filter { it is DragInteraction.Start },
    snapshotFlow { isScrollInProgress }.filter { it },
)

/**
 * Suspends until this list is moved by someone other than the restore.
 *
 * Used to arm recording after a [Outcome.TIMED_OUT]: the position the un-restored list shows must
 * not be written over a good saved one, but once something actually scrolls it - a drag *or* a
 * programmatic scroll - the position it lands on is intent and has to be recorded. A state that is
 * hoisted but not attached to any lazy container (Explorer keeps both a list and a grid state, only
 * one of which is attached) always times out, so this is the only path by which the list/grid
 * transfer's scroll ever gets persisted.
 */
suspend fun ScrollTarget.awaitMovement() {
    movementSignals().merge().first()
}
