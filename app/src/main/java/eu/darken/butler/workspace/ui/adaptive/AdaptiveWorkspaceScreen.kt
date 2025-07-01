package eu.darken.butler.workspace.ui.adaptive

import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.ui.EditorWorkspacePageHost
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePageHost
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePageHost
import eu.darken.butler.templates.ui.TemplatesWorkspacePageHost
import eu.darken.butler.templates.ui.WorkspaceTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.WorkspaceViewModel
import eu.darken.butler.workspace.ui.empty.EmptyWorkspaceContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val TAG = logTag("Workspace", "Adaptive", "Screen")

@Composable
fun AdaptiveWorkspaceScreen(
    state: WorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    onTabAction: (WorkspaceAction) -> Unit,
    onUpgradeButler: () -> Unit,
    paneModeFlow: Flow<String>,
    onPaneModeChange: (String) -> Unit,
) {
    val windowSizeInfo = rememberWindowSizeInfo()
    var paneModeOverride by remember { mutableStateOf<PaneMode?>(null) }
    var showPaneNumbers by remember { mutableStateOf(false) }

    val stateId = remember { System.currentTimeMillis() }
    var dividerPositions by rememberSaveable {
        log(TAG) { "Creating/Restoring divider positions state with stateId: $stateId" }
        mutableStateOf(DividerPositions())
    }

    log(TAG) { "AdaptiveWorkspaceScreen($stateId) recomposing - dividerPositions: $dividerPositions" }

    // Collect pane mode from settings
    LaunchedEffect(paneModeFlow) {
        paneModeFlow.collect { mode ->
            paneModeOverride = when (mode) {
                "AUTO" -> null
                "SINGLE" -> PaneMode.SINGLE
                "DUAL" -> PaneMode.DUAL
                "TRIPLE" -> PaneMode.TRIPLE
                else -> null
            }
        }
    }

    val effectivePaneMode = paneModeOverride ?: when (windowSizeInfo.recommendedPaneCount) {
        1 -> PaneMode.SINGLE
        2 -> PaneMode.DUAL
        3 -> PaneMode.TRIPLE
        else -> PaneMode.AUTO
    }

    val effectivePaneLayout = when (effectivePaneMode) {
        PaneMode.AUTO -> windowSizeInfo.recommendedPaneLayout
        PaneMode.SINGLE -> PaneLayout.SINGLE
        PaneMode.DUAL -> if (windowSizeInfo.widthDp > windowSizeInfo.heightDp) {
            PaneLayout.DUAL_VERTICAL
        } else {
            PaneLayout.DUAL_HORIZONTAL
        }
        PaneMode.TRIPLE -> PaneLayout.TRIPLE_MAIN_LEFT
    }

    // Auto-select workspaces for multi-pane mode if needed
    LaunchedEffect(effectivePaneMode, state.tabs, state.selectedIds) {
        val requiredPanes = when (effectivePaneMode) {
            PaneMode.SINGLE -> 1
            PaneMode.DUAL -> 2
            PaneMode.TRIPLE -> 3
            PaneMode.AUTO -> windowSizeInfo.recommendedPaneCount
        }

        if (state.selectedIds.size < requiredPanes && state.tabs.size >= requiredPanes) {
            val newSelectedIds = state.tabs.take(requiredPanes).map { it.id }
            onTabAction(WorkspaceAction.SelectMultiple(newSelectedIds))
        }
    }

    val useAdaptiveLayout =
        windowSizeInfo.widthSizeClass != WindowSizeClass.COMPACT || effectivePaneMode != PaneMode.SINGLE

    if (useAdaptiveLayout) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                WorkspaceNavigationRail(
                    tabs = state.tabs,
                    selectedIds = state.selectedIds,
                    focusedId = state.focusedId,
                    paneMode = effectivePaneMode,
                    onPaneModeChange = { mode ->
                        paneModeOverride = mode
                        val modeString = when (mode) {
                            PaneMode.AUTO -> "AUTO"
                            PaneMode.SINGLE -> "SINGLE"
                            PaneMode.DUAL -> "DUAL"
                            PaneMode.TRIPLE -> "TRIPLE"
                        }
                        onPaneModeChange(modeString)
                    },
                    onTabAction = onTabAction,
                    onPaneAssignment = { workspaceId, paneIndex ->
                        // Create new selection with the workspace at the specified pane index
                        val currentSelection = state.selectedIds.toMutableList()
                        while (currentSelection.size <= paneIndex) {
                            currentSelection.add(state.tabs.firstOrNull {
                                !currentSelection.contains(it.id)
                            }?.id ?: continue)
                        }
                        if (paneIndex < currentSelection.size) {
                            currentSelection[paneIndex] = workspaceId
                        }

                        // Remove duplicates and check if we need to reduce pane mode
                        val uniqueSelection = currentSelection.distinct()
                        if (uniqueSelection.size < currentSelection.size) {
                            // We have duplicates, adjust pane mode accordingly
                            when (uniqueSelection.size) {
                                1 -> {
                                    paneModeOverride = PaneMode.SINGLE
                                    onTabAction(WorkspaceAction.SelectMultiple(uniqueSelection))
                                }
                                2 -> {
                                    paneModeOverride = PaneMode.DUAL
                                    onTabAction(WorkspaceAction.SelectMultiple(uniqueSelection))
                                }
                                else -> {
                                    onTabAction(WorkspaceAction.SelectMultiple(uniqueSelection))
                                }
                            }
                        } else {
                            onTabAction(WorkspaceAction.SelectMultiple(currentSelection))
                        }
                        showPaneNumbers = false
                    },
                    showPaneNumbers = showPaneNumbers,
                )

                if (state.tabs.isNotEmpty()) {
                    AdaptiveWorkspaceContainer(
                        modifier = Modifier.weight(1f),
                        selectedTabs = state.selectedTabs,
                        focusedTabId = state.focusedId,
                        paneLayout = effectivePaneLayout,
                        dividerPositions = dividerPositions,
                        onDividerPositionsChange = { newPositions ->
                            log(TAG) { "onDividerPositionsChange called - old: $dividerPositions, new: $newPositions" }
                            dividerPositions = newPositions
                        },
                        onTabFocus = { id ->
                            onTabAction(WorkspaceAction.Focus(id))
                        },
                        showPaneNumbers = showPaneNumbers,
                        tabContent = { tab ->
                            TabContent(tab = tab)
                        }
                    )
                } else {
                    EmptyWorkspaceContent(
                        modifier = Modifier.weight(1f),
                        onNavToSettings = onNavToSettings,
                        onTabAction = onTabAction,
                        isUpgraded = state.isUpgraded,
                        onUpgradeButler = onUpgradeButler,
                    )
                }
            }
        }
    } else {
        // Fallback to original HorizontalPager for compact screens
        ClassicWorkspaceScreen(
            state = state,
            onNavToSettings = onNavToSettings,
            onTabAction = onTabAction,
            onUpgradeButler = onUpgradeButler,
        )
    }
}

