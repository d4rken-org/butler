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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspacePane
import eu.darken.butler.workspace.ui.workspaces.WorkspaceScreenAction
import eu.darken.butler.workspace.ui.workspaces.WorkspaceSwitchIndicator
import eu.darken.butler.workspace.ui.workspaces.WorkspacesViewModel
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo

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
    isFirstTabTourTarget: Boolean = false,
    /** Scrolls the create card into view before the tour's step is published. */
    firstTabTourRequester: BringIntoViewRequester? = null,
    onShareError: (Workspace.Id, Throwable) -> Unit,
) {
    val workspaceActionHandler = LocalWorkspaceButtonProvider.current
    val effectivePageCount = if (state.onDemandWorkspaceCreation && state.swipeGesturesEnabled) {
        state.tabWorkspaces.size + 1
    } else {
        state.tabWorkspaces.size
    }
    val pagerState = rememberPagerState(pageCount = { effectivePageCount })

    // snapPositionalThreshold: fraction of page that must be scrolled before switching (for low velocity flings)
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = 0.5f,
    )

    // Stable list of workspace IDs — does NOT re-identity on Workspace.Info field
    // changes (operationCount, attentionCount, etc.), so the coordinator below
    // doesn't trigger spurious pager scrolls when an unrelated workspace updates.
    val tabIds = state.tabWorkspaces.map { it.id }

    rememberPagerFocusCoordinator(
        pagerState = pagerState,
        tabIds = tabIds,
        focused = state.focused,
        isRestoring = state.isRestoring,
        isOverlayVisible = isOverlayVisible,
        onSettled = { settledId ->
            onWorkspaceScreenAction(WorkspaceScreenAction.Select(settledId))
        },
    )

    val hasBlockingDialog = managerDialogs.any { it.isBlocking }

    val creationController = rememberPlaceholderCreationController(
        pagerState = pagerState,
        tabIds = tabIds,
        onDemandEnabled = state.onDemandWorkspaceCreation,
        isInteractionBlocked = isOverlayVisible || state.fullScreenModalWorkspace != null,
        hasBlockingDialog = hasBlockingDialog,
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
                val paneInfo = state.tabWorkspaces.getOrNull(page)?.asPaneInfo()
                val isPlaceholderPage = page >= state.tabWorkspaces.size

                if (paneInfo == null) {
                    CreatingWorkspacePlaceholder(
                        isCreating = isPlaceholderPage && creationController.isCreating,
                        onClick = { creationController.onPlaceholderClick() },
                    )
                } else {
                    // When overlay is visible, no workspace should be considered focused
                    val isFocused = state.focused == paneInfo.id && !isOverlayVisible
                    WorkspacePane(
                        info = paneInfo,
                        design = design,
                        paneFocused = isFocused,
                        activeWorkspaceId = paneInfo.id.takeIf { isFocused },
                        onRequestPaneFocus = {
                            onWorkspaceScreenAction(WorkspaceScreenAction.Select(paneInfo.id))
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

        // Position indicator overlay
        val currentWorkspace = state.current
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