package eu.darken.butler.workspace.ui.workspaces

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.main.ui.motd.MotdCard
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.WorkspaceLimitDialog
import eu.darken.butler.workspace.ui.feedback.BannerState
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerScreen
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import eu.darken.butler.workspace.ui.manager.rememberWindowSizeInfo
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.classic.ClassicWorkspaceContainer
import kotlin.uuid.Uuid
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.workspace.R as WorkspaceR

@Composable
fun WorkspaceScreen(
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    state: WorkspacesViewModel.State,
    bannerStates: Map<Workspace.Id, BannerState> = emptyMap(),
    managerDialogStates: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    managerDialogs: List<ManagerDialog> = emptyList(),
    onScreenAction: (WorkspaceScreenAction) -> Unit,
    onHideMotd: (Uuid) -> Unit = {},
    onDismissMotd: (Uuid) -> Unit = {},
    onMotdLinkClick: (String) -> Unit = {},
    onDismissBanner: (Workspace.Id) -> Unit = {},
    onDismissManagerDialog: (Workspace.Id) -> Unit = {},
    onConfirmManagerDialog: (ManagerDialog.WorkspaceTargeted) -> Unit = {},
) {
    val windowSizeInfo = rememberWindowSizeInfo()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var showPaneNumbers by remember { mutableStateOf(false) }
    var showPaneOverlay by remember { mutableStateOf(false) }

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

    Box(modifier = Modifier.fillMaxSize()) {
        // Main workspace content
        if (!design.isSingle) {
            AdaptiveWorkspaceLayout(
                design = design,
                workspaces = state.tabWorkspaces,
                selected = state.selected,
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
                workspaceButtonState = workspaceButtonState,
                workspaceActionHandler = workspaceActionHandler,
                onScreenAction = onScreenAction,
                managerDialogStates = managerDialogStates,
                onDismissManagerDialog = onDismissManagerDialog,
                onConfirmManagerDialog = onConfirmManagerDialog,
                bannerStates = bannerStates,
                onDismissBanner = onDismissBanner,
                paneLocalModals = state.paneLocalModals,
                isUpgraded = state.isUpgraded,
            )
        } else {
            ClassicWorkspaceContainer(
                state = state,
                managerDialogs = managerDialogs,
                onWorkspaceScreenAction = onScreenAction,
                workspaceActionHandler = workspaceActionHandler,
                managerDialogStates = managerDialogStates,
                onDismissManagerDialog = onDismissManagerDialog,
                onConfirmManagerDialog = onConfirmManagerDialog,
                bannerStates = bannerStates,
                onDismissBanner = onDismissBanner,
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    // Full-screen modal workspace overlay (pickers, settings dialogs, detail views on phones)
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
        )
    }
}

@Composable
fun WorkspacesScreenHost(
    vm: WorkspacesViewModel = hiltViewModel(),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
    managerVm: WorkspaceManagerViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    ErrorEventHandler(managerVm)
    NavigationEventHandler(vm, workspaceButtonVm, managerVm)

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val workspaceButtonState by workspaceButtonVm.state.collectAsState(initial = null)
    val bannerStates by vm.bannerStates.collectAsState(initial = emptyMap())
    val showClearSessionConfirmation by vm.showClearSessionConfirmation.collectAsState(initial = false)
    val managerDialogs by vm.managerDialogs.collectAsState()
    val pageManagerState by vm.workspacePageManager.state.collectAsState()
    val managerState by waitForState(managerVm.state)

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

    // Handle back button when manager overlay is visible
    BackHandler(enabled = pageManagerState.isManagerOverlayVisible) {
        vm.workspacePageManager.hideManagerOverlay()
    }

    val state by waitForState(vm.state)

    state?.let { state ->
        WorkspaceScreen(
            workspaceButtonState = workspaceButtonState,
            workspaceActionHandler = workspaceButtonVm,
            state = state,
            bannerStates = bannerStates,
            managerDialogStates = managerDialogStates,
            managerDialogs = managerDialogs,
            onScreenAction = { vm.executeScreenAction(it) },
            onHideMotd = { vm.hideMotd(it) },
            onDismissMotd = { vm.dismissMotd(it) },
            onMotdLinkClick = { vm.openMotdLink(it) },
            onDismissBanner = { vm.dismissBanner(it) },
            onDismissManagerDialog = { vm.dismissManagerDialog(it) },
            onConfirmManagerDialog = { vm.confirmManagerDialog(it) },
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
                onCreateWorkspace = managerVm::createWorkspace,
                onNavigateBack = managerVm::navigateBack,
                onDismissBadgeExplanation = managerVm::dismissBadgeExplanation,
                onDismissLongPressHint = managerVm::dismissLongPressHint,
                onCloseAllWorkspaces = managerVm::closeAllWorkspaces,
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
        WorkspaceLimitDialog(
            limit = dialogState.limit,
            onDismiss = { vm.dismissWorkspaceLimitDialog() },
            onUpgrade = { vm.onUpgradeFromLimitDialog() },
        )
    }
}


@Composable
private fun ClearSessionConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(WorkspaceR.string.workspace_session_restoration_error_confirm_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(WorkspaceR.string.workspace_session_restoration_error_confirm_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(WorkspaceR.string.workspace_session_restoration_error_clear_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Preview2
@Composable
private fun ClearSessionConfirmationDialogPreview() {
    PreviewWrapper {
        ClearSessionConfirmationDialog(
            onDismiss = {},
            onConfirm = {},
        )
    }
}

@Preview2
@Composable
private fun WorkspacesScreenPreview() {
    PreviewWrapper {
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

        val workspaceButtonState = WorkspaceButtonViewModel.State(
            workspaceCount = 0,
            operationsCount = 0,
            attentionCount = 0,
        )

        WorkspacesScreenPreviewContent(
            workspaceButtonState = workspaceButtonState,
            state = state,
        )
    }
}

@Composable
private fun WorkspacesScreenPreviewContent(
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    state: WorkspacesViewModel.State,
) {
    WorkspaceScreen(
        workspaceButtonState = workspaceButtonState,
        state = state,
        bannerStates = emptyMap(),
        managerDialogStates = emptyMap(),
        onScreenAction = {},
        onHideMotd = {},
        onDismissMotd = {},
        onMotdLinkClick = {},
    )
}
