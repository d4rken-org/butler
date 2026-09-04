package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dnd.dropTargetHighlight
import eu.darken.butler.workspace.ui.dnd.workspaceDragPayload
import eu.darken.butler.workspace.ui.insets.LocalPaneBottomChrome
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.RailPlacement
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
    bannerStates: Map<Workspace.Id, eu.darken.butler.workspace.ui.feedback.BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
    /** Whether an unfocused pane has to be clicked once before it reacts to anything. */
    clickToFocus: Boolean,
    onRenameWorkspace: (Workspace.Id) -> Unit = {},
    paneLocalModalChains: Map<Workspace.Id, List<Workspace.Info>> = emptyMap(),
    isUpgraded: Boolean = false,
    isOverlayVisible: Boolean = false,
    fullScreenModalVisible: Boolean = false,
    /** The pane whose empty content anchors the first-tab tour, or `null` when it isn't running. */
    firstTabTourPaneNumber: Int? = null,
    /** Scrolls the add-tab card into view before the tour's step is published. */
    firstTabTourRequester: BringIntoViewRequester? = null,
    /** The rail's measured height in its bottom placement, owned by the host that draws chrome too. */
    railThickness: Dp = 0.dp,
    onRailThicknessChanged: (Dp) -> Unit = {},
    onShareError: (Workspace.Id, Throwable) -> Unit,
) {
    val dragDropState = remember { DragDropState() }
    val workspaceActionHandler = LocalWorkspaceButtonProvider.current

    CompositionLocalProvider(LocalDragDropState provides dragDropState) {
        val rail: @Composable () -> Unit = {
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
                onPaneUnassign = { workspaceId ->
                    onScreenAction(WorkspaceScreenAction.UnassignPane(workspaceId))
                },
                onRename = onRenameWorkspace,
                onPaneMenuToggle = onPaneMenuToggle,
                onRailThicknessChanged = onRailThicknessChanged,
            )
        }

        when (design.railPlacement) {
            RailPlacement.START -> Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                rail()
                PaneArea(
                    modifier = Modifier.weight(1f),
                    design = design,
                    railThickness = railThickness,
                    selected = selected,
                    focusedId = focusedId,
                    dividerPositions = dividerPositions,
                    onDividerPositionsChange = onDividerPositionsChange,
                    showPaneNumbers = showPaneNumbers,
                    showPaneOverlay = showPaneOverlay,
                    onScreenAction = onScreenAction,
                    managerDialogStates = managerDialogStates,
                    bannerStates = bannerStates,
                    onDismissBanner = onDismissBanner,
                    clickToFocus = clickToFocus,
                    paneLocalModalChains = paneLocalModalChains,
                    isUpgraded = isUpgraded,
                    isOverlayVisible = isOverlayVisible,
                    fullScreenModalVisible = fullScreenModalVisible,
                    firstTabTourPaneNumber = firstTabTourPaneNumber,
                    firstTabTourRequester = firstTabTourRequester,
                    onShareError = onShareError,
                )
            }
            RailPlacement.BOTTOM -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                PaneArea(
                    modifier = Modifier.weight(1f),
                    design = design,
                    railThickness = railThickness,
                    selected = selected,
                    focusedId = focusedId,
                    dividerPositions = dividerPositions,
                    onDividerPositionsChange = onDividerPositionsChange,
                    showPaneNumbers = showPaneNumbers,
                    showPaneOverlay = showPaneOverlay,
                    onScreenAction = onScreenAction,
                    managerDialogStates = managerDialogStates,
                    bannerStates = bannerStates,
                    onDismissBanner = onDismissBanner,
                    clickToFocus = clickToFocus,
                    paneLocalModalChains = paneLocalModalChains,
                    isUpgraded = isUpgraded,
                    isOverlayVisible = isOverlayVisible,
                    fullScreenModalVisible = fullScreenModalVisible,
                    firstTabTourPaneNumber = firstTabTourPaneNumber,
                    firstTabTourRequester = firstTabTourRequester,
                    onShareError = onShareError,
                )
                rail()
            }
        }
    }
}

/**
 * The panes, without the rail. Takes no scope receiver of its own: each placement hands it the
 * weight modifier of the row or column it is a child of.
 */
