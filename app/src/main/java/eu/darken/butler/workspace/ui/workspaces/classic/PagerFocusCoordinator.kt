package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

private val TAG = logTag("Workspace", "Container", "Classic", "PagerCoord")

class PagerFocusCoordinatorState internal constructor() {
    // A depth counter, not a flag: animateScrollToPage goes through Compose's MutatorMutex, so a
    // second programmatic scroll cancels the first — whose finally would then clear a flag the
    // still-running second scroll depends on, and its settle would be misread as a user swipe.
    private var programmaticScrollDepth: Int = 0

    val isAnimatingProgrammatically: Boolean
        get() = programmaticScrollDepth > 0

    internal var lastSyncedFocusId: Workspace.Id? = null
    internal var lastUserSwipeFocusId: Workspace.Id? = null

    // Page of an in-flight clamp correction. The matching settle emission is swallowed instead of
    // being reported as a user swipe — during a clamp, focus is stale/null, so the usual
    // settledId != focused guard can't suppress the echo.
    internal var pendingClampPage: Int? = null

    internal suspend fun <R> asProgrammaticScroll(block: suspend () -> R): R {
        programmaticScrollDepth++
        try {
            return block()
        } finally {
            programmaticScrollDepth--
        }
    }

    /**
     * Scrolls the pager back onto [id]'s page.
     *
     * Takes a [Workspace.Id] rather than a page index because a caller's index is resolved at
     * composition time: tabs closing or reordering while the animation runs would make it name a
     * different workspace, or fall out of range.
     */
    suspend fun scrollToWorkspace(pagerState: PagerState, tabIds: List<Workspace.Id>, id: Workspace.Id) {
        val page = tabIds.indexOf(id)
        if (page < 0 || pagerState.currentPage == page) return
        log(TAG, VERBOSE) { "scrollToWorkspace($id) -> page $page" }
        asProgrammaticScroll { pagerState.animateScrollToPage(page) }
    }
}

/**
 * Whether the pager is at rest on [page].
 *
 * Idle *and* settled, both parts load-bearing. `settledPage` alone stays on the outgoing page for
 * the whole fling, so a Back pressed mid-swipe would still reach the tab being swiped away.
 * Requiring the pager to be idle means no pane consumes Back while the pager is moving, in either
 * direction. `targetPage` and `currentPage` are unusable here: the former reverses on an aborted
 * drag, the latter flips at the drag's half-way point and back, so either would make
 * back-eligibility follow a finger that has not committed to anything.
 */
internal fun PagerState.isSettledOnPage(page: Int): Boolean =
    !isScrollInProgress && settledPage == page

/**
 * Coordinates a [PagerState] with externally-driven workspace focus.
 *
 * Replaces two separate `LaunchedEffect` blocks plus shared flag vars with a
 * single coordinator that:
 * - Keys on `tabIds` (stable workspace IDs), not the full `Workspace.Info` list,
 *   so internal field churn (operationCount, attentionCount, title) does not
 *   trigger spurious pager scrolls.
 * - Defers — rather than drops — focus changes that arrive while the pager is
 *   mid-scroll. Applies after the scroll settles.
 * - Wraps every programmatic scroll in
 *   [PagerFocusCoordinatorState.asProgrammaticScroll], so
 *   [PagerFocusCoordinatorState.isAnimatingProgrammatically] is always reset,
 *   even on cancellation, and survives overlapping scrolls.
 * - Owns ALL programmatic pager movement, including the clamp back into the
 *   real-tab range after a list shrink strands the pager on the trailing
 *   placeholder page. Nothing else may scroll this pager.
 *
 * @param pagerState the pager being driven
 * @param tabIds stable list of currently-displayed workspace IDs (placeholder
 *     pages excluded)
 * @param focused the OWNING TAB of the workspace the VM has focused, never a raw child id: pages
 *     are keyed by tab, so a stacked child would resolve to no page and the sync would wait forever
 * @param isRestoring true during session restoration; suppresses animation
 * @param isOverlayVisible true when an overlay covers the pager; suppresses animation
 * @param onSettled invoked once when the user's swipe settles on a tab whose
 *     ID differs from [focused]; the caller is responsible for updating the VM
 */
