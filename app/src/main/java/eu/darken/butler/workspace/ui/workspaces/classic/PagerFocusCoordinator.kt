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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val TAG = logTag("Workspace", "Container", "Classic", "PagerCoord")

class PagerFocusCoordinatorState internal constructor() {
    var isAnimatingProgrammatically: Boolean = false
        internal set
    internal var lastSyncedFocusId: Workspace.Id? = null
    internal var lastUserSwipeFocusId: Workspace.Id? = null
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
 * - Wraps every programmatic scroll in `try/finally` so
 *   [PagerFocusCoordinatorState.isAnimatingProgrammatically] is always reset,
 *   even on cancellation.
 *
 * @param pagerState the pager being driven
 * @param tabIds stable list of currently-displayed workspace IDs (placeholder
 *     pages excluded)
 * @param focused the workspace the VM currently has focused
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
        val desiredId = focused ?: return@LaunchedEffect

        if (desiredId == coordinator.lastUserSwipeFocusId) {
            log(TAG, VERBOSE) { "Skip pager sync — focus came from user swipe: $desiredId" }
            coordinator.lastUserSwipeFocusId = null
            coordinator.lastSyncedFocusId = desiredId
            return@LaunchedEffect
        }

        val targetIndex = tabIds.indexOf(desiredId)
        if (targetIndex < 0) {
            log(TAG, VERBOSE) { "Focus $desiredId not in tabIds yet — waiting" }
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

        coordinator.isAnimatingProgrammatically = true
        try {
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
        } finally {
            coordinator.isAnimatingProgrammatically = false
        }
        coordinator.lastSyncedFocusId = desiredId
    }

    LaunchedEffect(pagerState, tabIds) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .filter { (_, scrolling) -> !scrolling }
            .map { (settled, _) -> settled }
            .distinctUntilChanged()
            .collect { settled ->
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
