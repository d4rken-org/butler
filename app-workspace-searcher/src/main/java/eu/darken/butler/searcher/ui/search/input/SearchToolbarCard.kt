package eu.darken.butler.searcher.ui.search.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun SearchToolbarCard(
    workspaceId: Workspace.Id,
    modifier: Modifier = Modifier,
    state: SearcherWorkspaceViewModel.State,
    design: WorkspaceDesign,
    collapsedFraction: Float = 0f,
    onUpdateQuery: (TextFieldValue) -> Unit,
    onRemoveSearchPath: (SearchTarget) -> Unit,
    onTogglePathEnabled: (SearchTarget) -> Unit,
    onPerformSearch: () -> Unit,
    onExplicitSearch: () -> Unit = onPerformSearch,
    onCancelSearch: () -> Unit,
    onToggleCaseSensitive: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onToggleRegex: () -> Unit,
    onOpenPathPicker: (() -> Unit)? = null,
    workspaceButtonState: WorkspaceButtonViewModel.State? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
) {
    val isCollapsed = collapsedFraction > 0.5f
    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) 8.dp else 16.dp,
        label = "cardPadding"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = cardPadding,
                    end = cardPadding,
                    top = cardPadding,
                    bottom = if (isCollapsed) cardPadding else 8.dp // to deal with FlowRow too much build in padding
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isCollapsed) {
                // Collapsed state - compact display
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        modifier = Modifier.size(24.dp),
                        imageVector = Icons.TwoTone.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = state.searchQuery.text.ifBlank { "Search" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.searchQuery.text.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (design.isSingle) {
                        WorkspaceButton(
                            modifier = Modifier.size(32.dp),
                            state = workspaceButtonState,
                            workspaceActionHandler = workspaceActionHandler,
                        )
                    }
                }
            } else {
                // Expanded state - full interactive card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    SearchBar(
                        query = state.searchQuery,
                        onQueryChange = onUpdateQuery,
                        onSearch = onExplicitSearch,
                        isSearching = state.isSearching,
                        onCancel = if (state.isSearching) onCancelSearch else null,
                        modifier = Modifier.weight(1f)
                    )

                    if (design.isSingle) {
                        Spacer(modifier = Modifier.width(8.dp))

                        WorkspaceButton(
                            state = workspaceButtonState,
                            currentWorkspaceId = workspaceId,
                            workspaceActionHandler = workspaceActionHandler,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !isCollapsed,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy((-4).dp)
                    ) {
                        SearchOptionsRow(
                            caseSensitive = state.caseSensitive,
                            wholeWord = state.wholeWord,
                            useRegex = state.useRegex,
                            onToggleCaseSensitive = onToggleCaseSensitive,
                            onToggleWholeWord = onToggleWholeWord,
                            onToggleRegex = onToggleRegex,
                            modifier = Modifier.fillMaxWidth()
                        )

                        MultiPathChipBar(
                            paths = state.searchTargets,
                            onPathRemove = onRemoveSearchPath,
                            onPathToggle = onTogglePathEnabled,
                            onAddPathClick = { onOpenPathPicker?.invoke() },
                            isSearching = state.isSearching,
                        )
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun SearchToolbarCardPreview() {
    PreviewWrapper {
        SearchToolbarCard(
            workspaceId = Workspace.Id(),
            state = SearcherWorkspaceViewModel.State(
                id = Workspace.Id(),
                searchTargets = listOf(
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download"))
                ),
                searchQuery = TextFieldValue("example search"),
                caseSensitive = false,
                wholeWord = false,
                useRegex = false
            ),
            design = WorkspaceDesign(),
            onUpdateQuery = {},
            onRemoveSearchPath = {},
            onTogglePathEnabled = {},
            onPerformSearch = {},
            onCancelSearch = {},
            onToggleCaseSensitive = {},
            onToggleWholeWord = {},
            onToggleRegex = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview2
@Composable
private fun SearchToolbarCardCollapsedPreview() {
    PreviewWrapper {
        SearchToolbarCard(
            workspaceId = Workspace.Id(),
            state = SearcherWorkspaceViewModel.State(
                id = Workspace.Id(),
                searchTargets = listOf(
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download"))
                ),
                searchQuery = TextFieldValue("example search"),
                caseSensitive = false,
                wholeWord = false,
                useRegex = false
            ),
            design = WorkspaceDesign(),
            onUpdateQuery = {},
            onRemoveSearchPath = {},
            onTogglePathEnabled = {},
            onPerformSearch = {},
            onCancelSearch = {},
            onToggleCaseSensitive = {},
            onToggleWholeWord = {},
            onToggleRegex = {},
            modifier = Modifier.padding(16.dp),
            collapsedFraction = 1f,
        )
    }
}