package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.WorkspaceOverlayContainer
import eu.darken.butler.workspace.ui.dialogs.WorkspaceManagerDialogState
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspaceMapper
import eu.darken.butler.workspace.ui.workspaces.WorkspaceScreenAction
import eu.darken.butler.workspace.ui.workspaces.WorkspaceSwitchIndicator
import eu.darken.butler.workspace.ui.workspaces.WorkspacesViewModel
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo

private val TAG = logTag("Workspace", "Container", "Classic")

@Composable
internal fun ClassicWorkspaceContainer(
    design: WorkspaceDesign = WorkspaceDesign(),
    state: WorkspacesViewModel.State,
    onWorkspaceScreenAction: (WorkspaceScreenAction) -> Unit,
    workspaceActionHandler: WorkspaceActionHandler?,
    managerDialogStates: Map<Workspace.Id, WorkspaceManagerDialogState.Targeted>,
    onDismissManagerDialog: (Workspace.Id) -> Unit,
    onConfirmManagerDialog: (WorkspaceManagerDialogState.Targeted) -> Unit,
    bannerStates: Map<Workspace.Id, eu.darken.butler.workspace.ui.feedback.BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
) {
    val effectivePageCount = if (state.onDemandWorkspaceCreation && state.swipeGesturesEnabled) {
        state.all.size + 1
    } else {
        state.all.size
    }
    val pagerState = rememberPagerState(pageCount = { effectivePageCount })

    // Custom fling behavior requiring ~50% drag before committing to page change
    // snapPositionalThreshold: fraction of page that must be scrolled before switching (for low velocity flings)
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = 0.5f,
    )

    var isCreatingWorkspace by remember { mutableStateOf(false) }
    var previousPage by remember { mutableStateOf<Int?>(null) }
    var isAnimatingProgrammatically by remember { mutableStateOf(false) }

    // Track workspace switches for position indicator
    var workspaceSwitchTrigger by remember { mutableStateOf<Workspace.Id?>(null) }

    LaunchedEffect(state.focused) {
        val currentId = state.focused
        if (currentId != null) {
            workspaceSwitchTrigger = currentId
        }
    }

    // Sync pager with selected tab
    LaunchedEffect(state.focused, state.all) {
        val selectedId = state.focused ?: return@LaunchedEffect
        val selectedIndex = state.all.indexOfFirst { it.id == selectedId }
        log(
            TAG,
            VERBOSE
        ) { "Syncing pager with selected tab: selectedId=$selectedId, selectedIndex=$selectedIndex, currentPage=${pagerState.currentPage}" }

        if (selectedIndex < 0) {
            log(TAG, VERBOSE) { "Selected tab not found in tabs list yet - waiting for state consistency" }
            return@LaunchedEffect
        }

        if (selectedIndex >= state.all.size || selectedIndex == pagerState.currentPage) return@LaunchedEffect

        log(TAG, VERBOSE) { "Animating pager to page $selectedIndex" }
        isAnimatingProgrammatically = true
        pagerState.animateScrollToPage(selectedIndex)
        isAnimatingProgrammatically = false
    }

    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    val isScrolling by remember { derivedStateOf { pagerState.isScrollInProgress } }

    // Sync selected tab with pager when user swipes
    LaunchedEffect(currentPage, isScrolling, state.all) {
        if (isScrolling || isAnimatingProgrammatically) return@LaunchedEffect

        log(TAG, VERBOSE) { "Pager scroll completed at page: $currentPage" }

        // Check if we're on the extra page (beyond all workspaces)
        // Only trigger if transitioning from a valid page (not on initial render)
        val isOnPlaceholderPage = currentPage >= state.all.size
        val isTransitioningFromValidPage = previousPage != null && previousPage!! < state.all.size

        if (isOnPlaceholderPage && state.onDemandWorkspaceCreation && !isCreatingWorkspace && isTransitioningFromValidPage) {
            log(TAG, INFO) { "User swiped from page $previousPage to placeholder page $currentPage, creating workspace on-demand" }
            isCreatingWorkspace = true
            onWorkspaceScreenAction(WorkspaceScreenAction.CreateOnDemand)
            previousPage = currentPage
            return@LaunchedEffect
        }

        if (currentPage < 0 || currentPage >= state.all.size) {
            previousPage = currentPage
            return@LaunchedEffect
        }

        val currentTabId = state.all[currentPage].id
        log(TAG, VERBOSE) { "Current tab ID: $currentTabId, focused: ${state.focused}" }

        val focusedTabExists = state.focused?.let { focusedId ->
            state.all.any { it.id == focusedId }
        } ?: false

        if (focusedTabExists && currentTabId != state.focused) {
            log(TAG, VERBOSE) { "Selecting tab due to user swipe: $currentTabId" }
            onWorkspaceScreenAction(WorkspaceScreenAction.Select(currentTabId))
        } else if (!focusedTabExists) {
            log(TAG, WARN) { "Skipping tab selection - focused tab doesn't exist in tabs list yet" }
        }

        previousPage = currentPage
    }

    // Reset creation flag when workspace count increases
    LaunchedEffect(state.all.size) {
        if (state.all.isNotEmpty()) {
            isCreatingWorkspace = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            if (state.all.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    flingBehavior = flingBehavior,
                    userScrollEnabled = state.swipeGesturesEnabled,
                ) { page ->
                    val paneInfo = state.all.getOrNull(page)?.asPaneInfo()
                    val isPlaceholderPage = page >= state.all.size

                    if (paneInfo == null) {
                        CreatingWorkspacePlaceholder(isCreating = isPlaceholderPage && isCreatingWorkspace)
                    } else {
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
                            )
                        }
                    }
                }
            } else {
                EmptyClassicWorkspaceContent(
                    modifier = Modifier.padding(paddingValues),
                    isUpgraded = state.isUpgraded,
                    workspaceActionHandler = workspaceActionHandler,
                )
            }
        }

        // Position indicator overlay
        val currentWorkspace = state.current
        val switchedToWorkspace = workspaceSwitchTrigger
        if (switchedToWorkspace != null && currentWorkspace != null) {
            val context = LocalContext.current
            val position = state.tabWorkspaces.indexOfFirst { it.id == state.focused } + 1
            val total = state.tabWorkspaces.size
            val workspaceName = currentWorkspace.title.get(context)

            if (position > 0 && total > 1) {
                WorkspaceSwitchIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp),
                    position = position,
                    totalWorkspaces = total,
                    workspaceName = workspaceName,
                    workspaceId = switchedToWorkspace,
                )
            }
        }
    }
}