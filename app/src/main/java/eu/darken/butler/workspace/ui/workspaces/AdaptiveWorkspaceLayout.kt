package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.WorkspaceOverlayContainer
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
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
    onScreenAction: (WorkspaceScreenAction) -> Unit,
    managerDialogStates: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    onDismissManagerDialog: (Workspace.Id) -> Unit,
    onConfirmManagerDialog: (ManagerDialog.WorkspaceTargeted) -> Unit,
    bannerStates: Map<Workspace.Id, eu.darken.butler.workspace.ui.feedback.BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
    paneLocalModals: Map<Workspace.Id, Workspace.Info> = emptyMap(),
    isUpgraded: Boolean = false,
    isOverlayVisible: Boolean = false,
    onShareError: (Workspace.Id, Throwable) -> Unit,
) {
    val dragDropState = remember { DragDropState() }
    val workspaceActionHandler = LocalWorkspaceButtonProvider.current

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
            )

            AdaptiveWorkspaceContainer(
                modifier = Modifier
                    .weight(1f),
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
                    val paneDesign = design.forPane(paneNumber)
                    if (info != null) {
                        key(info.id) {
                            // Check if this workspace has a pane-local modal child
                            val childModal = paneLocalModals[info.id]

                            Box {
                                // Background: Parent workspace
                                // When overlay is visible, no workspace should be considered focused
                                CompositionLocalProvider(
                                    LocalWorkspaceFocused provides (focusedId == info.id && !isOverlayVisible),
                                    LocalWorkspaceFocusRequest provides {
                                        onScreenAction(
                                            WorkspaceScreenAction.Focus(
                                                info.id
                                            )
                                        )
                                    },
                                ) {
                                    WorkspaceOverlayContainer(
                                        workspaceId = info.id,
                                        managerDialogStates = managerDialogStates,
                                        onDismissManagerDialog = onDismissManagerDialog,
                                        onConfirmManagerDialog = onConfirmManagerDialog,
                                        bannerStates = bannerStates,
                                        onDismissBanner = onDismissBanner,
                                    ) {
                                        WorkspaceMapper(
                                            info = info,
                                            design = paneDesign,
                                            onShareError = { error ->
                                                onShareError(info.id, error)
                                            },
                                            onCloseWorkspace = {
                                                workspaceActionHandler?.executeWorkspaceAction(
                                                    WorkspaceAction.Close(info.id)
                                                )
                                            },
                                            onRestoreWorkspace = {
                                                onScreenAction(
                                                    WorkspaceScreenAction.RestoreDormant(info.id)
                                                )
                                            },
                                        )
                                    }
                                }

                                // Overlay: Child modal (if any)
                                childModal?.let { modal ->
                                    key(modal.id) {
                                        CompositionLocalProvider(
                                            LocalWorkspaceFocused provides (focusedId == modal.id && !isOverlayVisible),
                                            LocalWorkspaceFocusRequest provides {
                                                onScreenAction(
                                                    WorkspaceScreenAction.Focus(
                                                        modal.id
                                                    )
                                                )
                                            },
                                        ) {
                                            WorkspaceOverlayContainer(
                                                workspaceId = modal.id,
                                                managerDialogStates = managerDialogStates,
                                                onDismissManagerDialog = onDismissManagerDialog,
                                                onConfirmManagerDialog = onConfirmManagerDialog,
                                                bannerStates = bannerStates,
                                                onDismissBanner = onDismissBanner,
                                            ) {
                                                WorkspaceMapper(
                                                    info = modal.asPaneInfo(),
                                                    design = paneDesign,
                                                    onShareError = { error ->
                                                        onShareError(modal.id, error)
                                                    },
                                                    onCloseWorkspace = {
                                                        workspaceActionHandler?.executeWorkspaceAction(
                                                            WorkspaceAction.Close(modal.id)
                                                        )
                                                    },
                                                    onRestoreWorkspace = {
                                                        onScreenAction(
                                                            WorkspaceScreenAction.RestoreDormant(modal.id)
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        EmptyAdaptiveWorkspaceContent(
                            modifier = Modifier.weight(1f),
                            paneNumber = paneNumber,
                            paneEdges = paneDesign.paneEdges,
                            isUpgraded = isUpgraded,
                            onAddWorkspace = {
                                onScreenAction(WorkspaceScreenAction.CreateForPane(paneNumber - 1))
                            },
                        )
                    }
                }
            )
        }
    }
}