package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceBadgeExplanationCard
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceGridItem
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceStatusCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@Composable
fun WorkspaceManagerGridLayout(
    modifier: Modifier = Modifier,
    state: WorkspaceManagerViewModel.State,
    paddingValues: PaddingValues,
    screenWidth: Dp,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onPauseWorkspace: (Workspace.Id) -> Unit,
    onResumeWorkspace: (Workspace.Id) -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onStartSelection: (Workspace.Id) -> Unit = {},
    onToggleSelection: (Workspace.Id) -> Unit = {},
    onRenameWorkspace: (Workspace.Id) -> Unit = {},
    onTabsClick: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onOperationsFilterClick: () -> Unit = {},
    onAttentionFilterClick: () -> Unit = {},
) {
    val tag = logTag("Workspace", "Manager", "GridLayout")
    var localWorkspaceItems by remember { mutableStateOf(state.filteredWorkspaces) }
    var isDragging by remember { mutableStateOf(false) }

    if (!isDragging) {
        log(tag) { "Updating local workspace items: ${state.filteredWorkspaces}" }
        localWorkspaceItems = state.filteredWorkspaces
    }

    // Calculate number of columns based on screen width
    val columns = when {
        screenWidth < 840.dp -> 2
        else -> 3
    }

    // Calculate span for explanation cards - use 2 columns on large screens, full width otherwise
    val explanationSpan = if (columns == 3) 2 else columns

    val lazyGridState = rememberLazyGridState()
    val reorderableLazyGridState = rememberReorderableLazyGridState(
        lazyGridState = lazyGridState
    ) { from, to ->
        log(tag) { "Reorder from ${from.index} to ${to.index}" }

        val fromKey = from.key as? WorkspaceManagerColumnItemKey
        val toKey = to.key as? WorkspaceManagerColumnItemKey

        when {
            fromKey is WorkspaceManagerColumnItemKey.Workspace && toKey is WorkspaceManagerColumnItemKey.Workspace -> {
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

    LazyVerticalGrid(
        modifier = modifier,
        state = lazyGridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding(),
            start = 24.dp,
            end = 24.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status card spanning full width
        if (state.workspaceCount > 0) {
            item(
                key = WorkspaceManagerColumnItemKey.StatusCard,
                span = { GridItemSpan(maxLineSpan) }
            ) {
                WorkspaceStatusCard(
                    workspaceCount = state.workspaceCount,
                    operationsCount = state.operationsCount,
                    attentionCount = state.attentionCount,
                    isOperationsFilterActive = state.filterOperations,
                    isAttentionFilterActive = state.filterAttention,
                    selectedCount = state.selectedIds?.size,
                    onTabsClick = onTabsClick,
                    onClearSelection = onClearSelection,
                    onOperationsClick = onOperationsFilterClick,
                    onAttentionClick = onAttentionFilterClick,
                )
            }
        }

        if (state.workspaces.isEmpty()) {
            item(
                key = WorkspaceManagerColumnItemKey.Custom("empty_state", ""),
                span = { GridItemSpan(maxLineSpan) }
            ) {
                WorkspaceManagerEmptyState()
            }
        } else {
            items(
                items = localWorkspaceItems,
                key = { workspace -> WorkspaceManagerColumnItemKey.Workspace.Standard(workspace.id) },
                span = { GridItemSpan(1) }
            ) { workspace ->
                ReorderableItem(
                    reorderableLazyGridState,
                    key = WorkspaceManagerColumnItemKey.Workspace.Standard(workspace.id)
                ) { itemIsDragging ->
                    WorkspaceGridItem(
                        modifier = Modifier.animateItem(),
                        reorderableScope = this@ReorderableItem,
                        workspace = workspace,
                        onClose = { onCloseWorkspace(workspace.id) },
                        onSelect = { onSelectWorkspace(workspace.id) },
                        onStartSelection = { onStartSelection(workspace.id) },
                        onToggleSelection = { onToggleSelection(workspace.id) },
                        onRename = { onRenameWorkspace(workspace.id) },
                        onPause = { onPauseWorkspace(workspace.id) },
                        onResume = { onResumeWorkspace(workspace.id) },
                        isDragging = itemIsDragging,
                        livePreview = state.useLivePreview,
                        onDragStarted = { isDragging = true },
                        onDragStopped = {
                            isDragging = false
                            onReorderWorkspaces(localWorkspaceItems.map { it.id })
                        },
                        isFocused = workspace.isFocused,
                        isVisibleInPane = workspace.isVisibleInPane,
                        isSelectionActive = state.isSelectionActive,
                        isChecked = state.selectedIds?.contains(workspace.id) == true,
                        currentPaneCount = state.currentPaneCount,
                    )
                }
            }
        }

        // Explanation cards with responsive column spans
        if (state.showBadgeExplanation && !state.isSelectionActive) {
            item(
                key = WorkspaceManagerColumnItemKey.Explanation.BadgeExplanation,
                span = { GridItemSpan(explanationSpan) }
            ) {
                WorkspaceBadgeExplanationCard(
                    onDismiss = onDismissBadgeExplanation
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerGridLayoutPreview() {
    val templatesId = Workspace.Id()
    val explorerId = Workspace.Id()
    Box(modifier = Modifier.size(600.dp, 500.dp)) {
        WorkspaceManagerGridLayout(
            state = WorkspaceManagerViewModel.State(
                workspaces = listOf(
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = templatesId,
                        topId = templatesId,
                        type = Workspace.Type.TEMPLATES,
                        title = "New".toCaString(),
                        autoTitle = "New".toCaString(),
                        subtitle = null,
                        isFocused = true,
                        isVisibleInPane = true,
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = explorerId,
                        topId = explorerId,
                        type = Workspace.Type.EXPLORER,
                        title = "/storage/emulated/0/Download".toCaString(),
                        autoTitle = "/storage/emulated/0/Download".toCaString(),
                        subtitle = null,
                    )
                ),
                operationsCount = 2,
                attentionCount = 1,
                currentPaneCount = 1,
            ),
            paddingValues = PaddingValues(),
            screenWidth = 600.dp,
            onCloseWorkspace = {},
            onReorderWorkspaces = {},
            onSelectWorkspace = {},
            onPauseWorkspace = {},
            onResumeWorkspace = {},
            onDismissBadgeExplanation = {}
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerGridLayoutTabletPreview() {
    val templatesId = Workspace.Id()
    val explorerId = Workspace.Id()
    val searcherId = Workspace.Id()
    val editorId = Workspace.Id()
    Box(modifier = Modifier.size(900.dp, 600.dp)) {
        WorkspaceManagerGridLayout(
            state = WorkspaceManagerViewModel.State(
                workspaces = listOf(
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = templatesId,
                        topId = templatesId,
                        type = Workspace.Type.TEMPLATES,
                        title = "New".toCaString(),
                        autoTitle = "New".toCaString(),
                        subtitle = null,
                        isFocused = true,
                        isVisibleInPane = true,
                        paneNumber = 0,
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = explorerId,
                        topId = explorerId,
                        type = Workspace.Type.EXPLORER,
                        title = "Trash".toCaString(),
                        autoTitle = "Trash".toCaString(),
                        subtitle = "Recover deleted files".toCaString(),
                        isVisibleInPane = true,
                        paneNumber = 1,
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = searcherId,
                        topId = searcherId,
                        type = Workspace.Type.SEARCHER,
                        title = "*.log".toCaString(),
                        autoTitle = "*.log".toCaString(),
                        subtitle = "Device storage".toCaString(),
                        paneNumber = null,
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = editorId,
                        topId = editorId,
                        type = Workspace.Type.EDITOR,
                        title = "build.gradle.kts".toCaString(),
                        autoTitle = "build.gradle.kts".toCaString(),
                        subtitle = "/storage/emulated/0/Projects/butler".toCaString(),
                        paneNumber = null,
                    )
                ),
                operationsCount = 2,
                attentionCount = 1,
                currentPaneCount = 2,
            ),
            paddingValues = PaddingValues(),
            screenWidth = 900.dp,
            onCloseWorkspace = {},
            onReorderWorkspaces = {},
            onSelectWorkspace = {},
            onPauseWorkspace = {},
            onResumeWorkspace = {},
            onDismissBadgeExplanation = {}
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerGridLayoutEmptyPreview() {
    Box(modifier = Modifier.size(600.dp, 400.dp)) {
        WorkspaceManagerGridLayout(
            state = WorkspaceManagerViewModel.State(
                workspaces = emptyList(),
                operationsCount = 0,
                attentionCount = 0
            ),
            paddingValues = PaddingValues(),
            screenWidth = 600.dp,
            onCloseWorkspace = {},
            onReorderWorkspaces = {},
            onSelectWorkspace = {},
            onPauseWorkspace = {},
            onResumeWorkspace = {},
            onDismissBadgeExplanation = {}
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerGridLayoutWithExplanationsPreview() {
    val explorerId = Workspace.Id()
    val searcherId = Workspace.Id()
    Box(modifier = Modifier.size(900.dp, 700.dp)) {
        WorkspaceManagerGridLayout(
            state = WorkspaceManagerViewModel.State(
                workspaces = listOf(
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = explorerId,
                        topId = explorerId,
                        type = Workspace.Type.EXPLORER,
                        title = "/storage/emulated/0/Download".toCaString(),
                        autoTitle = "/storage/emulated/0/Download".toCaString(),
                        subtitle = null,
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = searcherId,
                        topId = searcherId,
                        type = Workspace.Type.SEARCHER,
                        title = "report".toCaString(),
                        autoTitle = "report".toCaString(),
                        subtitle = "SD card".toCaString(),
                    )
                ),
                operationsCount = 2,
                attentionCount = 1,
                showBadgeExplanation = true
            ),
            paddingValues = PaddingValues(),
            screenWidth = 900.dp,
            onCloseWorkspace = {},
            onReorderWorkspaces = {},
            onSelectWorkspace = {},
            onPauseWorkspace = {},
            onResumeWorkspace = {},
            onDismissBadgeExplanation = {}
        )
    }
}