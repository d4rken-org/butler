package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.settings
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.WorkspacePanelMode
import eu.darken.butler.workspace.ui.WorkspaceScreenAction
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.rememberWindowSizeInfo
import eu.darken.butler.workspace.ui.workspaces.adaptive.AdaptiveWorkspaceContainer
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.adaptive.EmptyAdaptiveWorkspaceContent
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRail
import eu.darken.butler.workspace.ui.workspaces.classic.ClassicWorkspaceContainer

@Composable
fun WorkspacesScreenHost(
    vm: WorkspacesViewModel = hiltViewModel(),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    val workspaceButtonState by workspaceButtonVm.state.collectAsState(null)

    val state by waitForState(vm.state)
    log(vm.tag) { "Workspace state: $state" }

    state?.let { state ->
        WorkspaceScreen(
            workspaceButtonState = workspaceButtonState,
            onWorkspaceAction = workspaceButtonVm::onWorkspaceAction,
            onNavToWorkspaceManager = workspaceButtonVm::onNavToWorkspaceManager,

            state = state,
            onNavToSettings = { vm.navTo(Nav.Main.settings()) },
            onTabAction = { vm.modifyTab(it) },
            onScreenAction = { vm.executeScreenAction(it) },
            onUpgradeButler = { vm.upgradeButler() },
        )
    }
}


@Composable
fun WorkspaceScreen(
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
    state: WorkspacesViewModel.State,
    onNavToSettings: () -> Unit,
    onTabAction: (WorkspaceAction) -> Unit,
    onScreenAction: (WorkspaceScreenAction) -> Unit,
    onUpgradeButler: () -> Unit,
) {
    val windowSizeInfo = rememberWindowSizeInfo()
    var showPaneNumbers by remember { mutableStateOf(false) }
    var showPaneOverlay by remember { mutableStateOf(false) }

    var dividerPositions by rememberSaveable {
        mutableStateOf(DividerPositions())
    }


    val effectivePaneLayout = when (state.displayMode) {
        WorkspacePanelMode.AUTO -> windowSizeInfo.recommendedLayout
        WorkspacePanelMode.SINGLE -> WorkspaceDesign.Layout.SINGLE
        WorkspacePanelMode.DUAL -> if (windowSizeInfo.widthDp > windowSizeInfo.heightDp) {
            WorkspaceDesign.Layout.DUAL_VERTICAL
        } else {
            WorkspaceDesign.Layout.DUAL_HORIZONTAL
        }

        WorkspacePanelMode.TRIPLE -> WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT
    }

    val design = WorkspaceDesign(
        layout = effectivePaneLayout,
    )
    
    // Update pane count when design changes
    LaunchedEffect(design.maxPanes) {
        onScreenAction(WorkspaceScreenAction.SetPaneCount(design.maxPanes))
    }

    if (!design.isSingle) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            WorkspaceNavigationRail(
                design = design,
                workspaces = state.all,
                selected = state.selected,
                focusedId = state.focused,
                onTabAction = onTabAction,
                onPaneAssignment = { workspaceId, paneIndex ->
                        // Create new selection with the workspace at the specified pane index
                        val currentSelection = state.selected.toMutableMap()
                        
                        // Check if this workspace is already assigned to this pane
                        if (currentSelection[paneIndex] == workspaceId) {
                            // Workspace is already in this pane, do nothing to prevent hang
                            return@WorkspaceNavigationRail
                        }
                        
                        // Check if the workspace is already assigned to another pane
                        val existingPosition = currentSelection.entries.find { it.value.id == workspaceId }?.key
                        
                        if (existingPosition != null && existingPosition != paneIndex) {
                            // Workspace is already in another pane - swap them if target pane is occupied
                            val targetWorkspace = currentSelection[paneIndex]
                            if (targetWorkspace != null) {
                                // Swap workspaces
                                currentSelection[paneIndex] = currentSelection[existingPosition]!!
                                currentSelection[existingPosition] = targetWorkspace
                            } else {
                                // Move workspace to empty pane
                                currentSelection.remove(existingPosition)
                                currentSelection[paneIndex] = state.all.find { it.id == workspaceId }!!
                            }
                        } else if (existingPosition == null) {
                            // Workspace not currently selected - add it to the specified pane
                            state.all.find { it.id == workspaceId }?.let { workspace ->
                                currentSelection[paneIndex] = workspace
                            }
                        }
                        
                        // Convert back to Map<Int, Workspace.Id> for the action
                        val newPositions = currentSelection.mapValues { it.value.id }
                        onScreenAction(WorkspaceScreenAction.SelectMultiple(newPositions))
                        
                        showPaneNumbers = false
                        showPaneOverlay = false
                    },
                    onPaneMenuToggle = { isOpen ->
                        showPaneOverlay = isOpen
                        showPaneNumbers = isOpen
                    },
                    workspaceButtonState = workspaceButtonState,
                    onWorkspaceAction = onWorkspaceAction,
                    onNavToWorkspaceManager = onNavToWorkspaceManager,
                    )

                AdaptiveWorkspaceContainer(
                    modifier = Modifier.weight(1f),
                    design = design,
                    selected = state.selected,
                    focusedTabId = state.focused,
                    dividerPositions = dividerPositions,
                    onDividerPositionsChange = { newPositions ->
                        dividerPositions = newPositions
                    },
                    getCurrentDividerPositions = { dividerPositions },
                    onTabFocus = { id ->
                        onScreenAction(WorkspaceScreenAction.Focus(id))
                    },
                    showPaneNumbers = showPaneNumbers,
                    showPaneOverlay = showPaneOverlay,
                    paneContent = { info, paneNumber ->
                        if (info != null) {
                            WorkspaceMapper(
                                info = info,
                                design = design,
                            )
                        } else {
                            EmptyAdaptiveWorkspaceContent(
                                modifier = Modifier.weight(1f),
                                paneNumber = paneNumber,
                            )
                        }
                    }
                )
        }
    } else {
        ClassicWorkspaceContainer(
            state = state,
            onNavToSettings = onNavToSettings,
            onTabAction = onTabAction,
            onUiAction = onScreenAction,
            onUpgradeButler = onUpgradeButler,
        )
    }
}

