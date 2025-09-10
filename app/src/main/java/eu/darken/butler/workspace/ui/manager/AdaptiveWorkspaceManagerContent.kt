package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceBadgeExplanationCard
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceButtonBehaviorCard
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceGridItem
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceListItem
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceStatusCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class LayoutMode {
    LIST,
    GRID
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdaptiveWorkspaceManagerContent(
    state: WorkspaceManagerViewModel.State,
    paddingValues: PaddingValues,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onDismissButtonBehaviorExplanation: () -> Unit,
    onToggleButtonActions: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val screenWidth = maxWidth
        val density = LocalDensity.current
        
        // Determine layout mode based on screen width
        val layoutMode = when {
            screenWidth < 600.dp -> LayoutMode.LIST
            else -> LayoutMode.GRID
        }
        
        // Maximum content width for large screens
        val maxContentWidth = 840.dp
        val contentModifier = if (screenWidth > maxContentWidth) {
            Modifier
                .widthIn(max = maxContentWidth)
                .align(Alignment.TopCenter)
        } else {
            Modifier.fillMaxWidth()
        }
        
        when (layoutMode) {
            LayoutMode.LIST -> {
                ListLayout(
                    modifier = contentModifier,
                    state = state,
                    paddingValues = paddingValues,
                    onCloseWorkspace = onCloseWorkspace,
                    onReorderWorkspaces = onReorderWorkspaces,
                    onSelectWorkspace = onSelectWorkspace,
                    onDismissBadgeExplanation = onDismissBadgeExplanation,
                    onDismissButtonBehaviorExplanation = onDismissButtonBehaviorExplanation,
                    onToggleButtonActions = onToggleButtonActions,
                )
            }
            LayoutMode.GRID -> {
                GridLayout(
                    modifier = contentModifier,
                    state = state,
                    paddingValues = paddingValues,
                    screenWidth = screenWidth,
                    onCloseWorkspace = onCloseWorkspace,
                    onReorderWorkspaces = onReorderWorkspaces,
                    onSelectWorkspace = onSelectWorkspace,
                    onDismissBadgeExplanation = onDismissBadgeExplanation,
                    onDismissButtonBehaviorExplanation = onDismissButtonBehaviorExplanation,
                    onToggleButtonActions = onToggleButtonActions,
                )
            }
        }
    }
}

@Preview2
@Composable
private fun AdaptiveWorkspaceManagerContentListPreview() {
    PreviewWrapper {
        Box(modifier = Modifier.size(400.dp, 800.dp)) {
            AdaptiveWorkspaceManagerContent(
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
                paddingValues = PaddingValues(),
                onCloseWorkspace = {},
                onReorderWorkspaces = {},
                onSelectWorkspace = {},
                onDismissBadgeExplanation = {},
                onDismissButtonBehaviorExplanation = {},
                onToggleButtonActions = {}
            )
        }
    }
}