@Composable
private fun ClassicWorkspaceScreen(
    state: WorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    onTabAction: (WorkspaceAction) -> Unit,
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

        val selectedTabExists = state.selected?.let { selectedId ->
            state.tabs.any { it.id == selectedId }
        } ?: false

        if (selectedTabExists && currentTabId != state.selected) {
            log(TAG) { "Selecting tab due to user swipe: $currentTabId" }
            onTabAction(WorkspaceAction.Select(currentTabId))
        } else if (!selectedTabExists) {
            log(TAG, WARN) { "Skipping tab selection - selected tab doesn't exist in tabs list yet" }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (state.tabs.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                userScrollEnabled = state.swipeGesturesEnabled,
            ) { page ->
                TabContent(tab = state.tabs[page])
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
    }
}

@Composable
private fun TabContent(
    tab: WorkspaceTab,
) {
    when (tab.type) {
        Workspace.Type.TEMPLATES -> TemplatesWorkspacePageHost(
            id = tab.id,
        )
        Workspace.Type.EXPLORER -> ExplorerWorkspacePageHost(
            id = tab.id,
        )
        Workspace.Type.SEARCHER -> SearcherWorkspacePageHost(
            id = tab.id,
        )
        Workspace.Type.EDITOR -> EditorWorkspacePageHost(
            id = tab.id,
        )
    }
}