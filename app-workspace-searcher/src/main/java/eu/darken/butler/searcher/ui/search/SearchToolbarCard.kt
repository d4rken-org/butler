package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun SearchToolbarCard(
    modifier: Modifier = Modifier,
    state: SearcherWorkspaceViewModel.State,
    design: WorkspaceDesign,
    onUpdateQuery: (TextFieldValue) -> Unit,
    onUpdateSearchPath: (APath<*>) -> Unit,
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
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                        workspaceActionHandler = workspaceActionHandler,
                    )
                }
            }

            SearchPathBar(
                path = state.searchPath,
                onPathChange = onUpdateSearchPath,
                onPerformSearch = onPerformSearch,
                isSearching = state.isSearching,
                onOpenPathPicker = onOpenPathPicker,
            )

            SearchOptionsRow(
                caseSensitive = state.caseSensitive,
                wholeWord = state.wholeWord,
                useRegex = state.useRegex,
                onToggleCaseSensitive = onToggleCaseSensitive,
                onToggleWholeWord = onToggleWholeWord,
                onToggleRegex = onToggleRegex,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview2
@Composable
private fun SearchToolbarCardPreview() {
    PreviewWrapper {
        SearchToolbarCard(
            state = SearcherWorkspaceViewModel.State(
                id = Workspace.Id(),
                searchPath = LocalPath.build("/storage/emulated/0/Documents"),
                searchQuery = TextFieldValue("example search"),
                caseSensitive = false,
                wholeWord = false,
                useRegex = false
            ),
            design = WorkspaceDesign(),
            onUpdateQuery = {},
            onUpdateSearchPath = {},
            onPerformSearch = {},
            onCancelSearch = {},
            onToggleCaseSensitive = {},
            onToggleWholeWord = {},
            onToggleRegex = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}