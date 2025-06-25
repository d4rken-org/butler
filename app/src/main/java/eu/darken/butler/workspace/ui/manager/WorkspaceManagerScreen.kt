package eu.darken.butler.workspace.ui.manager

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
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
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceManagerScreen(
    state: WorkspaceManagerViewModel.State,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onCreateWorkspace: (Workspace.Type) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val density = LocalDensity.current
    val isInPreview = LocalInspectionMode.current

    var workspaceItems by remember { mutableStateOf(state.workspaces) }
    LaunchedEffect(state.workspaces) {
        workspaceItems = state.workspaces
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val screenWidth = with(density) { maxWidth.toPx() }
        val screenHeight = with(density) { maxHeight.toPx() }
        val statusBarHeight = WindowInsets.statusBars.getTop(density).toFloat()

        // Position of the close button in header (top-right corner with padding)
        val buttonX = screenWidth - with(density) { (16 + 20).dp.toPx() } // 16dp padding + 20dp button center
        val buttonY = statusBarHeight + with(density) { (16 + 20).dp.toPx() } // status bar + 16dp padding + 20dp button center

        // Calculate maximum radius needed to cover the entire screen from button position
        val maxRadius = sqrt((buttonX * buttonX) + ((screenHeight - buttonY) * (screenHeight - buttonY)))
            .coerceAtLeast(sqrt(((screenWidth - buttonX) * (screenWidth - buttonX)) + (buttonY * buttonY)))

        val revealRadius = remember { Animatable(0f) }

        LaunchedEffect(Unit) {
            if (isInPreview) {
                // Skip animation in preview mode - start fully revealed
                revealRadius.snapTo(maxRadius)
            } else {
                revealRadius.animateTo(
                    targetValue = maxRadius,
                    animationSpec = tween(durationMillis = 400, easing = EaseOutCubic)
                )
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Header with title, icon, and close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
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
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    
                    // Close button
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier.clickable { onNavigateBack() }
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // Status card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_manager_status_count, state.workspaceCount),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        
                        // Add Workspace Button with dropdown
                        var showDropdown by remember { mutableStateOf(false) }
                        Box {
                            FilledTonalButton(
                                onClick = { showDropdown = true }
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Add",
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                            
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
                            }
                        }
                    }
                }

                // Workspace list or empty state
                if (workspaceItems.isEmpty()) {
                    // Empty state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
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
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = workspaceItems,
                            key = { _, workspace -> workspace.id.toString() }
                        ) { index, workspace ->
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
                }
            }
        }

        // Circular reveal mask
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            if (revealRadius.value < maxRadius) {
                drawRect(
                    color = Color.Black,
                    size = size
                )
                drawCircle(
                    color = Color.Transparent,
                    radius = revealRadius.value,
                    center = Offset(buttonX, buttonY),
                    blendMode = BlendMode.Clear
                )
            }
        }


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

            // Workspace type icon with background
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Circular background
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            shape = CircleShape
                        )
                )

                Icon(
                    imageVector = getIconForWorkspaceType(workspace.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

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
                )
            ),
            onCloseWorkspace = {},
            onReorderWorkspaces = {},
            onSelectWorkspace = {},
            onCreateWorkspace = {},
            onNavigateBack = {}
        )
    }
}
