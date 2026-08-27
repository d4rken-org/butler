package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceStacks
import eu.darken.butler.workspace.ui.LocalWorkspacePagerVisibility
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.modal.suppressPressesUnless
import eu.darken.butler.workspace.ui.workspaces.WorkspacePane
import eu.darken.butler.workspace.ui.workspaces.WorkspaceScreenAction
import eu.darken.butler.workspace.ui.workspaces.WorkspaceSwitchIndicator
import eu.darken.butler.workspace.ui.workspaces.WorkspacesViewModel
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val TAG = logTag("Workspace", "Classic", "Container")

// Stable key for the on-demand-creation placeholder page (last index when enabled).
// Distinct from any Workspace.Id so the pager preserves identity across list churn.
private const val PLACEHOLDER_PAGE_KEY = "classic-pager-placeholder"

/**
 * Whether a new tab may be created out from under [rootId].
 *
 * Swiping BETWEEN existing tabs stays allowed for every child type - only leaving a tab that owes
 * its child a result (a picker, the Saver) for a brand-new one is a way to strand that result.
 *
 * The whole ownership unit is consulted rather than the rendered chain: a sibling branch can be
 * composed out and still be waiting. `pausableAsChild` rather than `isPausable` - the latter flips
 * transiently during a package operation, which has nothing to do with owing a result.
 */
internal fun WorkspaceStacks.creationAllowedFor(rootId: Workspace.Id?): Boolean {
    val unit = rootId?.let { unitOf(it) } ?: return true
    return unit.none { it.isSubWorkspace && !it.pausableAsChild }
}