@Composable
private fun PaneArea(
    modifier: Modifier = Modifier,
    design: WorkspaceDesign,
    railThickness: Dp,
    selected: Map<Int, WorkspacePaneInfo>,
    focusedId: Workspace.Id?,
    dividerPositions: DividerPositions,
    onDividerPositionsChange: (DividerPositions) -> Unit,
    showPaneNumbers: Boolean,
    showPaneOverlay: Boolean,
    onScreenAction: (WorkspaceScreenAction) -> Unit,
    managerDialogStates: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    bannerStates: Map<Workspace.Id, eu.darken.butler.workspace.ui.feedback.BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
    clickToFocus: Boolean,
    paneLocalModalChains: Map<Workspace.Id, List<Workspace.Info>>,
    isUpgraded: Boolean,
    isOverlayVisible: Boolean,
    fullScreenModalVisible: Boolean,
    firstTabTourPaneNumber: Int?,
    firstTabTourRequester: BringIntoViewRequester?,
    onShareError: (Workspace.Id, Throwable) -> Unit,
) {
    val workspaceActionHandler = LocalWorkspaceButtonProvider.current
    val currentOnScreenAction by rememberUpdatedState(onScreenAction)
    val placement = design.railPlacement

    AdaptiveWorkspaceContainer(
        modifier = modifier,
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
            // Read before the rail's edge is cleared: it is what says the rail is the only thing
            // between this pane and the bottom of the window.
            val isBottomPane = design.forPane(paneNumber).paneEdges.touchesBottom
            // The navigation rail occupies one window edge, so no pane reaches it.
            val paneDesign = design.forPane(paneNumber).withoutEdges(
                start = placement == RailPlacement.START,
                bottom = placement == RailPlacement.BOTTOM,
            )
            val bottomChrome = when {
                isBottomPane && placement == RailPlacement.BOTTOM -> railThickness
                else -> 0.dp
            }
            CompositionLocalProvider(LocalPaneBottomChrome provides bottomChrome) {
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
                            clickToFocus = clickToFocus,
                            onRequestPaneFocus = {
                                onScreenAction(WorkspaceScreenAction.Focus(info.id))
                            },
                            childModals = chain.map { it.asPaneInfo() },
                            activeWorkspaceId = activeId,
                            managerDialogStates = managerDialogStates,
                            onScreenAction = onScreenAction,
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
                    // An empty pane opens exactly one dropped item, the same way tapping it
                    // would (folder -> Explorer, text -> Editor, anything else -> Viewer).
                    val paneIndex = paneNumber - 1
                    val isDropHovered = remember { mutableStateOf(false) }
                    val dropTarget = remember(paneIndex) {
                        object : DragAndDropTarget {
                            override fun onEntered(event: DragAndDropEvent) {
                                isDropHovered.value = true
                            }

                            override fun onExited(event: DragAndDropEvent) {
                                isDropHovered.value = false
                            }

                            override fun onEnded(event: DragAndDropEvent) {
                                isDropHovered.value = false
                            }

                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                isDropHovered.value = false
                                val payload = event.workspaceDragPayload()
                                    ?.takeIf { it.items.size == 1 }
                                    ?: return false
                                currentOnScreenAction(
                                    WorkspaceScreenAction.OpenDropInPane(paneIndex, payload)
                                )
                                return true
                            }
                        }
                    }

                    EmptyAdaptiveWorkspaceContent(
                        modifier = Modifier
                            .paneHorizontalInsetPadding(paneDesign.paneEdges)
                            .dragAndDropTarget(
                                shouldStartDragAndDrop = { event ->
                                    event.workspaceDragPayload()?.items?.size == 1
                                },
                                target = dropTarget,
                            )
                            .dropTargetHighlight(isDropHovered.value),
                        paneNumber = paneNumber,
                        paneEdges = paneDesign.paneEdges,
                        isUpgraded = isUpgraded,
                        isTourTarget = paneNumber == firstTabTourPaneNumber,
                        tourRequester = firstTabTourRequester,
                        onAddWorkspace = {
                            onScreenAction(WorkspaceScreenAction.CreateForPane(paneNumber - 1))
                        },
                    )
                }
            }
        }
    )
}
