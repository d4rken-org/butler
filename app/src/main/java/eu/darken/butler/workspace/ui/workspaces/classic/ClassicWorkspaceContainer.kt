package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.workspaces.WorkspaceMapper
import eu.darken.butler.workspace.ui.workspaces.WorkspaceViewModel
import eu.darken.butler.workspace.ui.workspaces.empty.EmptyWorkspaceContent

private val TAG = logTag("Workspace", "Container", "Classic")

@Composable
internal fun ClassicWorkspaceContainer(
    state: WorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    onTabAction: (WorkspaceAction) -> Unit,
    onUpgradeButler: () -> Unit,
    onContent: @Composable (Workspace.Info?) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { state.all.size })

    // Sync pager with selected tab
    LaunchedEffect(state.selected, state.all) {
        val selectedId = state.selected ?: return@LaunchedEffect
        val selectedIndex = state.all.indexOfFirst { it.id == selectedId }
        log(TAG) { "Syncing pager with selected tab: selectedId=$selectedId, selectedIndex=$selectedIndex, currentPage=${pagerState.currentPage}" }

        if (selectedIndex < 0) {
            log(TAG) { "Selected tab not found in tabs list yet - waiting for state consistency" }
            return@LaunchedEffect
        }

        if (selectedIndex >= state.all.size || selectedIndex == pagerState.currentPage) return@LaunchedEffect

        log(TAG) { "Animating pager to page $selectedIndex" }
        pagerState.animateScrollToPage(selectedIndex)
    }

    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    val isScrolling by remember { derivedStateOf { pagerState.isScrollInProgress } }

    // Sync selected tab with pager when user swipes
    LaunchedEffect(currentPage, isScrolling, state.all, state.selected) {
        if (isScrolling) return@LaunchedEffect

        log(TAG) { "Pager scroll completed at page: $currentPage" }
        if (currentPage < 0 || currentPage >= state.all.size) return@LaunchedEffect

        val currentTabId = state.all[currentPage].id
        log(TAG) { "Current tab ID: $currentTabId, selected: ${state.selected}" }

        val selectedTabExists = state.selected?.let { selectedId ->
            state.all.any { it.id == selectedId }
        } ?: false

        if (selectedTabExists && currentTabId != state.selected) {
            log(TAG) { "Selecting tab due to user swipe: $currentTabId" }
            onTabAction(WorkspaceAction.Select(currentTabId))
        } else if (!selectedTabExists) {
            log(TAG, Logging.Priority.WARN) { "Skipping tab selection - selected tab doesn't exist in tabs list yet" }
        }
    }

    Scaffold(
        modifier = Modifier.Companion.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (state.all.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.Companion
                    .fillMaxSize()
                    .padding(paddingValues),
                userScrollEnabled = state.swipeGesturesEnabled,
            ) { page ->
                onContent(state.all[page])
            }
        } else {
            onContent(null)
        }
    }
}