@Composable
fun rememberPagerFocusCoordinator(
    pagerState: PagerState,
    tabIds: List<Workspace.Id>,
    focused: Workspace.Id?,
    isRestoring: Boolean,
    isOverlayVisible: Boolean,
    onSettled: (Workspace.Id) -> Unit,
): PagerFocusCoordinatorState {
    val coordinator = remember { PagerFocusCoordinatorState() }

    val currentFocused by rememberUpdatedState(focused)
    val currentOnSettled by rememberUpdatedState(onSettled)

    LaunchedEffect(pagerState, tabIds, focused, isRestoring, isOverlayVisible) {
        val desiredId = focused
        val targetIndex = desiredId?.let { tabIds.indexOf(it) } ?: -1

        if (targetIndex < 0) {
            // No focus, or focus points at a workspace that is gone or not yet listed (e.g. the
            // tab that was just closed). Never leave the pager parked beyond the last real tab:
            // a list shrink hands the current index to the trailing creation placeholder, and
            // sitting there must not look like user intent to the placeholder logic.
            if (desiredId != null) {
                log(TAG, VERBOSE) { "Focus $desiredId not in tabIds yet — waiting" }
            }
            clampToLastRealPage(pagerState, tabIds, coordinator)
            return@LaunchedEffect
        }

        if (desiredId == coordinator.lastUserSwipeFocusId) {
            log(TAG, VERBOSE) { "Skip pager sync — focus came from user swipe: $desiredId" }
            coordinator.lastUserSwipeFocusId = null
            coordinator.lastSyncedFocusId = desiredId
            return@LaunchedEffect
        }

        if (pagerState.isScrollInProgress) {
            log(TAG, VERBOSE) { "Pager mid-scroll, deferring sync for $desiredId" }
            snapshotFlow { pagerState.isScrollInProgress }.first { !it }
            if (pagerState.currentPage == targetIndex) {
                coordinator.lastSyncedFocusId = desiredId
                return@LaunchedEffect
            }
        }

        if (pagerState.currentPage == targetIndex) {
            coordinator.lastSyncedFocusId = desiredId
            return@LaunchedEffect
        }

        val isFirstSyncForFocus = coordinator.lastSyncedFocusId != desiredId
        val shouldSkipAnimation = isRestoring || isFirstSyncForFocus || isOverlayVisible

        coordinator.asProgrammaticScroll {
            if (shouldSkipAnimation) {
                log(TAG, VERBOSE) {
                    "Jump to page $targetIndex (restoring=$isRestoring, " +
                        "first=$isFirstSyncForFocus, overlay=$isOverlayVisible)"
                }
                pagerState.scrollToPage(targetIndex)
            } else {
                log(TAG, VERBOSE) { "Animate to page $targetIndex" }
                pagerState.animateScrollToPage(targetIndex)
            }
        }
        coordinator.lastSyncedFocusId = desiredId
    }

    LaunchedEffect(pagerState, tabIds) {
        // Deliberately a LOCAL var, not coordinator state: it must reset on every restart of this
        // effect. A tabIds change restarts the effect, and the fresh snapshotFlow immediately
        // re-emits the CURRENT settledPage — the pager never moved, but tabIds[settled] may now
        // resolve to a different workspace (limit recovery removes and adds a tab in one step).
        // Requiring a scroll to have been observed since the restart keeps that re-report from
        // masquerading as a swipe and overwriting externally-set focus.
        var sawScroll = false
        // Also a LOCAL var, and for the same reason: after a restart nothing has been reported yet,
        // so the first settle must be free to report. See the de-duplication below.
        var lastSettledPage: Int? = null
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (settled, scrolling) ->
                if (scrolling) {
                    sawScroll = true
                    return@collect
                }

                // Consumed BEFORE the sawScroll gate, and only when the settled page actually IS
                // the clamp target: scrollToPage may flip isScrollInProgress on and off without
                // suspending, and snapshotFlow's conflating channel may skip the `true` state
                // entirely — so the clamp's own settle can arrive with sawScroll still false. If
                // the gate ran first, the marker would survive and swallow a later genuine swipe
                // back to that page. The equality check is what makes early consumption safe: an
                // unrelated pre-clamp settle must not consume a marker whose settle is still
                // in flight, which is what a blind reorder would have allowed.
                val clampPage = coordinator.pendingClampPage
                if (clampPage != null && settled == clampPage) {
                    coordinator.pendingClampPage = null
                    sawScroll = false
                    return@collect
                }

                if (!sawScroll) return@collect
                sawScroll = false

                // A genuine settle elsewhere means the marked clamp settle will never be reported;
                // drop the marker so it cannot go stale.
                if (clampPage != null) {
                    coordinator.pendingClampPage = null
                }

                // De-duplicated per PAGE, not per (page, scrolling) pair: one swipe is two scroll
                // episodes — the drag, then the fling/snap that follows it — and both settle on the
                // same page. Keyed on the pair alone they are distinct events, so the destination
                // was reported twice and the whole selection path ran twice per swipe.
                //
                // Recorded for every settle that gets this far, not only for the ones that go on to
                // be reported: a programmatic move parks the pager on a page too, and forgetting
                // that would swallow the user's next swipe back onto the page they left.
                if (settled == lastSettledPage) return@collect
                lastSettledPage = settled

                if (coordinator.isAnimatingProgrammatically) return@collect
                if (settled !in tabIds.indices) return@collect

                val settledId = tabIds[settled]
                if (settledId != currentFocused) {
                    log(TAG, VERBOSE) { "User swipe settled on $settledId at index $settled" }
                    coordinator.lastUserSwipeFocusId = settledId
                    currentOnSettled(settledId)
                }
            }
    }

    return coordinator
}

/**
 * Snaps the pager back into the real-tab range when it is parked beyond the last tab without a
 * (valid) focus to sync to. Waits for any in-flight scroll to settle first, then re-checks —
 * the pager may have landed in range on its own.
 */
private suspend fun clampToLastRealPage(
    pagerState: PagerState,
    tabIds: List<Workspace.Id>,
    coordinator: PagerFocusCoordinatorState,
) {
    if (tabIds.isEmpty()) return
    val lastReal = tabIds.size - 1

    if (pagerState.isScrollInProgress) {
        log(TAG, VERBOSE) { "Pager mid-scroll, deferring clamp check" }
        snapshotFlow { pagerState.isScrollInProgress }.first { !it }
    }
    if (pagerState.currentPage <= lastReal) return

    log(TAG, VERBOSE) { "Clamping pager from page ${pagerState.currentPage} to last real page $lastReal" }
    coordinator.pendingClampPage = lastReal
    coordinator.asProgrammaticScroll {
        try {
            pagerState.scrollToPage(lastReal)
        } catch (e: CancellationException) {
            // Clamp aborted (focus arrived, keys changed) — the marked settle may never happen.
            coordinator.pendingClampPage = null
            throw e
        }
    }
}
