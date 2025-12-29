package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.DragIndicator
import androidx.compose.material.icons.twotone.Looks3
import androidx.compose.material.icons.twotone.LooksOne
import androidx.compose.material.icons.twotone.LooksTwo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState


@Composable
fun WorkspaceNavigationRail(
    modifier: Modifier = Modifier,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    workspaces: List<Workspace.Info>,
    selected: Map<Int, WorkspacePaneInfo>,
    focusedId: Workspace.Id?,
    design: WorkspaceDesign = WorkspaceDesign(),
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    onPaneMenuToggle: (Boolean) -> Unit = {},
) {
    // Local state for reordering
    var localWorkspaces by remember { mutableStateOf(workspaces) }
    var isDragging by remember { mutableStateOf(false) }

    // Update local workspaces when input changes and not dragging
    if (!isDragging && localWorkspaces != workspaces) {
        localWorkspaces = workspaces
    }

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState
    ) { from, to ->
        val fromId = from.key as? Workspace.Id
        val toId = to.key as? Workspace.Id

        if (fromId != null && toId != null) {
            val fromIndex = localWorkspaces.indexOfFirst { it.id == fromId }
            val toIndex = localWorkspaces.indexOfFirst { it.id == toId }

            if (fromIndex != -1 && toIndex != -1) {
                val mutableList = localWorkspaces.toMutableList()
                val movedItem = mutableList.removeAt(fromIndex)
                mutableList.add(toIndex, movedItem)
                localWorkspaces = mutableList
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.systemBars),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(80.dp)
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WorkspaceButton(
                modifier = Modifier.padding(vertical = 16.dp),
                state = workspaceButtonState,
                currentWorkspaceId = focusedId,
                workspaceActionHandler = workspaceActionHandler,
            )

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 16.dp),
                state = lazyListState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = localWorkspaces,
                    key = { it.id }
                ) { ws ->
                    ReorderableItem(
                        reorderableLazyListState,
                        key = ws.id
                    ) { isDraggingItem ->
                        val paneIndex = selected.entries.find { it.value.id == ws.id }?.key
                        DraggableWorkspaceRailItem(
                            workspace = ws,
                            isSelected = selected.values.any { it.id == ws.id },
                            isFocused = focusedId == ws.id,
                            currentPaneIndex = paneIndex,
                            onTabAction = onTabAction,
                            onPaneAssignment = onPaneAssignment,
                            maxPanes = design.maxPanes,
                            onPaneMenuToggle = onPaneMenuToggle,
                            isDraggingItem = isDraggingItem,
                            onDragStarted = {
                                isDragging = true
                            },
                            onDragStopped = {
                                isDragging = false
                                // Trigger reorder action with new order
                                val newOrder = localWorkspaces.map { it.id }
                                onTabAction(WorkspaceAction.Reorder(newOrder))
                            },
                            reorderableScope = this,
                        )
                    }
                }
            }

            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            FloatingActionButton(
                onClick = {
                    onTabAction(
                        WorkspaceAction.Create()
                    )
                },
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Add,
                    contentDescription = stringResource(R.string.workspace_add_tab_description),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DraggableWorkspaceRailItem(
    workspace: Workspace.Info,
    isSelected: Boolean,
    isFocused: Boolean,
    currentPaneIndex: Int?,
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    maxPanes: Int,
    onPaneMenuToggle: (Boolean) -> Unit,
    isDraggingItem: Boolean,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    reorderableScope: sh.calvin.reorderable.ReorderableCollectionItemScope,
) {
    val hapticFeedback = LocalHapticFeedback.current
    var showPaneMenu by remember { mutableStateOf(false) }

    LaunchedEffect(showPaneMenu) {
        onPaneMenuToggle(showPaneMenu)
    }

    Box {
        NavigationRailItem(
            selected = isSelected,
            onClick = { showPaneMenu = true },
            modifier = if (isFocused) {
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    )
            } else if (isDraggingItem) {
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp)
                    )
            } else {
                Modifier
            },
            icon = {
                Box(
                    modifier = with(reorderableScope) {
                        Modifier.draggableHandle(
                            onDragStarted = {
                                onDragStarted()
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = {
                                onDragStopped()
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            }
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isDraggingItem) {
                            // Show drag indicator when dragging
                            Icon(
                                imageVector = Icons.TwoTone.DragIndicator,
                                contentDescription = stringResource(R.string.workspace_dragging_description),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            // Show pane number on the left of the icon
                            currentPaneIndex?.let { paneIdx ->
                                Text(
                                    text = "${paneIdx + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(end = 2.dp)
                                )
                            }
                            Icon(
                                imageVector = workspace.type.icon,
                                contentDescription = workspace.title.get(LocalContext.current),
                                modifier = if (currentPaneIndex != null) {
                                    Modifier.padding(start = 2.dp)
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                }
            },
            label = {
                Text(
                    text = workspace.title.get(LocalContext.current),
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = if (isFocused) TextDecoration.Underline else TextDecoration.None
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            },
        )

        DropdownMenu(
            expanded = showPaneMenu,
            onDismissRequest = { showPaneMenu = false },
        ) {
            repeat(maxPanes) { paneIndex ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_pane_assign_action, paneIndex + 1)) },
                    leadingIcon = {
                        Icon(
                            imageVector = when (paneIndex) {
                                0 -> Icons.TwoTone.LooksOne
                                1 -> Icons.TwoTone.LooksTwo
                                2 -> Icons.TwoTone.Looks3
                                else -> Icons.TwoTone.LooksOne
                            },
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showPaneMenu = false
                        onPaneMenuToggle(false)  // Explicitly hide overlays
                        onPaneAssignment(workspace.id, paneIndex)
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_pane_close_action)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = null,
                    )
                },
                onClick = {
                    showPaneMenu = false
                    onPaneMenuToggle(false)  // Explicitly hide overlays before closing
                    onTabAction(WorkspaceAction.Close(workspace.id))
                },
            )
        }
    }
}

@Composable
private fun WorkspaceRailItem(
    workspace: Workspace.Info,
    isSelected: Boolean,
    isFocused: Boolean,
    currentPaneIndex: Int?,
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    maxPanes: Int,
    onPaneMenuToggle: (Boolean) -> Unit,
) {
    var showPaneMenu by remember { mutableStateOf(false) }

    LaunchedEffect(showPaneMenu) {
        onPaneMenuToggle(showPaneMenu)
    }

    Box {
        NavigationRailItem(
            selected = isSelected,
            onClick = { showPaneMenu = true },
            modifier = if (isFocused) {
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    )
            } else {
                Modifier
            },
            icon = {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Show pane number on the left of the icon
                    currentPaneIndex?.let { paneIdx ->
                        Text(
                            text = "${paneIdx + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(end = 2.dp)
                        )
                    }
                    Icon(
                        imageVector = workspace.type.icon,
                        contentDescription = workspace.title.get(LocalContext.current),
                        modifier = if (currentPaneIndex != null) {
                            Modifier.padding(start = 2.dp)
                        } else {
                            Modifier
                        }
                    )
                }
            },
            label = {
                Text(
                    text = workspace.title.get(LocalContext.current),
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = if (isFocused) TextDecoration.Underline else TextDecoration.None
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            },
        )

        DropdownMenu(
            expanded = showPaneMenu,
            onDismissRequest = { showPaneMenu = false },
        ) {
            repeat(maxPanes) { paneIndex ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_pane_assign_action, paneIndex + 1)) },
                    leadingIcon = {
                        Icon(
                            imageVector = when (paneIndex) {
                                0 -> Icons.TwoTone.LooksOne
                                1 -> Icons.TwoTone.LooksTwo
                                2 -> Icons.TwoTone.Looks3
                                else -> Icons.TwoTone.LooksOne
                            },
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showPaneMenu = false
                        onPaneMenuToggle(false)  // Explicitly hide overlays
                        onPaneAssignment(workspace.id, paneIndex)
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_pane_close_action)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = null,
                    )
                },
                onClick = {
                    showPaneMenu = false
                    onPaneMenuToggle(false)  // Explicitly hide overlays before closing
                    onTabAction(WorkspaceAction.Close(workspace.id))
                },
            )
        }
    }
}

@Preview2
@Composable
private fun WorkspaceNavigationRailPreview() {
    PreviewWrapper {
        val tabs = listOf(
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Explorer 1234".toCaString(),
            ),
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search 1234".toCaString(),
            ),
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EDITOR,
                title = "Editor 1234".toCaString(),
            ),
        )
        WorkspaceNavigationRail(
            workspaceButtonState = null,
            workspaces = tabs,
            selected = mapOf(0 to tabs[0].asPaneInfo(), 1 to tabs[1].asPaneInfo()),
            focusedId = tabs[0].id,
            onTabAction = {},
            onPaneAssignment = { _, _ -> },
            onPaneMenuToggle = {},
        )
    }
}

@Preview2
@Composable
private fun PaneMenuPreview() {
    PreviewWrapper {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = {},
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_pane_assign_action, 1)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.LooksOne,
                            contentDescription = null,
                        )
                    },
                    onClick = {},
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_pane_assign_action, 2)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.LooksTwo,
                            contentDescription = null,
                        )
                    },
                    onClick = {},
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_pane_assign_action, 3)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.Looks3,
                            contentDescription = null,
                        )
                    },
                    onClick = {},
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_pane_close_action)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {},
                )
            }
        }
    }
}