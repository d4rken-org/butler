package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.background
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
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
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
    /** Every assignment, incl. panes this layout does not render. Drives assignment, not display. */
    selected: Map<Int, WorkspacePaneInfo>,
    /** Only the assignments this layout renders. Anything showing a pane number reads this. */
    visibleSelected: Map<Int, WorkspacePaneInfo> = selected,
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
    onRenameWorkspace: (Workspace.Id) -> Unit = {},
    paneLocalModalChains: Map<Workspace.Id, List<Workspace.Info>> = emptyMap(),
    isUpgraded: Boolean = false,
    isOverlayVisible: Boolean = false,
    fullScreenModalVisible: Boolean = false,
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
                // Badges say which pane a tab occupies, so they follow the panes this layout has.
                selected = visibleSelected,
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
                onRename = onRenameWorkspace,
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
                    // The navigation rail occupies the start edge, so no pane reaches it.
                    val paneDesign = design.forPane(paneNumber).withoutEdges(start = true)
                    if (info != null) {
                        key(info.id) {
                            val chain = paneLocalModalChains[info.id].orEmpty()
                            // A modal covering everything takes focus away from the panes below it,
                            // exactly like the tab manager overlay does.
                            val focusSuppressed = isOverlayVisible || fullScreenModalVisible
                            val paneIsFocused = !focusSuppressed &&
                                (focusedId == info.id || chain.any { it.id == focusedId })
                            // Deepest layer is the active one; global focus can sit on a covered
                            // ancestor (launchPicker never moves it).
                            val activeId = (chain.lastOrNull()?.id ?: info.id).takeIf { paneIsFocused }

                            WorkspacePane(
                                info = info,
                                design = paneDesign,
                                // Any occupant counts as focusing the pane, and every layer requests
                                // focus for the tab: a Focus() for a modal is silently dropped,
                                // which would leave another pane active.
                                paneFocused = paneIsFocused,
                                onRequestPaneFocus = {
                                    onScreenAction(WorkspaceScreenAction.Focus(info.id))
                                },
                                childModals = chain.map { it.asPaneInfo() },
                                activeWorkspaceId = activeId,
                                managerDialogStates = managerDialogStates,
                                onDismissManagerDialog = onDismissManagerDialog,
                                onConfirmManagerDialog = onConfirmManagerDialog,
                                bannerStates = bannerStates,
                                onDismissBanner = onDismissBanner,
                                paneEdges = paneDesign.paneEdges,
                                onShareError = onShareError,
                                onCloseWorkspace = { workspaceId ->
                                    workspaceActionHandler?.executeWorkspaceAction(
                                        WorkspaceAction.Close(workspaceId)
                                    )
                                },
                                onResumeWorkspace = { workspaceId ->
                                    onScreenAction(WorkspaceScreenAction.ResumeWorkspace(workspaceId))
                                },
                            )
                        }
                    } else {
                        EmptyAdaptiveWorkspaceContent(
                            modifier = Modifier
                                .weight(1f)
                                .paneHorizontalInsetPadding(paneDesign.paneEdges),
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