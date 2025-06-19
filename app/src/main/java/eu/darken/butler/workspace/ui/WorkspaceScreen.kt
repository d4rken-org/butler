package eu.darken.butler.workspace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.twotone.AddCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.ui.EditorWorkspacePageHost
import eu.darken.butler.explorer.ui.ExplorerWorkspacePageHost
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.searcher.ui.SearcherWorkspacePageHost
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.empty.EmptyWorkspaceContent
import eu.darken.butler.workspace.ui.template.TemplatesWorkspacePageHost
import kotlinx.coroutines.launch

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
            onUpgradeButler = { vm.upgradeButler() }
        )
    }
}

@Composable
fun WorkspaceScreen(
    state: WorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    onTabAction: (TabAction) -> Unit,
    onUpgradeButler: () -> Unit,
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
        topBar = {
            TabBar(
                tabs = state.tabs,
                selectedTabId = state.selected,
                onTabAction = onTabAction,
            )
        }
    ) { paddingValues ->
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
                showUpgradePrompt = state.showUpgradePrompt,
                onUpgradeButler = onUpgradeButler,
            )
        }
    }
}

@Composable
private fun TabBar(
    tabs: List<WorkspaceTab>,
    selectedTabId: Workspace.Id?,
    onTabAction: (TabAction) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to selected tab
    LaunchedEffect(selectedTabId, tabs.size) {
        if (selectedTabId == null) return@LaunchedEffect
        val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }
        if (selectedIndex < 0) return@LaunchedEffect

        coroutineScope.launch {
            // Scroll to make the selected tab prominently visible, use a small offset to avoid edge positioning
            listState.animateScrollToItem(
                index = selectedIndex,
                scrollOffset = -50, // Small negative offset to show some padding
            )
            log(TAG) { "Auto-scrolled tab bar to selected tab at index $selectedIndex" }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(tabs) { tab ->
                TabItem(
                    tab = tab,
                    isSelected = tab.id == selectedTabId,
                    onSelect = { onTabAction(TabAction.Select(tab.id)) },
                    onClose = { onTabAction(TabAction.Close(tab.id)) },
                )
            }
        }
        if (tabs.isNotEmpty()) {
            IconButton(onClick = { onTabAction(TabAction.Create()) }) {
                Icon(
                    imageVector = Icons.TwoTone.AddCircle,
                    tint = MaterialTheme.colorScheme.primary,
                    contentDescription = stringResource(R.string.workspace_tab_add_action)
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: WorkspaceTab,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier.clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tab.title.asComposable(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(end = 6.dp)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(16.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.workspace_tab_close_action),
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.size(12.dp)
                )
            }
        }
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
                showUpgradePrompt = true,
            ),
            onNavToSettings = {},
            onTabAction = {},
            onUpgradeButler = {}
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
                title = R.string.workspace_templates_title.toCaString(),
            ),
            WorkspaceTab(
                type = Workspace.Type.EXPLORER,
                id = Workspace.Id(),
                title = R.string.explorer_title.toCaString(),
            ),
        )
        WorkspaceScreen(
            state = WorkspaceViewModel.State(
                tabs = tabs,
                selected = tabs.last().id,
                showUpgradePrompt = true,
            ),
            onNavToSettings = {},
            onTabAction = {},
            onUpgradeButler = {}
        )
    }
}

