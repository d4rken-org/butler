package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

private val TAG = logTag("Workspace", "Container", "Classic", "Placeholder")

/** How long the pager must rest on the placeholder, eligible, before auto-creation fires. */
private const val CREATION_DWELL_MS = 100L

/**
 * How long the pager must stay idle before a completed gesture is evaluated. A drag's scroll
 * session ends momentarily before its fling/snap session begins; without this quiescence window
 * the gesture token would be consumed at that intermediate idle — at the wrong page.
 */
private const val SETTLE_QUIESCENCE_MS = 50L

class PlaceholderCreationController internal constructor() {
    var creationState by mutableStateOf<PlaceholderCreationState>(PlaceholderCreationState.Idle)
        internal set

    val isCreating: Boolean
        get() = creationState is PlaceholderCreationState.Creating

    // True between DragInteraction.Start and its terminal Stop/Cancel. Snapshot-backed because
    // the settle evaluator's snapshotFlow must re-evaluate when it changes.
    internal var dragInProgress by mutableStateOf(false)

    // One-shot token set when a drag completes normally (Stop). Consumed at the gesture's final
    // settle, dropped on Cancel or any environment change. Only a token-backed settle on the
    // placeholder may arm auto-creation; programmatic scrolls and list mutations never carry one.
    internal var gestureArmed: Boolean = false

    // Tab ids captured when creation was dispatched. Success = an id appearing that wasn't in
    // this set, so an unrelated close during creation can never be mistaken for success (and a
    // simultaneous close+create still counts as one).
    internal var idsAtTrigger: Set<Workspace.Id> = emptySet()

    // Plumbing refreshed via SideEffect by rememberPlaceholderCreationController().
    internal var currentTabIds: List<Workspace.Id> = emptyList()
    internal var onCreate: () -> Unit = {}

    fun onPlaceholderClick() {
        when (creationState) {
            PlaceholderCreationState.Idle,
            PlaceholderCreationState.Visiting,
            PlaceholderCreationState.Blocked,
                -> {
                log(TAG, INFO) { "Manual click triggered workspace creation" }
                triggerCreation()
            }
            else -> Unit
        }
    }

    internal fun triggerCreation() {
        idsAtTrigger = currentTabIds.toSet()
        creationState = PlaceholderCreationState.Creating
        onCreate()
    }
}

/**
 * Owns workspace auto-creation from the trailing pager placeholder page.
 *
 * The core rule: creation intent requires a real user drag. The pager also "settles" on the
 * placeholder when the tab list shrinks underneath it (closing the last tab hands its index to
 * the placeholder) — that must never create a workspace, which was the phantom-tab-recreation
 * bug. A gesture token from [interactions] is armed when a drag completes (Stop), revoked on
 * Cancel, and consumed once the pager has been idle for [SETTLE_QUIESCENCE_MS] — i.e. at the
 * gesture's final settle position, after any fling.
 *
 * The dwell then requires continuous eligibility for [CREATION_DWELL_MS]: settled on the
 * placeholder, on-demand creation enabled, no overlay/modal blocking interaction, no blocking
 * dialog. Any break cancels the pending trigger ([collectLatest]), so swiping away or an
 * appearing overlay during the dwell aborts cleanly instead of racing it.
 *
 * Known limitation: while a dispatched creation is in flight, a new tab id appearing from an
 * UNRELATED creation also ends the Creating state (spinner clears early, a second click becomes
 * possible). Tying completion to the specific request needs result plumbing through the
 * screen-action path; the pre-rework behavior (any count change ended Creating) was strictly
 * looser.
 *
 * @param interactions test seam; defaults to the pager's own interaction stream
 */
