package eu.darken.butler.workspace.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AddCircle
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString

import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.ui.EditorWorkspacePageHost
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePageHost
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.searcher.ui.SearcherWorkspacePageHost
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.empty.EmptyWorkspaceContent
import eu.darken.butler.workspace.ui.template.TemplatesWorkspacePageHost

private val TAG = logTag("Workspace", "Screen")

@Composable
fun WorkspaceScreenHost(vm: WorkspaceViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Workspace state: $state" }

    state?.let { state ->
        WorkspaceScreen(
            state = state,
            onNavToSettings = { vm.navTo(AppNav.Main.Settings) },
            onTabAction = { vm.modifyTab(it) },
            onUpgradeButler = { vm.upgradeButler() },
            onNavToWorkspaceManager = { vm.navToWorkspaceManager() }
        )
    }
}

@Composable
fun WorkspaceScreen(
    state: WorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    onTabAction: (TabAction) -> Unit,
    onUpgradeButler: () -> Unit,
    onNavToWorkspaceManager: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { state.tabs.size })

    // Sync pager with selected tab
    LaunchedEffect(state.selected, state.tabs) {
        val selectedId = state.selected ?: return@LaunchedEffect
        val selectedIndex = state.tabs.indexOfFirst { it.id == selectedId }
        log(TAG) { "Syncing pager with selected tab: selectedId=$selectedId, selectedIndex=$selectedIndex, currentPage=${pagerState.currentPage}" }

        if (selectedIndex < 0) {
            log(TAG) { "Selected tab not found in tabs list yet - waiting for state consistency" }
            return@LaunchedEffect
        }

        if (selectedIndex >= state.tabs.size || selectedIndex == pagerState.currentPage) return@LaunchedEffect

        log(TAG) { "Animating pager to page $selectedIndex" }
        pagerState.animateScrollToPage(selectedIndex)
    }


    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    val isScrolling by remember { derivedStateOf { pagerState.isScrollInProgress } }

    // Sync selected tab with pager when user swipes
    LaunchedEffect(currentPage, isScrolling, state.tabs, state.selected) {
        if (isScrolling) return@LaunchedEffect

        log(TAG) { "Pager scroll completed at page: $currentPage" }
        if (currentPage < 0 || currentPage >= state.tabs.size) return@LaunchedEffect

        val currentTabId = state.tabs[currentPage].id
        log(TAG) { "Current tab ID: $currentTabId, selected: ${state.selected}" }

        // Only trigger tab selection if the currently selected tab actually exists in the tabs list
        // This prevents race conditions during tab creation where selected ID is set before tabs list is updated
        val selectedTabExists = state.selected?.let { selectedId ->
            state.tabs.any { it.id == selectedId }
        } ?: false

        if (selectedTabExists && currentTabId != state.selected) {
            log(TAG) { "Selecting tab due to user swipe: $currentTabId" }
            onTabAction(TabAction.Select(currentTabId))
        } else if (!selectedTabExists) {
            log(TAG, WARN) { "Skipping tab selection - selected tab doesn't exist in tabs list yet" }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.tabs.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) { page ->
                    TabContent(
                        modifier = Modifier.fillMaxSize(),
                        selected = state.tabs[page],
                        onTabAction = onTabAction,
                    )
                }
            } else {
                EmptyWorkspaceContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    onNavToSettings = onNavToSettings,
                    onTabAction = onTabAction,
                    isUpgraded = state.isUpgraded,
                    onUpgradeButler = onUpgradeButler,
                )
            }

            // Workspace add button
            if (state.tabs.isNotEmpty()) {
                WorkspaceButton(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .statusBarsPadding(),
                    onTabAction = onTabAction,
                    onNavToWorkspaceManager = onNavToWorkspaceManager,
                    isButtonActionsFlipped = state.isButtonActionsFlipped
                )
            }
        }
    }
}

@Composable
private fun WorkspaceButton(
    modifier: Modifier = Modifier,
    onTabAction: (TabAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
    isButtonActionsFlipped: Boolean,
) {
    val (normalAction, longAction) = if (isButtonActionsFlipped) {
        // Flipped mode: normal click adds workspace, long click opens manager
        { onTabAction(TabAction.Create()) } to { onNavToWorkspaceManager() }
    } else {
        // Normal mode: normal click opens manager, long click adds workspace
        { onNavToWorkspaceManager() } to { onTabAction(TabAction.Create()) }
    }

    val icon = if (isButtonActionsFlipped) {
        Icons.TwoTone.AddCircle
    } else {
        Icons.TwoTone.Workspaces
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier.combinedClickable(
            onClick = normalAction,
            onLongClick = longAction
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.workspace_tab_add_action),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun TabContent(
    modifier: Modifier = Modifier,
    selected: WorkspaceTab,
    onTabAction: (TabAction) -> Unit,
) {
    Box(modifier = modifier) {
        when (selected.type) {
            Workspace.Type.TEMPLATES -> TemplatesWorkspacePageHost(
                id = selected.id,
                onTabAction = onTabAction,
            )
            Workspace.Type.EXPLORER -> ExplorerWorkspacePageHost(id = selected.id)
            Workspace.Type.SEARCHER -> SearcherWorkspacePageHost(id = selected.id)
            Workspace.Type.EDITOR -> EditorWorkspacePageHost(id = selected.id)
        }
    }
}

@Preview2
@Composable
private fun EmptyWorkspaceScreenPreview() {
    PreviewWrapper {
        WorkspaceScreen(
            state = WorkspaceViewModel.State(
                tabs = emptyList(),
                selected = null,
                isUpgraded = false,
            ),
            onNavToSettings = {},
            onTabAction = {},
            onUpgradeButler = {},
            onNavToWorkspaceManager = {}
        )
    }
}

@Preview2
@Composable
private fun WorkspaceScreenPreview() {
    PreviewWrapper {
        val tabs = listOf(
            WorkspaceTab(
                type = Workspace.Type.TEMPLATES,
                id = Workspace.Id(),
                title = R.string.workspace_templates_tab_title.toCaString(),
            ),
            WorkspaceTab(
                type = Workspace.Type.EXPLORER,
                id = Workspace.Id(),
                title = caString { "Explorer" },
            ),
        )
        WorkspaceScreen(
            state = WorkspaceViewModel.State(
                tabs = tabs,
                selected = tabs.last().id,
                isUpgraded = false,
            ),
            onNavToSettings = {},
            onTabAction = {},
            onUpgradeButler = {},
            onNavToWorkspaceManager = {}
        )
    }
}

