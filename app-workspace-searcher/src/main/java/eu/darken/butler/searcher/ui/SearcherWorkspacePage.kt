package eu.darken.butler.searcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.formatFileSize
import eu.darken.butler.common.compose.toRelativeTime
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.searcher.core.SearchRepository
import eu.darken.butler.searcher.core.SearchResult
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.WorkspaceButtonSpacer
import kotlinx.coroutines.delay


@Composable
fun SearcherWorkspacePageHost(
    id: Workspace.Id,
    vm: SearcherWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SearcherWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }
    state?.let { state ->
        SearcherWorkspacePage(
            state = state,
            onUpdateQuery = vm::updateSearchQuery,
            onPerformSearch = vm::performSearch,
            onCancelSearch = vm::cancelSearch,
            onResultClick = vm::onSearchResultClick,
            onClearHistory = vm::clearSearchHistory,
            onHistoryItemClick = { item ->
                vm.updateSearchQuery(item.query)
                vm.performSearch()
            }
        )
    }
}

@Composable
fun SearcherWorkspacePage(
    state: SearcherWorkspaceViewModel.State,
    onUpdateQuery: (String) -> Unit = {},
    onPerformSearch: () -> Unit = {},
    onCancelSearch: () -> Unit = {},
    onResultClick: (SearchResult) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onHistoryItemClick: (SearchRepository.SearchHistoryItem) -> Unit = {},
) {
    var searchDebounce by remember { mutableStateOf(false) }
    
    // Debounce search input
    LaunchedEffect(state.searchQuery) {
        if (state.searchQuery.isNotBlank()) {
            searchDebounce = true
            delay(500) // Wait 500ms after user stops typing
            searchDebounce = false
            onPerformSearch()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar with spacer for floating button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            SearchBar(
                query = state.searchQuery,
                onQueryChange = onUpdateQuery,
                onSearch = onPerformSearch,
                isSearching = state.isSearching,
                onCancel = if (state.isSearching) onCancelSearch else null,
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp)
            )
            
            WorkspaceButtonSpacer()
        }
        
        // Search progress
        if (state.isSearching || searchDebounce) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
            
            state.searchState.progress?.let { progress ->
                Text(
                    text = "Searching: ${progress.currentPath.name} (${progress.itemsScanned} scanned, ${progress.resultsFound} found)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        when {
            state.searchQuery.isBlank() && state.searchHistory.isNotEmpty() -> {
                // Show search history
                Text(
                    text = "Recent searches",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(state.searchHistory) { historyItem ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onHistoryItemClick(historyItem) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = historyItem.query,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
            
            state.searchQuery.isBlank() -> {
                Text(
                    text = "Search files and folders",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                )
            }
            
            state.searchState.error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Search error",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = state.searchState.error.message ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            !state.isSearching && state.searchState.results.isEmpty() -> {
                Text(
                    text = "No results found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                )
            }

            else -> {
                Text(
                    text = "${state.searchState.results.size} results",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(state.searchState.results) { result ->
                        SearchResultRow(
                            result = result,
                            onClick = { onResultClick(result) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(text = "Search files and folders") },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
        },
        trailingIcon = {
            when {
                isSearching && onCancel != null -> {
                    IconButton(onClick = onCancel) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
                query.isNotEmpty() -> {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (result.fileType) {
                FileType.DIRECTORY -> Icons.Default.Folder
                FileType.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
                FileType.SYMBOLIC_LINK -> Icons.Default.FolderOpen
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            }
            
            val tint = when (result.fileType) {
                FileType.DIRECTORY -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.secondary
            }
            
            Icon(
                imageVector = icon,
                contentDescription = result.fileType.name,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = result.path.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val details = buildString {
                    result.size?.let { append(formatFileSize(it)) }
                    result.modifiedAt?.let {
                        if (isNotEmpty()) append(" • ")
                        append(it.toRelativeTime())
                    }
                }
                
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun SearchPagePreview() {
    PreviewWrapper {
        SearcherWorkspacePage(
            state = SearcherWorkspaceViewModel.State(
                id = Workspace.Id(),
                searchPath = eu.darken.butler.common.files.local.LocalPath.build("/")
            )
        )
    }
}