@Composable
fun rememberPlaceholderCreationController(
    pagerState: PagerState,
    tabIds: List<Workspace.Id>,
    onDemandEnabled: Boolean,
    isInteractionBlocked: Boolean,
    hasBlockingDialog: Boolean,
    onCreateRequested: () -> Unit,
    interactions: Flow<Interaction> = pagerState.interactionSource.interactions,
): PlaceholderCreationController {
    val controller = remember { PlaceholderCreationController() }
    SideEffect {
        controller.currentTabIds = tabIds
        controller.onCreate = onCreateRequested
    }

    val currentTabCount by rememberUpdatedState(tabIds.size)
    val currentOnDemand by rememberUpdatedState(onDemandEnabled)
    val currentBlocked by rememberUpdatedState(isInteractionBlocked)
    val currentDialog by rememberUpdatedState(hasBlockingDialog)

    val settledPage by remember { derivedStateOf { pagerState.settledPage } }
    val isScrolling by remember { derivedStateOf { pagerState.isScrollInProgress } }

    // Gesture token lifecycle. Arm only on Stop — a canceled drag must not create, and a Cancel
    // arriving after the pager already visited the placeholder revokes that visit.
    LaunchedEffect(interactions) {
        interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    controller.gestureArmed = false
                    controller.dragInProgress = true
                }
                is DragInteraction.Stop -> {
                    controller.dragInProgress = false
                    controller.gestureArmed = true
                }
                is DragInteraction.Cancel -> {
                    controller.dragInProgress = false
                    controller.gestureArmed = false
                    if (controller.creationState == PlaceholderCreationState.Visiting) {
                        log(TAG, VERBOSE) { "Drag canceled, revoking placeholder visit" }
                        controller.creationState = PlaceholderCreationState.Idle
                    }
                }
                else -> Unit
            }
        }
    }

    // Any environment change invalidates a pending gesture: page meanings may have shifted under
    // the user's finger, so require a fresh swipe. A list mutation also disarms an armed Visiting —
    // the transition effect below can't catch mutations that leave the pager on the placeholder
    // (same-size reorders, shrinks that hand the index straight to the placeholder).
    LaunchedEffect(tabIds, onDemandEnabled, isInteractionBlocked, hasBlockingDialog) {
        controller.gestureArmed = false
        if (controller.creationState == PlaceholderCreationState.Visiting) {
            log(TAG, VERBOSE) { "Workspace list or environment changed while Visiting, resetting to Idle" }
            controller.creationState = PlaceholderCreationState.Idle
        }
    }

    // Consume the gesture token at the gesture's FINAL settle: pager idle, no drag in progress,
    // and the idle has held for the quiescence window (a fling restart cancels the pending
    // evaluation via collectLatest). Arm Visiting only when that settle is on the placeholder.
    LaunchedEffect(controller, pagerState) {
        snapshotFlow { !pagerState.isScrollInProgress && !controller.dragInProgress }
            .distinctUntilChanged()
            .collectLatest { idle ->
                if (!idle) return@collectLatest
                delay(SETTLE_QUIESCENCE_MS)
                if (!controller.gestureArmed) return@collectLatest
                controller.gestureArmed = false

                val onPlaceholder = currentTabCount > 0 && pagerState.settledPage >= currentTabCount
                if (!onPlaceholder) return@collectLatest
                if (!currentOnDemand || currentBlocked || currentDialog) return@collectLatest
                if (controller.creationState != PlaceholderCreationState.Idle) return@collectLatest

                log(TAG, INFO) { "User gesture settled on placeholder, transitioning to Visiting" }
                controller.creationState = PlaceholderCreationState.Visiting
            }
    }

    // Dwell: eligibility must hold continuously for CREATION_DWELL_MS. collectLatest cancels the
    // pending trigger the moment eligibility breaks.
    LaunchedEffect(controller, pagerState) {
        snapshotFlow {
            controller.creationState == PlaceholderCreationState.Visiting &&
                !pagerState.isScrollInProgress &&
                currentTabCount > 0 &&
                pagerState.settledPage >= currentTabCount &&
                currentOnDemand && !currentBlocked && !currentDialog
        }
            .distinctUntilChanged()
            .collectLatest { eligible ->
                if (!eligible) return@collectLatest
                delay(CREATION_DWELL_MS)
                if (controller.creationState == PlaceholderCreationState.Visiting) {
                    log(TAG, INFO) { "Auto-triggering workspace creation from placeholder" }
                    controller.triggerCreation()
                }
            }
    }

    // Post-arming state transitions
    LaunchedEffect(
        settledPage,
        isScrolling,
        tabIds,
        onDemandEnabled,
        isInteractionBlocked,
        hasBlockingDialog,
        controller.creationState,
    ) {
        val isOnPlaceholder = tabIds.isNotEmpty() && settledPage >= tabIds.size
        val current = controller.creationState

        val next = when (current) {
            // Visiting entry is gesture-gated in the settle evaluator above
            is PlaceholderCreationState.Idle -> current
            is PlaceholderCreationState.Visiting -> when {
                !onDemandEnabled || isInteractionBlocked || hasBlockingDialog -> {
                    log(TAG, VERBOSE) { "Placeholder no longer eligible, resetting to Idle" }
                    PlaceholderCreationState.Idle
                }
                !isScrolling && !isOnPlaceholder -> {
                    log(TAG, VERBOSE) { "Left placeholder page, resetting to Idle" }
                    PlaceholderCreationState.Idle
                }
                else -> current
            }
            is PlaceholderCreationState.Creating -> when {
                hasBlockingDialog -> {
                    log(TAG, INFO) { "Blocking dialog shown (limit reached), transitioning to Failed" }
                    PlaceholderCreationState.Failed
                }
                tabIds.any { it !in controller.idsAtTrigger } -> {
                    log(TAG, INFO) { "New workspace appeared, creation succeeded" }
                    PlaceholderCreationState.Idle
                }
                !isScrolling && !isOnPlaceholder -> {
                    // Navigated away mid-creation (e.g. a singleton AlreadyOpen result focused an
                    // existing tab) — the spinner context is gone either way.
                    log(TAG, VERBOSE) { "Left placeholder during creation, resetting to Idle" }
                    PlaceholderCreationState.Idle
                }
                else -> current
            }
            is PlaceholderCreationState.Failed -> when {
                hasBlockingDialog || isScrolling -> current
                isOnPlaceholder -> {
                    log(TAG, INFO) { "Dialog dismissed but still on placeholder, transitioning to Blocked" }
                    PlaceholderCreationState.Blocked
                }
                else -> {
                    log(TAG, VERBOSE) { "Dialog dismissed and left placeholder, resetting to Idle" }
                    PlaceholderCreationState.Idle
                }
            }
            is PlaceholderCreationState.Blocked -> when {
                !isScrolling && !isOnPlaceholder -> {
                    log(TAG, VERBOSE) { "Left placeholder from Blocked state, resetting to Idle" }
                    PlaceholderCreationState.Idle
                }
                else -> current
            }
        }

        if (next != current) {
            controller.creationState = next
        }
    }

    return controller
}
