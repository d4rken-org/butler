package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.DragIndicator
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceBadgeExplanationCard
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceButtonBehaviorCard
import kotlin.math.roundToInt

@Composable
fun WorkspaceManagerScreenHost(
    vm: WorkspaceManagerViewModel = hiltViewModel()
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }

    state?.let { currentState ->
        WorkspaceManagerScreen(
            state = currentState,
            onCloseWorkspace = vm::closeWorkspace,
            onReorderWorkspaces = vm::reorderWorkspaces,
            onSelectWorkspace = vm::selectWorkspace,
            onCreateWorkspace = vm::createWorkspace,
            onNavigateBack = vm::navigateBack,
            onNavigateToSettings = vm::navigateToSettings,
            onToggleButtonFlipped = { vm.toggleButtonFlipped() },
            onDismissBadgeExplanation = vm::dismissBadgeExplanation,
            onDismissButtonBehaviorExplanation = vm::dismissButtonBehaviorExplanation,
            onCloseAllWorkspaces = vm::closeAllWorkspaces,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceManagerScreen(
    state: WorkspaceManagerViewModel.State,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onCreateWorkspace: (Workspace.Type) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onToggleButtonFlipped: () -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onDismissButtonBehaviorExplanation: () -> Unit,
    onCloseAllWorkspaces: () -> Unit,
) {
    var workspaceItems by remember { mutableStateOf(state.workspaces) }
    LaunchedEffect(state.workspaces) {
        workspaceItems = state.workspaces
    }

    var showCloseAllDialog by remember { mutableStateOf(false) }
    var showDropdown by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    LocalHapticFeedback.current

    // FAB scroll offset
    var fabOffsetY by remember { mutableStateOf(0f) }
    val fabNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0) {
                    // Scrolling up - hide FAB
                    fabOffsetY = (fabOffsetY + available.y).coerceAtLeast(-200f)
                } else if (available.y > 0) {
                    // Scrolling down - show FAB
                    fabOffsetY = (fabOffsetY + available.y).coerceAtMost(0f)
                }
                return Offset.Zero
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .nestedScroll(fabNestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Workspaces,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = stringResource(R.string.workspace_manager_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.TwoTone.Settings,
                            contentDescription = "Settings"
                        )
                    }
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = "Dismiss"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            if (workspaceItems.isNotEmpty()) {
                Box(
                    modifier = Modifier.graphicsLayer {
                        translationY = fabOffsetY
                    }
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { showDropdown = true },
                        icon = {
                            Icon(
                                imageVector = Icons.TwoTone.Add,
                                contentDescription = null
                            )
                        },
                        text = { Text("Add Workspace") }
                    )

                    DropdownMenu(
                        expanded = showDropdown,
                        onDismissRequest = { showDropdown = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Explorer") },
                            onClick = {
                                onCreateWorkspace(Workspace.Type.EXPLORER)
                                showDropdown = false
                            },
                            leadingIcon = {
                                Icon(Icons.TwoTone.Folder, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Search") },
                            onClick = {
                                onCreateWorkspace(Workspace.Type.SEARCHER)
                                showDropdown = false
                            },
                            leadingIcon = {
                                Icon(Icons.TwoTone.Search, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Editor") },
                            onClick = {
                                onCreateWorkspace(Workspace.Type.EDITOR)
                                showDropdown = false
                            },
                            leadingIcon = {
                                Icon(Icons.TwoTone.Edit, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Templates") },
                            onClick = {
                                onCreateWorkspace(Workspace.Type.TEMPLATES)
                                showDropdown = false
                            },
                            leadingIcon = {
                                Icon(Icons.TwoTone.Workspaces, contentDescription = null)
                            }
                        )
                        if (state.workspaceCount > 1) {
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Close All Workspaces",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showDropdown = false
                                    showCloseAllDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.TwoTone.Close,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                )
                            }
                            )
                    }
                }
            }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // Single LazyColumn for all content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = paddingValues,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (workspaceItems.isEmpty()) {
                // Empty state as a single item
                item(key = "empty_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillParentMaxHeight()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Workspaces,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = stringResource(R.string.workspace_manager_empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        Text(
                            text = stringResource(R.string.workspace_manager_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            } else {
                // Status card as first item
                if (state.workspaceCount > 0) {
                    item(key = "status_card") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Workspace count
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (state.workspaceCount > 9) "9+" else state.workspaceCount.toString(),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(
                                        text = if (state.workspaceCount == 1) "Workspace" else "Workspaces",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Operations count
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                color = if (state.operationsCount > 0)
                                                    MaterialTheme.colorScheme.primaryContainer
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (state.operationsCount > 9) "9+" else state.operationsCount.toString(),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (state.operationsCount > 0)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = if (state.operationsCount == 1) "Operation" else "Operations",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Attention count
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                color = if (state.attentionCount > 0)
                                                    MaterialTheme.colorScheme.errorContainer
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (state.attentionCount > 9) "9+" else state.attentionCount.toString(),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (state.attentionCount > 0)
                                                MaterialTheme.colorScheme.error
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "Attention",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                itemsIndexed(
                    items = workspaceItems,
                    key = { _, workspace -> workspace.id.toString() }
                ) { index, workspace ->
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        DraggableWorkspaceListItem(
                            workspace = workspace,
                            onClose = { onCloseWorkspace(workspace.id) },
                            onSelect = { onSelectWorkspace(workspace.id) },
                            onDragEnd = { fromIndex, toIndex ->
                                if (fromIndex != toIndex) {
                                    val newList = workspaceItems.toMutableList()
                                    val movedItem = newList.removeAt(fromIndex)
                                    newList.add(toIndex, movedItem)
                                    workspaceItems = newList
                                    onReorderWorkspaces(newList.map { it.id })
                                }
                            },
                            index = index
                        )
                    }
                }

                // Button behavior explanation card
                if (state.showButtonBehaviorExplanation) {
                    item(key = "button_behavior_explanation") {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            WorkspaceButtonBehaviorCard(
                                isButtonFlipped = state.isButtonFlipped,
                                onToggleFlipped = { onToggleButtonFlipped() },
                                onDismiss = onDismissButtonBehaviorExplanation
                            )
                        }
                    }
                }

                // Badge explanation card
                if (state.showBadgeExplanation) {
                    item(key = "badge_explanation") {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            WorkspaceBadgeExplanationCard(
                                onDismiss = onDismissBadgeExplanation
                            )
                        }
                    }
                }
            }
        }
    }

    // Close all confirmation dialog
    if (showCloseAllDialog) {
        AlertDialog(
            onDismissRequest = { showCloseAllDialog = false },
            title = { Text("Close All Workspaces?") },
            text = {
                Text("This will close all ${state.workspaceCount} open ${if (state.workspaceCount == 1) "workspace" else "workspaces"}. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCloseAllWorkspaces()
                        showCloseAllDialog = false
                    }
                ) {
                    Text("Close All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCloseAllDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DraggableWorkspaceListItem(
    workspace: WorkspaceManagerViewModel.WorkspaceItem,
    onClose: () -> Unit,
    onSelect: () -> Unit,
    onDragEnd: (fromIndex: Int, toIndex: Int) -> Unit,
    index: Int,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current

    val itemHeight = 80.dp
    val itemHeightPx = with(density) { itemHeight.toPx() }

    Box(
        modifier = Modifier
            .offset { dragOffset }
            .zIndex(if (isDragging) 1f else 0f)
    ) {
        WorkspaceListItem(
            workspace = workspace,
            onClose = onClose,
            onSelect = onSelect,
            isDragging = isDragging,
            onDragStart = {
                isDragging = true
                dragOffset = IntOffset.Zero
            },
            onDrag = { delta ->
                dragOffset = IntOffset(
                    x = 0,
                    y = (dragOffset.y + delta.y).roundToInt()
                )
            },
            onDragEnd = {
                val draggedDistance = dragOffset.y
                val itemsMoved = (draggedDistance / itemHeightPx).roundToInt()
                val newIndex = (index + itemsMoved).coerceIn(0, Int.MAX_VALUE)

                onDragEnd(index, newIndex)

                isDragging = false
                dragOffset = IntOffset.Zero
            }
        )
    }
}

@Composable
private fun WorkspaceListItem(
    workspace: WorkspaceManagerViewModel.WorkspaceItem,
    onClose: () -> Unit,
    onSelect: () -> Unit,
    isDragging: Boolean = false,
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .scale(if (isDragging) 1.05f else 1f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Drag handle
            Icon(
                imageVector = Icons.TwoTone.DragIndicator,
                contentDescription = "Reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDrag = { change, _ ->
                                onDrag(change.position)
                            }
                        )
                    }
            )

            // Workspace type icon
            Icon(
                imageVector = getIconForWorkspaceType(workspace.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            // Title and subtitle
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = workspace.title.asComposable(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = workspace.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Close,
                    contentDescription = "Close workspace",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun getIconForWorkspaceType(type: Workspace.Type): ImageVector {
    return when (type) {
        Workspace.Type.TEMPLATES -> Icons.TwoTone.Workspaces
        Workspace.Type.EXPLORER -> Icons.TwoTone.Folder
        Workspace.Type.SEARCHER -> Icons.TwoTone.Search
        Workspace.Type.EDITOR -> Icons.TwoTone.Edit
    }
}


@Preview2
@Composable
private fun WorkspaceManagerScreenPreview() {
    PreviewWrapper {
        WorkspaceManagerScreen(
            state = WorkspaceManagerViewModel.State(
                workspaces = listOf(
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.TEMPLATES,
                        title = "Templates".toCaString(),
                        subtitle = "Workspace templates"
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.EXPLORER,
                        title = "Explorer".toCaString(),
                        subtitle = "File explorer"
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.SEARCHER,
                        title = "Search".toCaString(),
                        subtitle = "File search"
                    )
                ),
                operationsCount = 3,
                attentionCount = 2
            ),
            onCloseWorkspace = {},
            onReorderWorkspaces = {},
            onSelectWorkspace = {},
            onCreateWorkspace = {},
            onNavigateBack = {},
            onNavigateToSettings = {},
            onToggleButtonFlipped = {},
            onDismissBadgeExplanation = {},
            onDismissButtonBehaviorExplanation = {},
            onCloseAllWorkspaces = {}
        )
    }
}