@Composable
internal fun ClassicWorkspaceContainer(
    design: WorkspaceDesign = WorkspaceDesign(),
    state: WorkspacesViewModel.State,
    managerDialogs: List<ManagerDialog> = emptyList(),
    isOverlayVisible: Boolean = false,
    onWorkspaceScreenAction: (WorkspaceScreenAction) -> Unit,
    managerDialogStates: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    onDismissManagerDialog: (Workspace.Id) -> Unit,
    onConfirmManagerDialog: (ManagerDialog.WorkspaceTargeted) -> Unit,
    bannerStates: Map<Workspace.Id, eu.darken.butler.workspace.ui.feedback.BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
    /** Modal chains stacked inside their owning tab's page, keyed by that tab. */
    paneLocalModalChains: Map<Workspace.Id, List<Workspace.Info>> = emptyMap(),
    isFirstTabTourTarget: Boolean = false,
    /** Scrolls the create card into view before the tour's step is published. */
    firstTabTourRequester: BringIntoViewRequester? = null,
    onShareError: (Workspace.Id, Throwable) -> Unit,
) {
    val workspaceActionHandler = LocalWorkspaceButtonProvider.current

    // Stable list of workspace IDs — does NOT re-identity on Workspace.Info field
    // changes (operationCount, attentionCount, etc.), so the coordinator below
    // doesn't trigger spurious pager scrolls when an unrelated workspace updates.
    val tabIds = state.tabWorkspaces.map { it.id }

    val stacks = remember(state.all) { WorkspaceStacks(state.all) }

    // Resolved below, once the pager exists. The page count has to know which root is current, and
    // resolving that falls back to the pager's own settled page — reading it through this holder
    // keeps the two out of a composition-order cycle. Null (only the very first frame) means
    // "nothing known to block"; the pager cannot reach the placeholder within that frame anyway.
    val currentRootIdHolder = remember { mutableStateOf<Workspace.Id?>(null) }
    val placeholderAllowed = state.onDemandWorkspaceCreation && state.swipeGesturesEnabled

    val pagerState = rememberPagerState(
        pageCount = {
            val hasPlaceholder = placeholderAllowed && stacks.creationAllowedFor(currentRootIdHolder.value)
            state.tabWorkspaces.size + if (hasPlaceholder) 1 else 0
        },
    )

    // snapPositionalThreshold: fraction of page that must be scrolled before switching (for low velocity flings)
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = 0.5f,
    )

    // The tab that owns whatever holds focus. The raw focused id is not usable for this: it can be
    // a stacked child, which owns no page of its own. Null when nothing is focused or the chain is
    // dangling/cyclic — deliberately NOT widened by the fallbacks below, because "some page is
    // focused" and "the pager has to be somewhere" are different questions, and answering the first
    // one with a fallback would let a page consume Back while nothing is focused at all.
    val focusedRootId = state.focusedRootId?.takeIf { it in tabIds }

    // Where the PAGER is, which unlike focus can never be nothing. Same answer as focusedRootId
    // whenever that resolves; the fallbacks only cover a focus that names no tab, which nothing
    // repairs on its own. Drives pager coordination, creation blocking and the switch indicator.
    // settledPage, never currentPage — the latter moves mid-gesture and would hand the
    // placeholder's index out as a tab.
    val effectiveRootId: Workspace.Id? = focusedRootId
        ?: state.visibleSelected[0]?.id?.takeIf { it in tabIds }
        ?: state.tabWorkspaces.getOrNull(pagerState.settledPage)?.id
        ?: tabIds.firstOrNull()

    SideEffect { currentRootIdHolder.value = effectiveRootId }

    // What the pager REALLY shows, for auto-pause. Pane assignments and focus still name the
    // outgoing page for the whole of a swipe, so a workspace can be most of the screen and count as
    // hidden. Published from the pager's own layout: the visible pages, unioned with current and
    // target for conservatism (a multi-page fling passes over neighbours that are neither), with
    // the trailing creation placeholder dropped. Keyed on tabIds too, because a list mutation can
    // move ids under unchanged indices.
    val visibility = LocalWorkspacePagerVisibility.current
    var publisherToken by remember { mutableStateOf<Any?>(null) }
    DisposableEffect(visibility) {
        val token = visibility.claim()
        publisherToken = token
        // Covers leaving for a multi-pane layout too: that swaps this container out entirely, and
        // the assignments the adaptive layout goes by must not be second-guessed by a stale set.
        onDispose { visibility.release(token) }
    }
    publisherToken?.let { token ->
        LaunchedEffect(visibility, token, tabIds, pagerState) {
            snapshotFlow {
                val pages = pagerState.layoutInfo.visiblePagesInfo.mapTo(mutableSetOf()) { it.index }
                pages += pagerState.currentPage
                pages += pagerState.targetPage
                pages
            }
                .distinctUntilChanged()
                .collect { pages ->
                    visibility.publish(token, pages.mapNotNullTo(mutableSetOf()) { tabIds.getOrNull(it) })
                }
        }
    }

    val coordinator = rememberPagerFocusCoordinator(
        pagerState = pagerState,
        tabIds = tabIds,
        focused = effectiveRootId,
        isRestoring = state.isRestoring,
        isOverlayVisible = isOverlayVisible,
        onSettled = { settledId ->
            onWorkspaceScreenAction(WorkspaceScreenAction.Select(settledId))
        },
    )

    val restState = rememberPagerRestState(pagerState)

    val hasBlockingDialog = managerDialogs.any { it.isBlocking }

    val scope = rememberCoroutineScope()
    // The owning tab, not the raw focused id: focus can sit on a stacked child, which names no page
    // at all. Taking the raw id would leave this handler disarmed whenever a child holds focus,
    // while that child's own handlers are already disarmed for being off screen — so a press on the
    // placeholder would reach the app-root exit prompt instead of coming back to the tab.
    val backTarget = focusedRootId
    val backTargetPage = backTarget?.let(tabIds::indexOf) ?: -1
    // Deliberately the same expression the focused pane is handed as `backActive` below. The
    // handler underneath is armed only while this is false, so the two can never both consume the
    // same press — that mutual exclusivity is the entire argument for the gate's shape, and it
    // breaks the moment these two drift apart.
    val focusedPaneBackEligible = backTargetPage >= 0 && pagerState.isSettledOnPage(backTargetPage)
    // Only GLOBAL blocking dialogs, unlike hasBlockingDialog above: a WorkspaceTargeted dialog
    // renders inside a pane, and while the pager sits on the placeholder that pane is off screen,
    // so its own back handler is already disarmed. Counting it here would leave nothing at all
    // handling back.
    val hasGlobalBlockingDialog = managerDialogs.any { it.isBlocking && it is ManagerDialog.Global }
    // Read off the pager rather than recomputed: the trailing page comes and goes now (it is
    // dropped while the current tab owns a child that owes it a result), so a second derivation
    // could disagree with the page count the pager is actually using.
    val isOnPlaceholder = state.tabWorkspaces.isNotEmpty() &&
        pagerState.pageCount > state.tabWorkspaces.size &&
        pagerState.settledPage >= state.tabWorkspaces.size

    // Covers every press the focused pane cannot take: parked on the trailing placeholder, mid-move,
    // and the few frames between a swipe settling and focus catching up with it. Without it back
    // falls through to the app-root "press again to exit" prompt during those windows.
    // WorkspaceBackHandler rather than a raw BackHandler on purpose (RawBackHandlerBanTest): there
    // is no enclosing PaneLayerHost here, so both of its locals sit at their `true` defaults and it
    // behaves as an ordinary handler.
    WorkspaceBackHandler(
        enabled = backTarget != null &&
            !focusedPaneBackEligible &&
            !isOverlayVisible &&
            state.fullScreenModalWorkspace == null &&
            !hasGlobalBlockingDialog,
    ) {
        val settling = pagerState.isScrollInProgress
        val settledTab = tabIds.getOrNull(pagerState.settledPage)
        // Mid-move and the placeholder are the expected reasons to land here. At rest on a real
        // page means focus and the pager disagree with nothing in flight to reconcile them, and
        // that is the state presses cannot get out of on their own.
        log(TAG, if (settling || isOnPlaceholder) VERBOSE else WARN) {
            "Pane-level back: focusedRoot=$backTarget targetPage=$backTargetPage " +
                "settled=${pagerState.settledPage} settling=$settling onPlaceholder=$isOnPlaceholder"
        }
        when {
            settling -> Unit
            // The placeholder is no destination, so here the pager is the one that has to move.
            isOnPlaceholder -> backTarget?.let {
                scope.launch { coordinator.scrollToWorkspace(pagerState, tabIds, it) }
            }
            // At rest on a real page: that page is what the user is looking at, so focus adopts it
            // rather than the pager undoing a swipe the user just made. The press itself is still
            // consumed; the next one reaches the page now that the two agree.
            settledTab != null -> onWorkspaceScreenAction(WorkspaceScreenAction.Select(settledTab))
        }
    }

    val creationController = rememberPlaceholderCreationController(
        pagerState = pagerState,
        tabIds = tabIds,
        onDemandEnabled = state.onDemandWorkspaceCreation,
        isInteractionBlocked = isOverlayVisible || state.fullScreenModalWorkspace != null,
        hasBlockingDialog = hasBlockingDialog,
        // Dropping the page is the primary block; this covers the paths that create without
        // consulting the pager at all, the manual click above all.
        creationEnabled = stacks.creationAllowedFor(effectiveRootId),
        onCreateRequested = { onWorkspaceScreenAction(WorkspaceScreenAction.CreateOnDemand) },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (state.tabWorkspaces.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                // Swiping the page out from under an in-flight system back gesture is what makes
                // Back appear dead on ROMs that hand the app the edge touch first.
                modifier = Modifier
                    .fillMaxSize()
                    .ignoreEdgeHorizontalDrags(),
                flingBehavior = flingBehavior,
                userScrollEnabled = state.swipeGesturesEnabled,
                key = { page ->
                    state.tabWorkspaces.getOrNull(page)?.id ?: PLACEHOLDER_PAGE_KEY
                },
            ) { page ->
                val tabInfo = state.tabWorkspaces.getOrNull(page)
                val isPlaceholderPage = page >= state.tabWorkspaces.size

                if (tabInfo == null) {
                    CreatingWorkspacePlaceholder(
                        // Gated at the down, not on the click: `clickable` fires on the up, so a
                        // finger put down on the placeholder's sliver mid-swipe and held through
                        // the settle would create a workspace the gesture never asked for. `page`
                        // is the composed page identity, so the gate cannot drift onto another
                        // index while the list changes under the finger.
                        modifier = Modifier.suppressPressesUnless { restState.isRestingOn(page) },
                        isCreating = isPlaceholderPage && creationController.isCreating,
                        onClick = { creationController.onPlaceholderClick() },
                    )
                } else {
                    val paneInfo = tabInfo.asPaneInfo()
                    val chain = paneLocalModalChains[tabInfo.id].orEmpty()
                    // A modal covering everything takes focus away from the pages below it, exactly
                    // like the tab manager overlay does.
                    val focusSuppressed = isOverlayVisible || state.fullScreenModalWorkspace != null
                    // Strict: who actually holds focus. Drives Back dispatch and which workspace
                    // counts as active.
                    val paneHoldsFocus = !focusSuppressed &&
                        (focusedRootId == tabInfo.id || chain.any { it.id == state.focused })
                    // Widened, for press handling only: while focus resolves to no tab at all, the
                    // pane boundary would consume every press to request a focus that never
                    // arrives, leaving the visible page tap-dead (observed on device after a
                    // pane-local modal closes, after picker-driven tab creation and after a session
                    // restore). No other pane can hold focus in this single-pane pager, so the page
                    // the pager rests on keeps accepting presses.
                    val paneAcceptsPresses = paneHoldsFocus ||
                        (!focusSuppressed && focusedRootId == null && effectiveRootId == tabInfo.id)
                    // Deepest layer is the active one; global focus can sit on a covered ancestor
                    // (launchPicker never moves it). Strict basis: a page that merely accepts
                    // presses names no active workspace.
                    val activeId = (chain.lastOrNull()?.id ?: tabInfo.id).takeIf { paneHoldsFocus }
                    WorkspacePane(
                        info = paneInfo,
                        design = design,
                        paneFocused = paneAcceptsPresses,
                        clickToFocus = state.paneClickToFocus,
                        // Back must not reach a page the pager is not resting on. Parked on the
                        // trailing placeholder, focus legitimately stays on the last tab, so
                        // without this the off-screen Explorer's back-at-root handler consumes
                        // back and closes that tab. Inherited by the whole stack: a child modal's
                        // handlers go through WorkspaceBackHandler too. Strict focus, never the
                        // widened press variant: no page may arm a back handler while focus names
                        // no tab.
                        backActive = paneHoldsFocus && pagerState.isSettledOnPage(page),
                        // The press-side mirror of backActive above: while the pager is moving,
                        // two pages share the viewport, so a finger-down starting the next swipe
                        // lands on the partially visible neighbour. Answering it would select a
                        // tab the gesture never chose, and the swipe would end somewhere else.
                        // Stricter than isSettledOnPage, which is briefly true in the gap between
                        // a drag and its fling.
                        allowPresses = { restState.isRestingOn(page) },
                        activeWorkspaceId = activeId,
                        childModals = chain.map { it.asPaneInfo() },
                        // Always the page's own tab: a Focus() for a stacked child is dropped.
                        onRequestPaneFocus = {
                            onWorkspaceScreenAction(WorkspaceScreenAction.Select(tabInfo.id))
                        },
                        managerDialogStates = managerDialogStates,
                        onDismissManagerDialog = onDismissManagerDialog,
                        onConfirmManagerDialog = onConfirmManagerDialog,
                        bannerStates = bannerStates,
                        onDismissBanner = onDismissBanner,
                        paneEdges = design.paneEdges,
                        onShareError = onShareError,
                        onCloseWorkspace = { workspaceId ->
                            workspaceActionHandler?.executeWorkspaceAction(
                                WorkspaceAction.Close(workspaceId)
                            )
                        },
                        onResumeWorkspace = { workspaceId ->
                            onWorkspaceScreenAction(WorkspaceScreenAction.ResumeWorkspace(workspaceId))
                        },
                    )
                }
            }
        } else {
            EmptyClassicWorkspaceContent(
                modifier = Modifier
                    // Horizontal via the pane helper so cutouts are covered too, vertical from the
                    // system bars as before.
                    .paneHorizontalInsetPadding(design.paneEdges)
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)),
                isUpgraded = state.isUpgraded,
                isTourTarget = isFirstTabTourTarget,
                tourRequester = firstTabTourRequester,
            )
        }

        // Position indicator overlay. Resolved through the owning tab, so it stays up while a
        // stacked child holds focus - which is exactly when the user is swiping between tabs.
        val currentWorkspace = effectiveRootId?.let { rootId ->
            state.tabWorkspaces.firstOrNull { it.id == rootId }
        }
        if (currentWorkspace != null && state.tabWorkspaces.size > 1) {
            val position = state.tabWorkspaces.indexOfFirst { it.id == currentWorkspace.id } + 1
            if (position > 0) {
                WorkspaceSwitchIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp),
                    info = currentWorkspace,
                    position = position,
                    totalWorkspaces = state.tabWorkspaces.size,
                )
            }
        }
    }
}