@Preview2
@Composable
private fun WorkspacesScreenPreview() {
    PreviewWrapper {
        // Create mock workspace infos
        val workspace1 = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            title = "Explorer".toCaString(),
            subtitle = "/storage/emulated/0".toCaString(),
            operationCount = 2,
            attentionCount = 0,
        )
        
        val workspace2 = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.SEARCHER,
            title = "Search Results".toCaString(),
            subtitle = "Found 42 items".toCaString(),
            operationCount = 0,
            attentionCount = 1,
        )
        
        val workspace3 = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EDITOR,
            title = "config.json".toCaString(),
            subtitle = "Modified".toCaString(),
            operationCount = 0,
            attentionCount = 0,
        )
        
        val workspace4 = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.TEMPLATES,
            title = "Templates".toCaString(),
            subtitle = null,
            operationCount = 0,
            attentionCount = 0,
        )
        
        // Create mock state
        val state = WorkspacesViewModel.State(
            state = WorkspaceRemote.State(
                infos = listOf(workspace1, workspace2, workspace3, workspace4),
                isButtonActionsFlipped = false,
                panelMode = WorkspacePanelMode.DUAL,
            ),
            focusedWorkspace = workspace1.id,
            selectedWorkspaces = mapOf(
                0 to workspace1.id,
                1 to workspace2.id,
            ),
            isUpgraded = true,
            isButtonActionsFlipped = false,
            swipeGesturesEnabled = true,
        )
        
        // Mock WorkspaceButtonViewModel state
        val workspaceButtonState = WorkspaceButtonViewModel.State(
            workspaceCount = 4,
            isButtonFlipped = false,
            operationsCount = 2,
            attentionCount = 1,
        )
        
        WorkspaceScreen(
            workspaceButtonState = workspaceButtonState,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
            state = state,
            onNavToSettings = {},
            onTabAction = {},
            onScreenAction = {},
            onUpgradeButler = {},
        )
    }
}
