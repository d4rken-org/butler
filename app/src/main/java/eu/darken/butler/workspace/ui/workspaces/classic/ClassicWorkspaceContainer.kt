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
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceStacks
import eu.darken.butler.workspace.ui.LocalWorkspacePagerVisibility
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.workspaces.WorkspacePane
import eu.darken.butler.workspace.ui.workspaces.WorkspaceScreenAction
import eu.darken.butler.workspace.ui.workspaces.WorkspaceSwitchIndicator
import eu.darken.butler.workspace.ui.workspaces.WorkspacesViewModel
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// Stable key for the on-demand-creation placeholder page (last index when enabled).
// Distinct from any Workspace.Id so the pager preserves identity across list churn.
private const val PLACEHOLDER_PAGE_KEY = "classic-pager-placeholder"

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

    // Swiping BETWEEN tabs stays allowed for every child type; only creating a NEW tab out from
    // under a result-returning child is not. The whole ownership unit is consulted rather than the
    // rendered chain: a sibling branch can be composed out and still be waiting for its result.
    // pausableAsChild rather than isPausable — the latter flips transiently during a package
    // operation, which would disable creation for reasons that have nothing to do with results.
    fun creationAllowedFor(rootId: Workspace.Id?): Boolean {
        val unit = rootId?.let { stacks.unitOf(it) } ?: return true
        return unit.none { it.isSubWorkspace && !it.pausableAsChild }
    }

    // Resolved below, once the pager exists. The page count has to know which root is current, and
    // resolving that falls back to the pager's own settled page — reading it through this holder
    // keeps the two out of a composition-order cycle. Null (only the very first frame) means
    // "nothing known to block"; the pager cannot reach the placeholder within that frame anyway.
    val currentRootIdHolder = remember { mutableStateOf<Workspace.Id?>(null) }
    val placeholderAllowed = state.onDemandWorkspaceCreation && state.swipeGesturesEnabled

    val pagerState = rememberPagerState(
        pageCount = {
            val hasPlaceholder = placeholderAllowed && creationAllowedFor(currentRootIdHolder.value)
            state.tabWorkspaces.size + if (hasPlaceholder) 1 else 0
        },
    )

    // snapPositionalThreshold: fraction of page that must be scrolled before switching (for low velocity flings)
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = 0.5f,
    )

    // The one answer to "which tab is the user in", for the pager, pane focus, creation blocking and
    // the switch indicator alike. Focus itself is not usable: it can sit on a stacked child, which
    // owns no page. rootOf() returns null for a dangling or cyclic chain and nothing repairs such a
    // focus on its own, hence the fallbacks. settledPage, never currentPage — the latter moves
    // mid-gesture and would hand the placeholder's index out as a tab.
    val effectiveRootId: Workspace.Id? = state.focusedRootId?.takeIf { it in tabIds }
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

    val hasBlockingDialog = managerDialogs.any { it.isBlocking }

    val scope = rememberCoroutineScope()
    val backTarget = state.focused?.takeIf { it in tabIds }
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
        // On the placeholder and at rest: return to the focused tab. Otherwise the pager is
        // mid-move or focus has not caught up with a settle yet — swallow the press rather than
        // let it reach the app-root exit prompt, and let the user press again once things settle.
        if (isOnPlaceholder && !pagerState.isScrollInProgress) {
            backTarget?.let { scope.launch { coordinator.scrollToWorkspace(pagerState, tabIds, it) } }
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
        creationEnabled = creationAllowedFor(effectiveRootId),
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
                modifier = Modifier.fillMaxSize(),
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
                        isCreating = isPlaceholderPage && creationController.isCreating,
                        onClick = { creationController.onPlaceholderClick() },
                    )
                } else {
                    val paneInfo = tabInfo.asPaneInfo()
                    val chain = paneLocalModalChains[tabInfo.id].orEmpty()
                    // A modal covering everything takes focus away from the pages below it, exactly
                    // like the tab manager overlay does.
                    val focusSuppressed = isOverlayVisible || state.fullScreenModalWorkspace != null
                    val paneIsFocused = !focusSuppressed &&
                        (effectiveRootId == tabInfo.id || chain.any { it.id == state.focused })
                    // Deepest layer is the active one; global focus can sit on a covered ancestor
                    // (launchPicker never moves it).
                    val activeId = (chain.lastOrNull()?.id ?: tabInfo.id).takeIf { paneIsFocused }
                    WorkspacePane(
                        info = paneInfo,
                        design = design,
                        paneFocused = paneIsFocused,
                        // Back must not reach a page the pager is not resting on. Parked on the
                        // trailing placeholder, focus legitimately stays on the last tab, so
                        // without this the off-screen Explorer's back-at-root handler consumes
                        // back and closes that tab. Inherited by the whole stack: a child modal's
                        // handlers go through WorkspaceBackHandler too.
                        backActive = paneIsFocused && pagerState.isSettledOnPage(page),
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