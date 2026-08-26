package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

private val TAG = logTag("Workspace", "Container", "Classic", "PagerCoord")

/** How long the pager must have been quiet before a page counts as resting under the finger. */
private const val REST_QUIESCENCE_MS = 50L

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
 * Whether the pager is genuinely at rest, as opposed to merely reporting no scroll session.
 *
 * Stricter than [PagerState.isSettledOnPage], for a case that back dispatch does not have to care
 * about: a drag's scroll session ends a moment before its fling session begins, and in that gap the
 * pager reports no scroll while `settledPage` falls back to `currentPage` — which past the drag's
 * halfway point already names the neighbour. A press landing there belongs to the swipe in
 * progress, not to the page under the finger.
 *
 * The page offset is deliberately not part of the answer. `PagerState`'s saver persists the current
 * offset fraction, so a configuration change during a swipe restores a non-zero offset with no
 * scroll session open and no drag to end it — an offset term would leave every page permanently
 * press-inert, unrecoverable once swipe gestures are off. `settledPage == page` carries the
 * alignment requirement instead.
 */
class PagerRestState internal constructor(
    private val pagerState: PagerState,
    restingAtCreation: Boolean,
) {

    // True between DragInteraction.Start and its terminal Stop/Cancel: the pager's own
    // isScrollInProgress is false in the gap described above, while the finger is still down.
    internal var dragInProgress by mutableStateOf(false)

    // The latch: false the moment the pager stops being idle, true again once the idle has held for
    // REST_QUIESCENCE_MS. Snapshot-backed, so anything reading it through composition follows it.
    internal var quiescent by mutableStateOf(restingAtCreation)

    internal val isIdle: Boolean
        get() = !pagerState.isScrollInProgress && !dragInProgress

    /**
     * Whether the pager is resting on [page] at this instant.
     *
     * [isIdle] is re-read here rather than trusted to [quiescent] alone: the latch is driven by a
     * snapshotFlow collector, which is asynchronous, so a scroll that has just started can still
     * find it true.
     */
    fun isRestingOn(page: Int): Boolean = quiescent && isIdle && pagerState.settledPage == page
}

/**
 * Remembers a [PagerRestState] for [pagerState].
 *
 * @param interactions test seam; defaults to the pager's own interaction stream, which cannot be
 *     driven from test code
 */
@Composable
fun rememberPagerRestState(
    pagerState: PagerState,
    interactions: Flow<Interaction> = pagerState.interactionSource.interactions,
): PagerRestState {
    // Seeded from the instantaneous predicate, not from false: a holder cannot come into existence
    // mid-gesture, and starting closed would make every page press-inert for the quiescence window
    // after each composition.
    val restState = remember(pagerState) {
        PagerRestState(pagerState, restingAtCreation = !pagerState.isScrollInProgress)
    }

    LaunchedEffect(restState, interactions) {
        interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> restState.dragInProgress = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> restState.dragInProgress = false
                else -> Unit
            }
        }
    }

    LaunchedEffect(restState) {
        snapshotFlow { restState.isIdle }
            .distinctUntilChanged()
            // collectLatest, so the fling session starting cancels the pending open and the latch
            // never opens inside a single swipe.
            .collectLatest { idle ->
                if (!idle) {
                    restState.quiescent = false
                    return@collectLatest
                }
                delay(REST_QUIESCENCE_MS)
                restState.quiescent = true
            }
    }

    return restState
}

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
        // Where the pager currently rests, tracked independently of whether that settle was
        // reported. Also a LOCAL var, and for the same reason: after a restart nothing has been
        // reported yet, so the first settle must be free to report.
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
                    lastSettledPage = settled
                    return@collect
                }

                // De-duplication is decided and recorded BEFORE the sawScroll gate, for the same
                // reason the clamp marker is consumed before it: a programmatic jump can flip
                // isScrollInProgress on and off without snapshotFlow ever observing `true`, so its
                // settle arrives with sawScroll still false. Recorded behind the gate, the pager
                // would move without the record following it, and the next genuine swipe back onto
                // the page it left would be mistaken for a duplicate and never reported - page and
                // focus would then disagree, and later actions would target the wrong workspace.
                //
                // Only the *reporting* stays gated on sawScroll, further down.
                val duplicatePage = settled == lastSettledPage
                lastSettledPage = settled

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
                if (duplicatePage) return@collect

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
