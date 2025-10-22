package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.adaptive.AdaptiveWorkspaceContainer
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.adaptive.DragDropState
import eu.darken.butler.workspace.ui.workspaces.adaptive.EmptyAdaptiveWorkspaceContent
import eu.darken.butler.workspace.ui.workspaces.adaptive.LocalDragDropState
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRail

@Composable
fun AdaptiveWorkspaceLayout(
    design: WorkspaceDesign,
    workspaces: List<Workspace.Info>,
    selected: Map<Int, WorkspacePaneInfo>,
    focusedId: Workspace.Id?,
    dividerPositions: DividerPositions,
    onDividerPositionsChange: (DividerPositions) -> Unit,
    showPaneNumbers: Boolean,
    showPaneOverlay: Boolean,
    onPaneMenuToggle: (Boolean) -> Unit,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    onScreenAction: (WorkspaceScreenAction) -> Unit,
) {
    val dragDropState = remember { DragDropState() }


    CompositionLocalProvider(LocalDragDropState provides dragDropState) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            WorkspaceNavigationRail(
                design = design,
                workspaces = workspaces,
                selected = selected,
                focusedId = focusedId,
                onTabAction = { workspaceActionHandler?.executeWorkspaceAction(it) },
                onPaneAssignment = { workspaceId, paneIndex ->
                    // Create new selection with the workspace at the specified pane index
                    val currentSelection = selected.toMutableMap()

                    // Check if this workspace is already assigned to this pane
                    if (currentSelection[paneIndex]?.id == workspaceId) {
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
                            currentSelection[paneIndex] = workspaces.find { it.id == workspaceId }!!.asPaneInfo()
                        }
                    } else if (existingPosition == null) {
                        // Workspace not currently selected - add it to the specified pane
                        workspaces.find { it.id == workspaceId }?.let { workspace ->
                            currentSelection[paneIndex] = workspace.asPaneInfo()
                        }
                    }

                    // Convert back to Map<Int, Workspace.Id> for the action
                    val newPositions = currentSelection.mapValues { it.value.id }
                    onScreenAction(WorkspaceScreenAction.SelectMultiple(newPositions))

                    onPaneMenuToggle(false)
                },
                onPaneMenuToggle = onPaneMenuToggle,
                workspaceButtonState = workspaceButtonState,
                workspaceActionHandler = workspaceActionHandler,
            )

            AdaptiveWorkspaceContainer(
                modifier = Modifier
                    .weight(1f)
                    .systemBarsPadding(),
                design = design,
                selected = selected,
                focusedTabId = focusedId,
                dividerPositions = dividerPositions,
                onDividerPositionsChange = onDividerPositionsChange,
                onTabFocus = { id ->
                    onScreenAction(WorkspaceScreenAction.Focus(id))
                },
                showPaneNumbers = showPaneNumbers,
                showPaneOverlay = showPaneOverlay,
                paneContent = { info, paneNumber ->
                    if (info != null) {
                        key(info.id) {
                            WorkspaceMapper(
                                info = info,
                                design = design,
                            )
                        }
                    } else {
                        EmptyAdaptiveWorkspaceContent(
                            modifier = Modifier.weight(1f),
                            paneNumber = paneNumber,
                        )
                    }
                }
            )
        }
    }
}