package eu.darken.butler.workspace.ui.workspaces

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import eu.darken.butler.common.compose.systemBarsWithOptionalCutout
import eu.darken.butler.common.compose.tour.LocalGuidedTourController
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.main.ui.motd.MotdCard
import eu.darken.butler.main.ui.review.ReviewCard
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.LocalWorkspaceTitles
import eu.darken.butler.workspace.ui.tabLabel
import eu.darken.butler.workspace.ui.LocalWorkspacePagerVisibility
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceFeedback
import eu.darken.butler.workspace.ui.dialogs.ClearSessionConfirmationDialog
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.ManagerDialogAction
import eu.darken.butler.workspace.ui.dialogs.WorkspaceCloseConfirmationDialog
import eu.darken.butler.workspace.ui.dialogs.WorkspaceLimitDialog
import eu.darken.butler.workspace.ui.dialogs.WorkspaceRenameDialog
import eu.darken.butler.workspace.ui.error.ErrorShareConsentDialog
import eu.darken.butler.workspace.ui.feedback.BannerState
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerScreen
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerGridDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import eu.darken.butler.workspace.ui.manager.tour.WorkspaceManagerTour
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarScope
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.LocalWorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.manager.rememberWindowSizeInfo
import eu.darken.butler.workspace.ui.scroll.LocalWorkspaceScrollPositions
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import eu.darken.butler.workspace.ui.workspaces.classic.ClassicWorkspaceContainer
import eu.darken.butler.workspace.ui.workspaces.tour.FirstTabTour
import kotlin.uuid.Uuid

