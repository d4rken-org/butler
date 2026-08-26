package eu.darken.butler.workspace.ui.workspaces

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.LocalGuidedTourController
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.main.ui.motd.MotdCard
import eu.darken.butler.main.ui.review.ReviewCard
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.LocalWorkspacePagerVisibility
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.dialogs.ClearSessionConfirmationDialog
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.WorkspaceLimitDialog
import eu.darken.butler.workspace.ui.dialogs.WorkspaceRenameDialog
import eu.darken.butler.workspace.ui.feedback.BannerState
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerScreen
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import eu.darken.butler.workspace.ui.manager.tour.WorkspaceManagerTour
import eu.darken.butler.workspace.ui.floatingbar.LocalWorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.manager.rememberWindowSizeInfo
import eu.darken.butler.workspace.ui.scroll.LocalWorkspaceScrollPositions
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.classic.ClassicWorkspaceContainer
import eu.darken.butler.workspace.ui.workspaces.tour.FirstTabTour
import kotlin.uuid.Uuid

@Composable
fun WorkspaceScreen(
    state: WorkspacesViewModel.State,
    bannerStates: Map<Workspace.Id, BannerState> = emptyMap(),
    managerDialogStates: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    managerDialogs: List<ManagerDialog> = emptyList(),
    isOverlayVisible: Boolean = false,
    reviewActivity: Activity? = null,
    onScreenAction: (WorkspaceScreenAction) -> Unit,
    onHideMotd: (Uuid) -> Unit = {},
    onDismissMotd: (Uuid) -> Unit = {},
    onMotdLinkClick: (String) -> Unit = {},
    onReviewDismiss: () -> Unit = {},
    onReviewNow: (Activity) -> Unit = {},
    onDismissBanner: (Workspace.Id) -> Unit = {},
    onDismissManagerDialog: (Workspace.Id) -> Unit = {},
    onConfirmManagerDialog: (ManagerDialog.WorkspaceTargeted) -> Unit = {},
    onShareError: (Workspace.Id, Throwable) -> Unit = { _, _ -> },
) {
    val workspaceActionHandler = LocalWorkspaceButtonProvider.current
    val windowSizeInfo = rememberWindowSizeInfo()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var showPaneNumbers by remember { mutableStateOf(false) }
    var showPaneOverlay by remember { mutableStateOf(false) }

    // Held as an id, not a captured Info: the automatic title can change, another surface can
    // rename it, or the tab can close while the dialog is open.
    var renameTargetId by remember { mutableStateOf<Workspace.Id?>(null) }

    var dividerPositions by rememberSaveable {
        mutableStateOf(DividerPositions())
    }

    // Select panel mode based on orientation
    val effectivePanelMode = if (isLandscape) {
        state.landscapePanelMode
    } else {
        state.portraitPanelMode
    }

    val effectivePaneLayout = when (effectivePanelMode) {
        WorkspacePanelMode.AUTO -> windowSizeInfo.recommendedLayout
        WorkspacePanelMode.SINGLE -> WorkspaceDesign.Layout.SINGLE
        WorkspacePanelMode.DUAL_VERTICAL -> WorkspaceDesign.Layout.DUAL_VERTICAL
        WorkspacePanelMode.DUAL_HORIZONTAL -> WorkspaceDesign.Layout.DUAL_HORIZONTAL
        WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT -> WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT
        WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT -> WorkspaceDesign.Layout.TRIPLE_MAIN_RIGHT
        WorkspacePanelMode.QUAD_GRID -> WorkspaceDesign.Layout.QUAD_GRID
    }

    val design = WorkspaceDesign(
        layout = effectivePaneLayout,
    )

    // Update pane count when design changes
    LaunchedEffect(design.maxPanes) {
        onScreenAction(WorkspaceScreenAction.SetPaneCount(design.maxPanes))
    }

    // Restoration starts with an empty tabWorkspaces even when the user has saved tabs, so the
    // isRestoring guard is what keeps the tour off a restoring session. tabWorkspaces.isEmpty()
    // gates BOTH layouts: an empty pane next to an occupied one is not "no tabs yet".
    val firstTabTourEligible = !state.isRestoring && state.tabWorkspaces.isEmpty()
    // With zero tabs every pane is empty, so the first one is always the one to tag - no scan needed.
    val firstTabTourPaneNumber: Int? = if (!firstTabTourEligible || design.isSingle) null else 1

    // Both empty-state surfaces scroll vertically, so on a short viewport the create/add-tab card
    // starts below the fold with no bounds to anchor on. The tour's prepareTarget brings it in
    // before the step is published.
    val createTabRequester = remember { BringIntoViewRequester() }

    val tourController = LocalGuidedTourController.current
    val firstTabTourDefinition = remember(createTabRequester) {
        FirstTabTour.definition(prepareCreateTab = { createTabRequester.bringIntoView() })
    }
    var tourStartAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(firstTabTourEligible, isOverlayVisible) {
        // Starting under the manager overlay would anchor on a card the user cannot see.
        if (!firstTabTourEligible || isOverlayVisible || tourStartAttempted) return@LaunchedEffect
        // tryStart is atomic: `attempted` is only set when the start actually took, so a transient
        // block (another tour active) cannot permanently suppress this one.
        tourStartAttempted = tourController.tryStart(firstTabTourDefinition)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main workspace content
        if (!design.isSingle) {
            AdaptiveWorkspaceLayout(
                design = design,
                workspaces = state.tabWorkspaces,
                selected = state.selected,
                visibleSelected = state.visibleSelected,
                focusedId = state.focused,
                dividerPositions = dividerPositions,
                onDividerPositionsChange = { newPositions ->
                    dividerPositions = newPositions
                },
                showPaneNumbers = showPaneNumbers,
                showPaneOverlay = showPaneOverlay,
                onPaneMenuToggle = { isOpen ->
                    showPaneOverlay = isOpen
                    showPaneNumbers = isOpen
                },
                onScreenAction = onScreenAction,
                managerDialogStates = managerDialogStates,
                onDismissManagerDialog = onDismissManagerDialog,
                onConfirmManagerDialog = onConfirmManagerDialog,
                bannerStates = bannerStates,
                onDismissBanner = onDismissBanner,
                onRenameWorkspace = { renameTargetId = it },
                paneLocalModalChains = state.paneLocalModalChains,
                clickToFocus = state.paneClickToFocus,
                isUpgraded = state.isUpgraded,
                isOverlayVisible = isOverlayVisible,
                // A full-screen modal covers every pane, so none of them may stay focus- or
                // back-active underneath it. Classic already guards this via its own container.
                fullScreenModalVisible = state.fullScreenModalWorkspace != null,
                firstTabTourPaneNumber = firstTabTourPaneNumber,
                firstTabTourRequester = createTabRequester,
                onShareError = onShareError,
            )
        } else {
            ClassicWorkspaceContainer(
                state = state,
                managerDialogs = managerDialogs,
                isOverlayVisible = isOverlayVisible,
                onWorkspaceScreenAction = onScreenAction,
                managerDialogStates = managerDialogStates,
                onDismissManagerDialog = onDismissManagerDialog,
                onConfirmManagerDialog = onConfirmManagerDialog,
                bannerStates = bannerStates,
                onDismissBanner = onDismissBanner,
                paneLocalModalChains = state.paneLocalModalChains,
                isFirstTabTourTarget = firstTabTourEligible,
                firstTabTourRequester = createTabRequester,
                onShareError = onShareError,
            )
        }

        // MOTD overlay
        state.motd?.let { motd ->
            MotdCard(
                motd = motd,
                onHide = { onHideMotd(motd.id) },
                onMarkAsRead = onDismissMotd,
                onLinkClick = onMotdLinkClick,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Review overlay, gated to a quiet screen by the ViewModel
        if (state.showReviewCard) {
            ReviewCard(
                activity = reviewActivity,
                onDismiss = onReviewDismiss,
                onReview = onReviewNow,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    renameTargetId?.let { targetId ->
        val target = state.tabWorkspaces.firstOrNull { it.id == targetId }
        if (target == null) {
            renameTargetId = null
        } else {
            key(targetId) {
                WorkspaceRenameDialog(
                    currentCustomTitle = target.customTitle,
                    autoTitle = target.title.get(LocalContext.current),
                    onConfirm = { newTitle ->
                        renameTargetId = null
                        onScreenAction(WorkspaceScreenAction.Rename(targetId, newTitle))
                    },
                    onDismiss = { renameTargetId = null },
                )
            }
        }
    }

    // Full-screen modal workspace overlay (anything asking for FULL_SCREEN presentation)
    state.fullScreenModalWorkspace?.let { fullScreenModal ->
        WorkspaceModalDialog(
            workspace = fullScreenModal,
            design = design,
            onDismissRequest = {
                // Dismiss by closing the modal workspace
                workspaceActionHandler?.executeWorkspaceAction(
                    WorkspaceAction.Close(fullScreenModal.id)
                )
            },
            onShareError = { error -> onShareError(fullScreenModal.id, error) },
            onCloseWorkspace = {
                workspaceActionHandler?.executeWorkspaceAction(
                    WorkspaceAction.Close(fullScreenModal.id)
                )
            },
            onResumeWorkspace = {
                onScreenAction(WorkspaceScreenAction.ResumeWorkspace(fullScreenModal.id))
            },
        )
    }
}

/**
 * Dismisses the tab manager overlay on back.
 *
 * Registered above the workspace content, so it loses every LIFO race against a handler inside a
 * pane — the panes going unfocused while the overlay is up is what disables those and leaves this
 * one to win. Kept as its own composable so the regression test can drive the real registration
 * instead of a stand-in.
 *
 * This registers an UNGATED raw back handler: composed anywhere inside the workspace tree it would
 * outrank the pane handlers exactly like the bug it exists to prevent. Only [WorkspacesScreenHost]
 * may compose it, which `RawBackHandlerBanTest` enforces; `internal` is as tight as the visibility
 * can go while the test can still reach it.
 */
@Composable
internal fun ManagerOverlayBackHandler(
    isOverlayVisible: Boolean,
    onDismiss: () -> Unit,
) = BackHandler(enabled = isOverlayVisible, onBack = onDismiss)

@Composable
fun WorkspacesScreenHost(
    vm: WorkspacesViewModel = hiltViewModel(),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
    managerVm: WorkspaceManagerViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    ErrorEventHandler(managerVm)
    ErrorEventHandler(workspaceButtonVm)
    NavigationEventHandler(vm, workspaceButtonVm, managerVm)

    val context = LocalContext.current
    val activity = context as? Activity
    val focusManager = LocalFocusManager.current
    val bannerStatesRaw by vm.bannerStates.collectAsState(initial = emptyMap())
    val bannerStates = bannerStatesRaw ?: emptyMap()
    val showClearSessionConfirmationRaw by vm.showClearSessionConfirmation.collectAsState(initial = false)
    val showClearSessionConfirmation = showClearSessionConfirmationRaw ?: false
    val managerDialogs by vm.managerDialogs.collectAsState()
    val pageManagerState by vm.workspacePageManager.state.collectAsState()
    val managerState by managerVm.state.collectAsState(initial = null)

    // Derive dialog states from unified registry
    val managerDialogStates = remember(managerDialogs) {
        managerDialogs
            .filterIsInstance<ManagerDialog.WorkspaceTargeted>()
            .associateBy { it.targetWorkspaceId }
    }
    val workspaceLimitDialog = remember(managerDialogs) {
        managerDialogs.filterIsInstance<ManagerDialog.Global.WorkspaceLimitReached>().firstOrNull()
    }

    LaunchedEffect(Unit) {
        vm.shareIntentEvent.collect { intent ->
            context.startActivity(intent)
        }
    }

    // Clear system focus and invalidate preview cache when manager overlay becomes visible
    LaunchedEffect(pageManagerState.isManagerOverlayVisible) {
        if (pageManagerState.isManagerOverlayVisible) {
            focusManager.clearFocus()
            managerVm.onScreenAppeared()
        }
    }

    val tourController = LocalGuidedTourController.current
    // Gated on the manager's own state, not just the overlay flag: a restored session makes the
    // overlay visible before that state arrives, and the manager - with the tour's only anchor -
    // is composed no earlier than the state is non-null. A tour started in that window finds no
    // anchor, grace-skips its single step, and suppresses itself for the rest of the process.
    val managerTourReady = pageManagerState.isManagerOverlayVisible && managerState != null
    LaunchedEffect(managerTourReady) {
        if (!managerTourReady) return@LaunchedEffect
        // No attempted-flag: this key already runs the body once per open, and tryStart itself
        // refuses a tour that is completed, dismissed, or skipped this process.
        tourController.tryStart(WorkspaceManagerTour.definition)
    }

    ManagerOverlayBackHandler(
        isOverlayVisible = pageManagerState.isManagerOverlayVisible,
        onDismiss = { vm.workspacePageManager.hideManagerOverlay() },
    )

    val state by vm.state.collectAsState(initial = null)

    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides workspaceButtonVm,
        LocalWorkspacePageHosts provides vm.pageHosts,
        LocalWorkspaceScrollPositions provides vm.scrollPositions,
        LocalWorkspaceBarCollapseStates provides vm.barCollapseStates,
        LocalWorkspacePagerVisibility provides vm.pagerVisibility,
    ) {
        state?.let { state ->
            WorkspaceScreen(
                state = state,
                bannerStates = bannerStates,
                managerDialogStates = managerDialogStates,
                managerDialogs = managerDialogs,
                isOverlayVisible = pageManagerState.isManagerOverlayVisible,
                reviewActivity = activity,
                onScreenAction = { vm.executeScreenAction(it) },
                onHideMotd = { vm.hideMotd(it) },
                onDismissMotd = { vm.dismissMotd(it) },
                onMotdLinkClick = { vm.openMotdLink(it) },
                onReviewDismiss = { vm.reviewDismiss() },
                onReviewNow = { vm.reviewNow(it) },
                onDismissBanner = { vm.dismissBanner(it) },
                onDismissManagerDialog = { vm.dismissManagerDialog(it) },
                onConfirmManagerDialog = { vm.confirmManagerDialog(it) },
                onShareError = { workspaceId, error -> vm.shareWorkspaceError(workspaceId, error) },
            )
        }

        // Manager overlay
        if (pageManagerState.isManagerOverlayVisible) {
            managerState?.let { currentManagerState ->
                WorkspaceManagerScreen(
                    state = currentManagerState,
                    onCloseWorkspace = managerVm::closeWorkspace,
                    onReorderWorkspaces = managerVm::reorderWorkspaces,
                    onSelectWorkspace = managerVm::selectWorkspace,
                    onPauseWorkspace = managerVm::pauseWorkspace,
                    onResumeWorkspace = managerVm::resumeWorkspace,
                    onCreateWorkspace = managerVm::createWorkspace,
                    onQuickCreate = managerVm::createWorkspace,
                    onNavigateBack = managerVm::navigateBack,
                    onDismissBadgeExplanation = managerVm::dismissBadgeExplanation,
                    onCloseAllWorkspaces = managerVm::closeAllWorkspaces,
                    onRenameWorkspace = managerVm::renameWorkspace,
                    onTabsClick = managerVm::clearFilters,
                    onOperationsFilterClick = managerVm::toggleOperationsFilter,
                    onAttentionFilterClick = managerVm::toggleAttentionFilter,
                )
            }
        }

        if (showClearSessionConfirmation) {
            ClearSessionConfirmationDialog(
                onDismiss = { vm.dismissClearSessionConfirmation() },
                onConfirm = { vm.confirmClearSession() },
            )
        }

        // WorkspaceLimitDialog renders above everything - visible over both workspace and manager
        workspaceLimitDialog?.let { dialogState ->
            // Keyed on the confirmation: a recovery that no longer fits re-posts a fresh dialog, and
            // it must not inherit the tab ticks the user made against the previous tab list.
            key(dialogState.id) {
                WorkspaceLimitDialog(
                    limit = dialogState.limit,
                    onDismiss = { vm.dismissWorkspaceLimitDialog() },
                    onUpgrade = { vm.onUpgradeFromLimitDialog() },
                    candidates = dialogState.candidates,
                    canRecover = dialogState.canRecover,
                    minToClose = dialogState.minToClose,
                    onCloseSelected = { vm.onCloseSelectedFromLimitDialog(it) },
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacesScreenPreview() {
    val state = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(
            infos = emptyList(), // No workspaces
            portraitPanelMode = WorkspacePanelMode.AUTO,
            landscapePanelMode = WorkspacePanelMode.AUTO,
        ),
        focusedWorkspace = null,
        selectedWorkspaces = emptyMap(), // No selected workspaces
        isUpgraded = true,
        swipeGesturesEnabled = true,
    )

    WorkspacesScreenPreviewContent(
        state = state,
    )
}

@Composable
private fun WorkspacesScreenPreviewContent(
    state: WorkspacesViewModel.State,
) {
    WorkspaceScreen(
        state = state,
        bannerStates = emptyMap(),
        managerDialogStates = emptyMap(),
        onScreenAction = {},
        onHideMotd = {},
        onDismissMotd = {},
        onMotdLinkClick = {},
    )
}
