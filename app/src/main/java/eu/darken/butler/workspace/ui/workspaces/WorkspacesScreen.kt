package eu.darken.butler.workspace.ui.workspaces

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.main.ui.motd.MotdCard
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.dialogs.WorkspaceManagerDialogState
import eu.darken.butler.workspace.ui.feedback.BannerState
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.rememberWindowSizeInfo
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.classic.ClassicWorkspaceContainer
import kotlin.uuid.Uuid

private val TAG = logTag("Workspace", "Screen")

@Composable
fun WorkspacesScreenHost(
    vm: WorkspacesViewModel = hiltViewModel(),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    val workspaceButtonState by workspaceButtonVm.state.collectAsState(initial = null)
    val managerDialogStates by vm.managerDialogStates.collectAsState(initial = emptyMap())
    val bannerStates by vm.bannerStates.collectAsState(initial = emptyMap())

    val state by waitForState(vm.state)

    state?.let { state ->
        WorkspaceScreen(
            workspaceButtonState = workspaceButtonState,
            workspaceActionHandler = workspaceButtonVm,
            state = state,
            bannerStates = bannerStates,
            managerDialogStates = managerDialogStates,
            onScreenAction = { vm.executeScreenAction(it) },
            onHideMotd = { vm.hideMotd(it) },
            onDismissMotd = { vm.dismissMotd(it) },
            onMotdLinkClick = { vm.openMotdLink(it) },
            onDismissBanner = { vm.dismissBanner(it) },
            onDismissManagerDialog = { vm.dismissManagerDialog(it) },
            onConfirmManagerDialog = { vm.confirmManagerDialog(it) },
        )
    }
}


@Composable
fun WorkspaceScreen(
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    state: WorkspacesViewModel.State,
    bannerStates: Map<Workspace.Id, BannerState> = emptyMap(),
    managerDialogStates: Map<Workspace.Id, WorkspaceManagerDialogState.Targeted>,
    onScreenAction: (WorkspaceScreenAction) -> Unit,
    onHideMotd: (Uuid) -> Unit = {},
    onDismissMotd: (Uuid) -> Unit = {},
    onMotdLinkClick: (String) -> Unit = {},
    onDismissBanner: (Workspace.Id) -> Unit = {},
    onDismissManagerDialog: (Workspace.Id) -> Unit = {},
    onConfirmManagerDialog: (WorkspaceManagerDialogState.Targeted) -> Unit = {},
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
            )
        } else {
            ClassicWorkspaceContainer(
                state = state,
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
