package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.WorkspaceOverlayContainer
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspaceMapper
import eu.darken.butler.workspace.ui.workspaces.WorkspaceScreenAction
import eu.darken.butler.workspace.ui.workspaces.WorkspaceSwitchIndicator
import eu.darken.butler.workspace.ui.workspaces.WorkspacesViewModel
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo
import kotlinx.coroutines.delay

private val TAG = logTag("Workspace", "Container", "Classic")

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

    // State machine for placeholder workspace creation
    var creationState by remember { mutableStateOf<PlaceholderCreationState>(PlaceholderCreationState.Idle) }

    // Stable list of workspace IDs — does NOT re-identity on Workspace.Info field
    // changes (operationCount, attentionCount, etc.), so the coordinator below
    // doesn't trigger spurious pager scrolls when an unrelated workspace updates.
    val tabIds = state.tabWorkspaces.map { it.id }

    val coordinator = rememberPagerFocusCoordinator(
        pagerState = pagerState,
        tabIds = tabIds,
        focused = state.focused,
        isRestoring = state.isRestoring,
        isOverlayVisible = isOverlayVisible,
        onSettled = { settledId ->
            onWorkspaceScreenAction(WorkspaceScreenAction.Select(settledId))
        },
    )

    val isScrolling by remember { derivedStateOf { pagerState.isScrollInProgress } }

    val hasBlockingDialog = managerDialogs.any { it.isBlocking }
    val settledPage by remember { derivedStateOf { pagerState.settledPage } }
    val workspaceCount = state.tabWorkspaces.size

    // State machine transitions for placeholder creation
    LaunchedEffect(
        settledPage,
        isScrolling,
        workspaceCount,
        hasBlockingDialog,
        creationState,
    ) {
        if (isScrolling || coordinator.isAnimatingProgrammatically) return@LaunchedEffect

        // Don't run placeholder creation logic when EmptyClassicWorkspaceContent is shown
        // (it has its own explicit creation actions)
        if (workspaceCount == 0) {
            if (creationState != PlaceholderCreationState.Idle) {
                creationState = PlaceholderCreationState.Idle
            }
            return@LaunchedEffect
        }

        val isOnPlaceholder = settledPage >= workspaceCount

        val newState = when (creationState) {
            is PlaceholderCreationState.Idle -> {
                if (isOnPlaceholder && state.onDemandWorkspaceCreation) {
                    log(TAG, INFO) { "Placeholder page settled, transitioning to Visiting" }
                    PlaceholderCreationState.Visiting
                } else {
                    PlaceholderCreationState.Idle
                }
            }
            is PlaceholderCreationState.Visiting -> {
                when {
                    !isOnPlaceholder -> {
                        log(TAG, VERBOSE) { "Left placeholder page, resetting to Idle" }
                        PlaceholderCreationState.Idle
                    }
                    else -> PlaceholderCreationState.Visiting
                }
            }
            is PlaceholderCreationState.Triggered -> PlaceholderCreationState.Creating
            is PlaceholderCreationState.Creating -> {
                when {
                    !isOnPlaceholder -> {
                        log(TAG, VERBOSE) { "Left placeholder during creation, resetting to Idle" }
                        PlaceholderCreationState.Idle
                    }
                    hasBlockingDialog -> {
                        log(TAG, INFO) { "Blocking dialog shown (limit reached), transitioning to Failed" }
                        PlaceholderCreationState.Failed
                    }
                    else -> PlaceholderCreationState.Creating
                }
            }
            is PlaceholderCreationState.Failed -> {
                when {
                    !hasBlockingDialog && isOnPlaceholder -> {
                        log(TAG, INFO) { "Dialog dismissed but still on placeholder, transitioning to Blocked" }
                        PlaceholderCreationState.Blocked
                    }
                    !hasBlockingDialog -> {
                        log(TAG, VERBOSE) { "Dialog dismissed and left placeholder, resetting to Idle" }
                        PlaceholderCreationState.Idle
                    }
                    else -> PlaceholderCreationState.Failed
                }
            }
            is PlaceholderCreationState.Blocked -> {
                when {
                    !isOnPlaceholder -> {
                        log(TAG, VERBOSE) { "Left placeholder from Blocked state, resetting to Idle" }
                        PlaceholderCreationState.Idle
                    }
                    else -> PlaceholderCreationState.Blocked
                }
            }
        }

        if (newState != creationState) {
            creationState = newState
        }
    }

    // Auto-trigger creation after settling on placeholder (with brief delay)
    LaunchedEffect(creationState) {
        if (creationState is PlaceholderCreationState.Visiting) {
            delay(100) // Brief delay to confirm user intent
            if (creationState is PlaceholderCreationState.Visiting) {
                log(TAG, INFO) { "Auto-triggering workspace creation from placeholder" }
                creationState = PlaceholderCreationState.Triggered
                onWorkspaceScreenAction(WorkspaceScreenAction.CreateOnDemand)
            }
        }
    }

    // Reset to Idle when workspace count increases (creation succeeded)
    LaunchedEffect(workspaceCount) {
        if (creationState is PlaceholderCreationState.Creating && workspaceCount > 0) {
            log(TAG, INFO) { "Workspace count increased, creation succeeded" }
            creationState = PlaceholderCreationState.Idle
        }
    }

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
                    val isCreating = creationState is PlaceholderCreationState.Creating ||
                        creationState is PlaceholderCreationState.Triggered
                    CreatingWorkspacePlaceholder(
                        isCreating = isPlaceholderPage && isCreating,
                        onClick = {
                            if (creationState is PlaceholderCreationState.Visiting ||
                                creationState is PlaceholderCreationState.Idle ||
                                creationState is PlaceholderCreationState.Blocked
                            ) {
                                log(TAG, INFO) { "Manual click triggered workspace creation" }
                                creationState = PlaceholderCreationState.Triggered
                                onWorkspaceScreenAction(WorkspaceScreenAction.CreateOnDemand)
                            }
                        },
                    )
                } else {
                    // When overlay is visible, no workspace should be considered focused
                    val isFocused = state.focused == paneInfo.id && !isOverlayVisible
                    CompositionLocalProvider(
                        LocalWorkspaceFocused provides isFocused,
                        LocalWorkspaceFocusRequest provides {
                            onWorkspaceScreenAction(
                                WorkspaceScreenAction.Select(
                                    paneInfo.id
                                )
                            )
                        },
                    ) {
                        WorkspaceOverlayContainer(
                            workspaceId = paneInfo.id,
                            managerDialogStates = managerDialogStates,
                            onDismissManagerDialog = onDismissManagerDialog,
                            onConfirmManagerDialog = onConfirmManagerDialog,
                            bannerStates = bannerStates,
                            onDismissBanner = onDismissBanner,
                        ) {
                            WorkspaceMapper(
                                info = paneInfo,
                                design = design,
                                onShareError = { error ->
                                    onShareError(paneInfo.id, error)
                                },
                                onCloseWorkspace = {
                                    workspaceActionHandler?.executeWorkspaceAction(
                                        WorkspaceAction.Close(paneInfo.id)
                                    )
                                },
                            )
                        }
                    }
                }
            }
        } else {
            EmptyClassicWorkspaceContent(
                modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                isUpgraded = state.isUpgraded,
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