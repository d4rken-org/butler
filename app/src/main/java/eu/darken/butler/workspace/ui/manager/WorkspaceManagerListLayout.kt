package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceBadgeExplanationCard
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceButtonBehaviorCard
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceListItem
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceStatusCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceManagerListLayout(
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

    LazyColumn(
        modifier = modifier,
        state = lazyListState,
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status card
        if (state.workspaceCount > 0) {
            item(key = WorkspaceManagerColumnItemKey.StatusCard) {
                WorkspaceStatusCard(
                    workspaceCount = state.workspaceCount,
                    operationsCount = state.operationsCount,
                    attentionCount = state.attentionCount
                )
            }
        }

        if (state.workspaces.isEmpty()) {
            item(key = WorkspaceManagerColumnItemKey.Custom("empty_state", "")) {
                WorkspaceManagerEmptyState()
            }
        } else {
            items(
                items = localWorkspaceItems,
                key = { workspace -> WorkspaceManagerColumnItemKey.Workspace.Standard(workspace.id) }
            ) { workspace ->
                ReorderableItem(
                    reorderableLazyListState,
                    key = WorkspaceManagerColumnItemKey.Workspace.Standard(workspace.id)
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
            item(key = WorkspaceManagerColumnItemKey.Explanation.ButtonBehaviorExplanation) {
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
            item(key = WorkspaceManagerColumnItemKey.Explanation.BadgeExplanation) {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    WorkspaceBadgeExplanationCard(
                        onDismiss = onDismissBadgeExplanation
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspaceManagerListLayoutPreview() {
    PreviewWrapper {
        WorkspaceManagerListLayout(
            state = WorkspaceManagerViewModel.State(
                workspaces = listOf(
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.TEMPLATES,
                        title = "Templates".toCaString(),
                        subtitle = "Workspace templates".toCaString()
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.EXPLORER,
                        title = "Explorer".toCaString(),
                        subtitle = "File explorer".toCaString()
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.SEARCHER,
                        title = "Search".toCaString(),
                        subtitle = "File search".toCaString()
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

@Preview2
@Composable
private fun WorkspaceManagerListLayoutEmptyPreview() {
    PreviewWrapper {
        WorkspaceManagerListLayout(
            state = WorkspaceManagerViewModel.State(
                workspaces = emptyList(),
                operationsCount = 0,
                attentionCount = 0
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

@Preview2
@Composable
private fun WorkspaceManagerListLayoutWithExplanationsPreview() {
    PreviewWrapper {
        WorkspaceManagerListLayout(
            state = WorkspaceManagerViewModel.State(
                workspaces = listOf(
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.EXPLORER,
                        title = "Explorer".toCaString(),
                        subtitle = "File explorer".toCaString()
                    )
                ),
                operationsCount = 1,
                attentionCount = 1,
                showBadgeExplanation = true,
                showButtonBehaviorExplanation = true,
                isButtonActionsFlipped = false
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