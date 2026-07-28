package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Looks3
import androidx.compose.material.icons.twotone.LooksOne
import androidx.compose.material.icons.twotone.LooksTwo
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.systemBarsWithOptionalCutout
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import eu.darken.butler.common.R as CommonR

object WorkspaceNavigationRailDefaults {
    const val SURFACE_TEST_TAG = "workspace.rail.surface"
    const val CONTENT_TEST_TAG = "workspace.rail.content"
    const val LIST_TEST_TAG = "workspace.rail.list"
}

private val RailSectionPadding = 8.dp
private val RailItemHeight = 56.dp
private val RailItemSpacing = 4.dp
private val RailItemShape = RoundedCornerShape(16.dp)

@Composable
fun WorkspaceNavigationRail(
    modifier: Modifier = Modifier,
    workspaces: List<Workspace.Info>,
    selected: Map<Int, WorkspacePaneInfo>,
    focusedId: Workspace.Id?,
    design: WorkspaceDesign = WorkspaceDesign(),
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    onRename: (Workspace.Id) -> Unit = {},
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

    WorkspaceRailContainer(modifier = modifier) {
        WorkspaceButton(
            modifier = Modifier.padding(vertical = RailSectionPadding),
            currentWorkspaceId = focusedId,
        )

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = RailSectionPadding)
                .testTag(WorkspaceNavigationRailDefaults.LIST_TEST_TAG),
            state = lazyListState,
            verticalArrangement = Arrangement.spacedBy(RailItemSpacing),
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
                        isFocused = focusedId == ws.id,
                        currentPaneIndex = paneIndex,
                        onTabAction = onTabAction,
                        onPaneAssignment = onPaneAssignment,
                        onRename = onRename,
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

        Spacer(modifier = Modifier.height(RailSectionPadding))

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

        Spacer(modifier = Modifier.height(RailSectionPadding))
    }
}

@Composable
internal fun WorkspaceRailContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .testTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // Only the start side: the rail doesn't touch the end edge, and padding for an end-side
                // navigation bar here would widen the rail while the end pane pads for it as well.
                .windowInsetsPadding(
                    systemBarsWithOptionalCutout()
                        .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
                )
                .width(80.dp)
                .testTag(WorkspaceNavigationRailDefaults.CONTENT_TEST_TAG)
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * Rail entry with its own background: nothing when the workspace sits idle, an outline once it is
 * assigned to a pane, a filled container while it is the focused one.
 */
@Composable
internal fun WorkspaceRailItem(
    modifier: Modifier = Modifier,
    workspace: Workspace.Info,
    paneIndex: Int?,
    isFocused: Boolean,
    isDraggingItem: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isAssigned = paneIndex != null

    val containerColor by animateColorAsState(
        targetValue = if (isFocused) colorScheme.secondaryContainer else Color.Transparent,
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (isDraggingItem) 6.dp else 0.dp,
    )
    val scale by animateFloatAsState(
        targetValue = if (isDraggingItem) 1.05f else 1f,
        animationSpec = spring(),
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(RailItemHeight)
            .scale(scale)
            .semantics {
                selected = isAssigned
                role = Role.Tab
            },
        shape = RailItemShape,
        color = containerColor,
        contentColor = when {
            isFocused -> colorScheme.onSecondaryContainer
            isAssigned -> colorScheme.onSurface
            else -> colorScheme.onSurfaceVariant
        },
        border = if (isAssigned && !isFocused) BorderStroke(1.dp, colorScheme.outline) else null,
        shadowElevation = shadowElevation,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = dragHandleModifier,
                contentAlignment = Alignment.Center,
            ) {
                BadgedBox(
                    badge = {
                        paneIndex?.let {
                            val paneDescription = stringResource(R.string.workspace_pane_current_description, it + 1)
                            Badge(
                                modifier = Modifier.semantics { contentDescription = paneDescription },
                                containerColor = colorScheme.tertiary,
                                contentColor = colorScheme.onTertiary,
                            ) {
                                Text(text = "${it + 1}")
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (isDraggingItem) Icons.TwoTone.DragIndicator else workspace.type.icon,
                        contentDescription = if (isDraggingItem) {
                            stringResource(R.string.workspace_dragging_description)
                        } else {
                            workspace.displayTitle.get(LocalContext.current)
                        },
                    )
                }
            }
            Text(
                text = workspace.displayTitle.get(LocalContext.current),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRailItemPreview() {
    Column(
        modifier = Modifier
            .width(80.dp)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(RailItemSpacing),
    ) {
        WorkspaceRailItem(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Explorer".toCaString(),
            ),
            paneIndex = null,
            isFocused = false,
            onClick = {},
        )
        WorkspaceRailItem(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search".toCaString(),
            ),
            paneIndex = 1,
            isFocused = false,
            onClick = {},
        )
        WorkspaceRailItem(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EDITOR,
                title = "Editor".toCaString(),
            ),
            paneIndex = 0,
            isFocused = true,
            onClick = {},
        )
        WorkspaceRailItem(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.TEMPLATES,
                title = "Templates".toCaString(),
            ),
            paneIndex = null,
            isFocused = false,
            isDraggingItem = true,
            onClick = {},
        )
    }
}

@Composable
private fun DraggableWorkspaceRailItem(
    workspace: Workspace.Info,
    isFocused: Boolean,
    currentPaneIndex: Int?,
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    onRename: (Workspace.Id) -> Unit,
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
        WorkspaceRailItem(
            workspace = workspace,
            paneIndex = currentPaneIndex,
            isFocused = isFocused,
            isDraggingItem = isDraggingItem,
            dragHandleModifier = with(reorderableScope) {
                Modifier.draggableHandle(
                    onDragStarted = {
                        onDragStarted()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragStopped = {
                        onDragStopped()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    },
                )
            },
            onClick = { showPaneMenu = true },
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
            DropdownMenuItem(
                text = { Text(stringResource(CommonR.string.general_rename_action)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Edit,
                        contentDescription = null,
                    )
                },
                onClick = {
                    showPaneMenu = false
                    onPaneMenuToggle(false)
                    onRename(workspace.id)
                },
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceNavigationRailPreview() {
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
        workspaces = tabs,
        selected = mapOf(0 to tabs[0].asPaneInfo(), 1 to tabs[1].asPaneInfo()),
        focusedId = tabs[0].id,
        onTabAction = {},
        onPaneAssignment = { _, _ -> },
        onPaneMenuToggle = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneMenuPreview() {
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