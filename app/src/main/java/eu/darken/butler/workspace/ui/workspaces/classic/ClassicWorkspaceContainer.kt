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
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.WorkspaceOverlayContainer
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspaceMapper
import eu.darken.butler.workspace.ui.workspaces.WorkspaceScreenAction
import eu.darken.butler.workspace.ui.workspaces.WorkspaceSwitchIndicator
import eu.darken.butler.workspace.ui.workspaces.WorkspacesViewModel
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo
import kotlinx.coroutines.delay

private val TAG = logTag("Workspace", "Container", "Classic")

@Composable
internal fun ClassicWorkspaceContainer(
    design: WorkspaceDesign = WorkspaceDesign(),
    state: WorkspacesViewModel.State,
    managerDialogs: List<ManagerDialog> = emptyList(),
    onWorkspaceScreenAction: (WorkspaceScreenAction) -> Unit,
    workspaceActionHandler: WorkspaceActionHandler?,
    managerDialogStates: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    onDismissManagerDialog: (Workspace.Id) -> Unit,
    onConfirmManagerDialog: (ManagerDialog.WorkspaceTargeted) -> Unit,
    bannerStates: Map<Workspace.Id, eu.darken.butler.workspace.ui.feedback.BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
) {
    val effectivePageCount = if (state.onDemandWorkspaceCreation && state.swipeGesturesEnabled) {
        state.tabWorkspaces.size + 1
    } else {
        state.tabWorkspaces.size
    }
    val pagerState = rememberPagerState(pageCount = { effectivePageCount })

    // Custom fling behavior requiring ~50% drag before committing to page change
    // snapPositionalThreshold: fraction of page that must be scrolled before switching (for low velocity flings)
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = 0.5f,
    )

    // State machine for placeholder workspace creation
    var creationState by remember { mutableStateOf<PlaceholderCreationState>(PlaceholderCreationState.Idle) }

    var isAnimatingProgrammatically by remember { mutableStateOf(false) }

    // Track last synced focus to detect new focus changes that should skip animation
    var lastSyncedFocusId by remember { mutableStateOf<Workspace.Id?>(null) }

    // Track focus changes initiated by user swipe to prevent race condition
    // where Block A would re-animate after Block B dispatches Select
    var lastUserSwipeFocusId by remember { mutableStateOf<Workspace.Id?>(null) }

    // Sync pager with selected tab
    LaunchedEffect(state.focused, state.tabWorkspaces, state.isRestoring) {
        val selectedId = state.focused ?: return@LaunchedEffect

        // Skip animation if this focus change was initiated by user swipe
        // (pager is already at the correct page from the swipe gesture)
        if (selectedId == lastUserSwipeFocusId) {
            log(TAG, VERBOSE) { "Skipping pager sync - focus change was user-initiated swipe" }
            lastUserSwipeFocusId = null
            lastSyncedFocusId = selectedId
            return@LaunchedEffect
        }

        val selectedIndex = state.tabWorkspaces.indexOfFirst { it.id == selectedId }
        log(TAG, VERBOSE) {
            "Syncing pager with selected tab: selectedId=$selectedId, selectedIndex=$selectedIndex, currentPage=${pagerState.currentPage}"
        }

        if (selectedIndex < 0) {
            log(TAG, VERBOSE) { "Selected tab not found in tabs list yet - waiting for state consistency" }
            return@LaunchedEffect
        }

        if (selectedIndex >= state.tabWorkspaces.size || selectedIndex == pagerState.currentPage) {
            lastSyncedFocusId = selectedId
            return@LaunchedEffect
        }

        // First sync for a new focus should be instant (no animation), subsequent syncs animate
        val isFirstSyncForFocus = lastSyncedFocusId != selectedId
        val shouldSkipAnimation = state.isRestoring || isFirstSyncForFocus

        isAnimatingProgrammatically = true
        if (shouldSkipAnimation) {
            log(
                TAG,
                VERBOSE
            ) { "Jumping pager to page $selectedIndex (restoration=${state.isRestoring}, firstSync=$isFirstSyncForFocus)" }
            pagerState.scrollToPage(selectedIndex)
        } else {
            log(TAG, VERBOSE) { "Animating pager to page $selectedIndex" }
            pagerState.animateScrollToPage(selectedIndex)
        }
        lastSyncedFocusId = selectedId
        isAnimatingProgrammatically = false
    }

    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
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
        if (isScrolling || isAnimatingProgrammatically) return@LaunchedEffect

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

    // Sync selected tab with pager when user swipes to a valid workspace page
    LaunchedEffect(currentPage, isScrolling, state.tabWorkspaces) {
        if (isScrolling || isAnimatingProgrammatically) return@LaunchedEffect
        if (currentPage < 0 || currentPage >= state.tabWorkspaces.size) return@LaunchedEffect

        val currentTabId = state.tabWorkspaces[currentPage].id
        log(TAG, VERBOSE) { "Current tab ID: $currentTabId, focused: ${state.focused}" }

        val focusedTabExists = state.focused?.let { focusedId ->
            state.tabWorkspaces.any { it.id == focusedId }
        } ?: false

        if (focusedTabExists && currentTabId != state.focused) {
            log(TAG, VERBOSE) { "Selecting tab due to user swipe: $currentTabId" }
            lastUserSwipeFocusId = currentTabId
            onWorkspaceScreenAction(WorkspaceScreenAction.Select(currentTabId))
        } else if (!focusedTabExists) {
            log(TAG, WARN) { "Skipping tab selection - focused tab doesn't exist in tabs list yet" }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            if (state.tabWorkspaces.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    flingBehavior = flingBehavior,
                    userScrollEnabled = state.swipeGesturesEnabled,
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
                        CompositionLocalProvider(
                            LocalWorkspaceFocused provides (state.focused == paneInfo.id),
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
                                )
                            }
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