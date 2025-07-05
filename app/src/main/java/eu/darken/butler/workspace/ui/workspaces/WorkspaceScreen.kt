package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.settings
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.workspaces.empty.EmptyWorkspaceContent
import eu.darken.butler.workspace.ui.workspaces.adaptive.AdaptiveWorkspaceContainer
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.adaptive.PaneLayout
import eu.darken.butler.workspace.ui.workspaces.adaptive.PaneMode
import eu.darken.butler.workspace.ui.workspaces.adaptive.WindowSizeClass
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRail
import eu.darken.butler.workspace.ui.workspaces.adaptive.rememberWindowSizeInfo
import eu.darken.butler.workspace.ui.workspaces.classic.ClassicWorkspaceContainer
import kotlinx.coroutines.flow.Flow

private val TAG = logTag("Workspace", "Screen")

@Composable
fun WorkspaceScreenHost(vm: WorkspaceViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Workspace state: $state" }

    state?.let { state ->
        WorkspaceScreen(
            state = state,
            onNavToSettings = { vm.navTo(Nav.Main.settings()) },
            onTabAction = { vm.modifyTab(it) },
            onUpgradeButler = { vm.upgradeButler() },
            paneModeFlow = vm.workspaceSettings.paneMode.flow,
            onPaneModeChange = { mode ->
                vm.launch {
                    vm.workspaceSettings.paneMode.update { mode }
                }
            },
        )
    }
}


@Composable
fun WorkspaceScreen(
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
                        log(TAG) { "onPaneAssignment: workspaceId=$workspaceId, paneIndex=$paneIndex" }
                        log(TAG) { "Current selectedIds: ${state.selectedIds}" }

                        // Create new selection with the workspace at the specified pane index
                        val currentSelection = state.selectedIds.toMutableList()

                        // Check if this workspace is already assigned to this pane
                        if (paneIndex < currentSelection.size && currentSelection[paneIndex] == workspaceId) {
                            log(TAG) { "Workspace already in pane $paneIndex, ignoring" }
                            // Workspace is already in this pane, do nothing to prevent hang
                            return@WorkspaceNavigationRail
                        }

                        // Check if the workspace is already assigned to another pane
                        val existingPaneIndex = currentSelection.indexOf(workspaceId)
                        log(TAG) { "Existing pane index for workspace: $existingPaneIndex" }

                        // Ensure we have enough panes
                        while (currentSelection.size <= paneIndex) {
                            val newWorkspace = state.tabs.firstOrNull {
                                !currentSelection.contains(it.id)
                            }?.id
                            if (newWorkspace != null) {
                                currentSelection.add(newWorkspace)
                                log(TAG) { "Added new workspace to fill pane: $newWorkspace" }
                            } else {
                                log(TAG) { "No available workspace to fill pane" }
                                break
                            }
                        }

                        if (existingPaneIndex != -1 && existingPaneIndex != paneIndex && paneIndex < currentSelection.size) {
                            // Workspace is already in another pane - swap them
                            val targetWorkspaceId = currentSelection[paneIndex]
                            log(TAG) { "Swapping: $workspaceId (from pane $existingPaneIndex) with $targetWorkspaceId (in pane $paneIndex)" }
                            currentSelection[paneIndex] = workspaceId
                            currentSelection[existingPaneIndex] = targetWorkspaceId
                        } else {
                            // Normal assignment
                            if (paneIndex < currentSelection.size) {
                                log(TAG) { "Normal assignment: $workspaceId to pane $paneIndex" }
                                currentSelection[paneIndex] = workspaceId
                            }
                        }

                        log(TAG) { "New selection after assignment: $currentSelection" }

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
                            WorkspaceMapper(tab = tab)
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
        ClassicWorkspaceContainer(
            state = state,
            onNavToSettings = onNavToSettings,
            onTabAction = onTabAction,
            onUpgradeButler = onUpgradeButler,
        )
    }
}