@Composable
fun WorkspaceScreen(
    state: WorkspacesViewModel.State,
    bannerStates: Map<Workspace.Id, BannerState> = emptyMap(),
    managerDialogStates: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    managerDialogs: List<ManagerDialog> = emptyList(),
    /** The dialog the full-screen modal window hosts, which no pane may compose. */
    modalDialogState: ManagerDialog.WorkspaceTargeted? = null,
    isOverlayVisible: Boolean = false,
    reviewActivity: Activity? = null,
    onScreenAction: (WorkspaceScreenAction) -> Unit,
    onHideMotd: (Uuid) -> Unit = {},
    onDismissMotd: (Uuid) -> Unit = {},
    onMotdLinkClick: (String) -> Unit = {},
    onReviewDismiss: () -> Unit = {},
    onReviewNow: (Activity) -> Unit = {},
    onDismissBanner: (Workspace.Id) -> Unit = {},
    onShareError: (Workspace.Id, Throwable) -> Unit = { _, _ -> },
    design: WorkspaceDesign = rememberWorkspaceDesign(state),
) {
    val workspaceActionHandler = LocalWorkspaceButtonProvider.current

    var showPaneNumbers by remember { mutableStateOf(false) }
    var showPaneOverlay by remember { mutableStateOf(false) }

    // Held as an id, not a captured Info: the automatic title can change, another surface can
    // rename it, or the tab can close while the dialog is open.
    var renameTargetId by remember { mutableStateOf<Workspace.Id?>(null) }

    var dividerPositions by rememberSaveable {
        mutableStateOf(DividerPositions())
    }

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
            managerDialog = modalDialogState,
            onScreenAction = onScreenAction,
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
 * The pane layout the window gets, from the orientation's panel mode setting and the window size.
 * Shared by the screen and its host: the host draws chrome outside every pane (the close-undo bar)
 * and has to know whether the navigation rail takes the start edge.
 */
@Composable
fun rememberWorkspaceDesign(state: WorkspacesViewModel.State): WorkspaceDesign {
    val windowSizeInfo = rememberWindowSizeInfo()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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

    return WorkspaceDesign(layout = effectivePaneLayout)
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
    val closedFeedback by vm.closedFeedback.collectAsState()
    val pageManagerState by vm.workspacePageManager.state.collectAsState()
    val managerState by managerVm.state.collectAsState(initial = null)
    val state by vm.state.collectAsState(initial = null)
    val pendingErrorShare by vm.pendingErrorShare.collectAsState()

    // Derive dialog states from unified registry. Every host is composed from here or from the
    // screen below it, so this is where the routing between them belongs.
    val tabOrder = state?.tabWorkspaces?.map { it.id }.orEmpty()
    val fullScreenModalId = state?.fullScreenModalWorkspace?.id
    val dialogRouting = remember(
        managerDialogs,
        pageManagerState.isManagerOverlayVisible,
        tabOrder,
        fullScreenModalId,
    ) {
        routeManagerDialogs(
            dialogs = managerDialogs,
            isManagerOverlayVisible = pageManagerState.isManagerOverlayVisible,
            tabOrder = tabOrder,
            fullScreenModalId = fullScreenModalId,
        )
    }
    val managerDialogStates = dialogRouting.paneHosted
    val managerCloseConfirmation = dialogRouting.managerHosted
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
    // Hoisted out of the grid so the tour's prepareTarget hooks can scroll an anchor into the
    // viewport before the step it belongs to is published.
    val managerGridState = rememberLazyGridState()
    val managerTourDefinition = remember(managerGridState) {
        WorkspaceManagerTour.definition(
            prepareAddTab = { managerGridState.scrollToItem(0) },
            prepareFirstCard = {
                managerGridState.scrollToItem(WorkspaceManagerGridDefaults.FIRST_WORKSPACE_CARD_INDEX)
            },
        )
    }
    // Gated on the manager's own state, not just the overlay flag: a restored session makes the
    // overlay visible before that state arrives, and the manager - with the tour's anchors - is
    // composed no earlier than the state is non-null. A tour started in that window finds no
    // anchor, grace-skips every step, and suppresses itself for the rest of the process.
    // The grid also has to hold a card: the manager is reachable with zero tabs, and there the two
    // card steps have nothing to anchor on while the add-tab step still renders - enough for
    // completion to be persisted, burning a tour whose gestures were never shown.
    val managerTourReady = pageManagerState.isManagerOverlayVisible &&
        managerState?.filteredWorkspaces?.isNotEmpty() == true
    LaunchedEffect(managerTourReady) {
        if (!managerTourReady) return@LaunchedEffect
        // No attempted-flag: this key already runs the body once per open, and tryStart itself
        // refuses a tour that is completed, dismissed, or skipped this process.
        tourController.tryStart(managerTourDefinition)
    }

    // Selection mode is a state inside the overlay, so back leaves it first; dismissing the manager
    // outright would drop a selection the user is still assembling.
    ManagerOverlayBackHandler(
        isOverlayVisible = pageManagerState.isManagerOverlayVisible,
        onDismiss = {
            // Asks the ViewModel rather than the collected state: a long-press reaches selectionFlow
            // a frame before managerState reflects it, and back in that window must not dismiss the
            // whole manager out from under a selection that has already started.
            if (!managerVm.clearSelectionIfActive()) {
                vm.workspacePageManager.hideManagerOverlay()
            }
        },
    )

    val workspaceTitles = state?.let { current ->
        remember(current.all, context) {
            current.all.associate { it.id to it.tabLabel.get(context) }
        }
    }

    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides workspaceButtonVm,
        LocalWorkspacePageHosts provides vm.pageHosts,
        LocalWorkspaceScrollPositions provides vm.scrollPositions,
        LocalWorkspaceBarCollapseStates provides vm.barCollapseStates,
        LocalWorkspacePagerVisibility provides vm.pagerVisibility,
        LocalWorkspaceTitles provides workspaceTitles,
    ) {
        val design = state?.let { rememberWorkspaceDesign(it) }
        state?.let { state ->
            WorkspaceScreen(
                state = state,
                design = design ?: WorkspaceDesign(),
                bannerStates = bannerStates,
                managerDialogStates = managerDialogStates,
                managerDialogs = managerDialogs,
                modalDialogState = dialogRouting.modalHosted,
                isOverlayVisible = pageManagerState.isManagerOverlayVisible,
                reviewActivity = activity,
                onScreenAction = { vm.executeScreenAction(it) },
                onHideMotd = { vm.hideMotd(it) },
                onDismissMotd = { vm.dismissMotd(it) },
                onMotdLinkClick = { vm.openMotdLink(it) },
                onReviewDismiss = { vm.reviewDismiss() },
                onReviewNow = { vm.reviewNow(it) },
                onDismissBanner = { vm.dismissBanner(it) },
                onShareError = { workspaceId, error -> vm.shareWorkspaceError(workspaceId, error) },
            )
        }

        // Manager overlay
        if (pageManagerState.isManagerOverlayVisible) {
            managerState?.let { currentManagerState ->
                WorkspaceManagerScreen(
                    state = currentManagerState,
                    lazyGridState = managerGridState,
                    closeConfirmation = managerCloseConfirmation,
                    onCloseConfirmationResolve = { confirmed ->
                        managerCloseConfirmation?.let {
                            vm.executeScreenAction(
                                WorkspaceScreenAction.HandleDialog(
                                    ManagerDialogAction.Resolve(it.id, confirmed = confirmed),
                                ),
                            )
                        }
                    },
                    onCloseConfirmationGoTo = {
                        managerCloseConfirmation?.let {
                            vm.executeScreenAction(
                                WorkspaceScreenAction.HandleDialog(
                                    ManagerDialogAction.CancelAndGoToWorkspace(
                                        confirmationId = it.id,
                                        workspaceId = it.closingWorkspaceId,
                                        // No pane hosts this one, so the pane the user last worked
                                        // in is where the tab should land.
                                        sourceWorkspaceId = pageManagerState.focusedWorkspaceId,
                                        hideManagerOverlay = true,
                                    ),
                                ),
                            )
                        }
                    },
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
                    onStartSelection = managerVm::startSelection,
                    onToggleSelection = managerVm::toggleSelection,
                    onSelectAllTabs = managerVm::selectAllTabs,
                    onClearSelection = managerVm::clearSelection,
                    onCloseSelectedWorkspaces = managerVm::closeSelectedWorkspaces,
                    onPauseSelectedWorkspaces = managerVm::pauseSelectedWorkspaces,
                    onRenameWorkspace = managerVm::renameWorkspace,
                    onTabsClick = managerVm::selectAllTabs,
                    onOperationsFilterClick = managerVm::toggleOperationsFilter,
                    onAttentionFilterClick = managerVm::toggleAttentionFilter,
                )
            }
        }

        // After the manager overlay on purpose: the manager stays open when a card closes a tab,
        // and a bar drawn inside the workspace surface would sit underneath it - invisible for the
        // most likely first use of this feature. The dialogs below still cover it, which is right:
        // anything asking the user a question outranks an offer they can also just ignore.
        closedFeedback?.let { feedback ->
            // The rail sits inside the window's start inset and is 80dp beyond it. Only while the
            // manager is down: the overlay covers the rail, and a bar offset over a full-width grid
            // would look misplaced.
            val railVisible = design?.isSingle == false && !pageManagerState.isManagerOverlayVisible
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(systemBarsWithOptionalCutout().only(WindowInsetsSides.Horizontal))
                    .padding(start = if (railVisible) WorkspaceNavigationRailDefaults.Width else 0.dp),
            ) {
                FloatingBarStack(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    position = BarPosition.BOTTOM,
                ) {
                    WorkspaceClosedUndoBar(
                        feedback = feedback,
                        onUndo = { vm.undoClose() },
                        onDismiss = { vm.dismissClosedFeedback(feedback.closeToken) },
                    )
                }
            }
        }

        if (showClearSessionConfirmation) {
            ClearSessionConfirmationDialog(
                onDismiss = { vm.dismissClearSessionConfirmation() },
                onConfirm = { vm.confirmClearSession() },
            )
        }

        // Composed here rather than in a pane, which is what makes it a window dialog: outside every
        // PaneLayerHost the adaptive renderer resolves to the window one, so the question covers the
        // screen instead of scrimming a pane that belongs to a tab the close leaves alone.
        dialogRouting.globalHosted?.let { dialog ->
            key(dialog.id) {
                WorkspaceCloseConfirmationDialog(
                    workspaceTitle = dialog.workspaceTitle,
                    hasUnsavedChanges = dialog.hasUnsavedChanges,
                    unsavedCount = dialog.unsavedCount,
                    onDismiss = {
                        vm.executeScreenAction(
                            WorkspaceScreenAction.HandleDialog(
                                ManagerDialogAction.Resolve(dialog.id, confirmed = false),
                            ),
                        )
                    },
                    onConfirm = {
                        vm.executeScreenAction(
                            WorkspaceScreenAction.HandleDialog(
                                ManagerDialogAction.Resolve(dialog.id, confirmed = true),
                            ),
                        )
                    },
                    // A tab whose ownership chain is broken cannot be brought on screen, so the
                    // jump is not offered rather than cancelling the close for nothing.
                    onGoToWorkspace = if (dialog.canGoToWorkspace) {
                        {
                            vm.executeScreenAction(
                                WorkspaceScreenAction.HandleDialog(
                                    ManagerDialogAction.CancelAndGoToWorkspace(
                                        confirmationId = dialog.id,
                                        workspaceId = dialog.closingWorkspaceId,
                                        sourceWorkspaceId = dialog.selectionSourceWorkspaceId,
                                        // This dialog can be raised while the manager is up, and
                                        // hiding one that is already down does nothing.
                                        hideManagerOverlay = true,
                                    ),
                                ),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        if (pendingErrorShare != null) {
            ErrorShareConsentDialog(
                onConfirm = { vm.confirmErrorShare() },
                onDismiss = { vm.dismissErrorShare() },
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

/**
 * The undo offer's bar declaration. Separate from its caller so a test drives the composition the
 * app runs, key and all, rather than a re-declaration of it.
 */
@Composable
internal fun FloatingBarScope.WorkspaceClosedUndoBar(
    feedback: ClosedWorkspaceFeedback,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingBar(key = "workspace-closed-undo") {
        // Start-aligned and capped: a card spanning a tablet-width window reads as a banner.
        Box(modifier = Modifier.fillMaxWidth()) {
            // A superseding entry can reach composition without an intervening null, which would
            // leave the swipe state parked at the previous entry's dismissed anchor - the new bar
            // arrives already swiped away and takes no further gesture.
            key(feedback.closeToken) {
                WorkspaceClosedFeedbackBar(
                    modifier = Modifier.widthIn(max = WorkspaceClosedUndoBarDefaults.MaxWidth),
                    feedback = feedback,
                    onUndo = onUndo,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

object WorkspaceClosedUndoBarDefaults {
    val MaxWidth = 600.dp
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