@Preview2
@Composable
private fun AdaptiveWorkspaceManagerContentGridPreview() {
    PreviewWrapper {
        Box(modifier = Modifier.size(800.dp, 600.dp)) {
            AdaptiveWorkspaceManagerContent(
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
                            subtitle = "File explorer for browsing"
                        ),
                        WorkspaceManagerViewModel.WorkspaceItem(
                            id = Workspace.Id(),
                            type = Workspace.Type.SEARCHER,
                            title = "Search".toCaString(),
                            subtitle = "Search for files and folders"
                        ),
                        WorkspaceManagerViewModel.WorkspaceItem(
                            id = Workspace.Id(),
                            type = Workspace.Type.EDITOR,
                            title = "Editor".toCaString(),
                            subtitle = "Text editor"
                        )
                    ),
                    operationsCount = 2,
                    attentionCount = 1
                ),
                paddingValues = PaddingValues(),
                onCloseWorkspace = {},
                onReorderWorkspaces = {},
                onSelectWorkspace = {},
                onDismissBadgeExplanation = {},
                onDismissButtonBehaviorExplanation = {},
                onToggleButtonActions = {}
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListLayout(
    modifier: Modifier = Modifier,
    state: WorkspaceManagerViewModel.State,
    paddingValues: PaddingValues,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onDismissButtonBehaviorExplanation: () -> Unit,
    onToggleButtonActions: () -> Unit,
) {
    var localWorkspaceItems by remember { mutableStateOf(state.workspaces) }
    var isDragging by remember { mutableStateOf(false) }
    
    if (!isDragging) {
        log("WorkspaceManager") { "Updating local workspace items: ${state.workspaces}" }
        localWorkspaceItems = state.workspaces
    }
    
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState
    ) { from, to ->
        log("WorkspaceManager") { "Reorder from ${from.index} to ${to.index}" }
        
        val fromKey = from.key as? LazyColumnItemKey
        val toKey = to.key as? LazyColumnItemKey
        
        when {
            fromKey is LazyColumnItemKey.Workspace && toKey is LazyColumnItemKey.Workspace -> {
                val fromWorkspaceIndex = localWorkspaceItems.indexOfFirst { it.id == fromKey.id }
                val toWorkspaceIndex = localWorkspaceItems.indexOfFirst { it.id == toKey.id }
                
                if (fromWorkspaceIndex != -1 && toWorkspaceIndex != -1) {
                    val newList = localWorkspaceItems.toMutableList()
                    val movedItem = newList.removeAt(fromWorkspaceIndex)
                    newList.add(toWorkspaceIndex, movedItem)
                    localWorkspaceItems = newList
                }
            }
        }
    }
    
    LazyColumn(
        modifier = modifier,
        state = lazyListState,
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status card
        if (state.workspaceCount > 0) {
            item(key = LazyColumnItemKey.StatusCard) {
                WorkspaceStatusCard(
                    workspaceCount = state.workspaceCount,
                    operationsCount = state.operationsCount,
                    attentionCount = state.attentionCount
                )
            }
        }
        
        if (state.workspaces.isEmpty()) {
            item(key = LazyColumnItemKey.Custom("empty_state", "")) {
                EmptyStateContent()
            }
        } else {
            items(
                items = localWorkspaceItems,
                key = { workspace -> LazyColumnItemKey.Workspace.Standard(workspace.id) }
            ) { workspace ->
                ReorderableItem(
                    reorderableLazyListState,
                    key = LazyColumnItemKey.Workspace.Standard(workspace.id)
                ) { itemIsDragging ->
                    WorkspaceListItem(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .animateItem(),
                        reorderableScope = this@ReorderableItem,
                        workspace = workspace,
                        onClose = { onCloseWorkspace(workspace.id) },
                        onSelect = { onSelectWorkspace(workspace.id) },
                        isDragging = itemIsDragging,
                        onDragStarted = { isDragging = true },
                        onDragStopped = {
                            isDragging = false
                            onReorderWorkspaces(localWorkspaceItems.map { it.id })
                        }
                    )
                }
            }
        }
        
        // Explanation cards
        if (state.showButtonBehaviorExplanation) {
            item(key = LazyColumnItemKey.Explanation.ButtonBehaviorExplanation) {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    WorkspaceButtonBehaviorCard(
                        isButtonFlipped = state.isButtonActionsFlipped,
                        onToggleFlipped = { onToggleButtonActions() },
                        onDismiss = onDismissButtonBehaviorExplanation
                    )
                }
            }
        }
        
        if (state.showBadgeExplanation) {
            item(key = LazyColumnItemKey.Explanation.BadgeExplanation) {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    WorkspaceBadgeExplanationCard(
                        onDismiss = onDismissBadgeExplanation
                    )
                }
            }
        }
    }
}

@Composable
private fun GridLayout(
    modifier: Modifier = Modifier,
    state: WorkspaceManagerViewModel.State,
    paddingValues: PaddingValues,
    screenWidth: Dp,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onDismissButtonBehaviorExplanation: () -> Unit,
    onToggleButtonActions: () -> Unit,
) {
    // Calculate number of columns based on screen width
    val columns = when {
        screenWidth < 840.dp -> 2
        else -> 3
    }
    
    LazyVerticalGrid(
        modifier = modifier,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding(),
            start = 16.dp,
            end = 16.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status card spanning full width
        if (state.workspaceCount > 0) {
            item(
                key = LazyColumnItemKey.StatusCard,
                span = { GridItemSpan(maxLineSpan) }
            ) {
                WorkspaceStatusCard(
                    workspaceCount = state.workspaceCount,
                    operationsCount = state.operationsCount,
                    attentionCount = state.attentionCount
                )
            }
        }
        
        if (state.workspaces.isEmpty()) {
            item(
                key = LazyColumnItemKey.Custom("empty_state", ""),
                span = { GridItemSpan(maxLineSpan) }
            ) {
                EmptyStateContent()
            }
        } else {
            items(
                items = state.workspaces,
                key = { workspace -> LazyColumnItemKey.Workspace.Standard(workspace.id) },
                span = { GridItemSpan(1) }
            ) { workspace ->
                WorkspaceGridItem(
                    modifier = Modifier,
                    workspace = workspace,
                    onClose = { onCloseWorkspace(workspace.id) },
                    onSelect = { onSelectWorkspace(workspace.id) }
                )
            }
        }
        
        // Explanation cards spanning full width
        if (state.showButtonBehaviorExplanation) {
            item(
                key = LazyColumnItemKey.Explanation.ButtonBehaviorExplanation,
                span = { GridItemSpan(maxLineSpan) }
            ) {
                WorkspaceButtonBehaviorCard(
                    isButtonFlipped = state.isButtonActionsFlipped,
                    onToggleFlipped = { onToggleButtonActions() },
                    onDismiss = onDismissButtonBehaviorExplanation
                )
            }
        }
        
        if (state.showBadgeExplanation) {
            item(
                key = LazyColumnItemKey.Explanation.BadgeExplanation,
                span = { GridItemSpan(maxLineSpan) }
            ) {
                WorkspaceBadgeExplanationCard(
                    onDismiss = onDismissBadgeExplanation
                )
            }
        }
    }
}

@Composable
private fun EmptyStateContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .padding(vertical = 48.dp),
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