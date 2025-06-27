package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import eu.darken.butler.searcher.ui.search.rows.FileRowData
import eu.darken.butler.searcher.ui.search.rows.SmartFileRow
import eu.darken.butler.searcher.ui.search.rows.FileType as UIFileType
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LocalPath
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
            onUpdateSearchPath = vm::updateSearchPath,
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
    onUpdateSearchPath: (APath) -> Unit = {},
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
        // Search inputs with spacer for floating button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchBar(
                    query = state.searchQuery,
                    onQueryChange = onUpdateQuery,
                    onSearch = onPerformSearch,
                    isSearching = state.isSearching,
                    onCancel = if (state.isSearching) onCancelSearch else null
                )
                
                SearchPathBar(
                    path = state.searchPath,
                    onPathChange = onUpdateSearchPath,
                    isSearching = state.isSearching
                )
            }
            
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
fun SearchPathBar(
    path: APath,
    onPathChange: (APath) -> Unit,
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    var pathText by remember(path) { mutableStateOf(path.path) }
    var showPathPicker by remember { mutableStateOf(false) }
    
    OutlinedTextField(
        value = pathText,
        onValueChange = { newPath ->
            pathText = newPath
            try {
                onPathChange(LocalPath.build(newPath))
            } catch (e: Exception) {
                // Invalid path, don't update
            }
        },
        placeholder = { Text(text = "Search path (e.g., /sdcard)") },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Folder, contentDescription = "Search Path")
        },
        trailingIcon = {
            IconButton(
                onClick = { showPathPicker = true }
            ) {
                Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Browse")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        singleLine = true,
        enabled = !isSearching,
        modifier = modifier.fillMaxWidth()
    )
    
    if (showPathPicker) {
        PathPickerDialog(
            onPathSelected = { selectedPath ->
                pathText = selectedPath.path
                onPathChange(selectedPath)
                showPathPicker = false
            },
            onDismiss = { showPathPicker = false }
        )
    }
}

@Composable
fun PathPickerDialog(
    onPathSelected: (APath) -> Unit,
    onDismiss: () -> Unit
) {
    val commonPaths = listOf(
        "/storage/emulated/0/Android/data/eu.darken.butler" to "Butler App Data (Default)",
        "/" to "Root",
        "/sdcard" to "Internal Storage",
        "/storage/emulated/0" to "Internal Storage (Alt)",
        "/storage/emulated/0/Download" to "Downloads",
        "/storage/emulated/0/Pictures" to "Pictures",
        "/storage/emulated/0/Documents" to "Documents",
        "/storage/emulated/0/Music" to "Music",
        "/storage/emulated/0/Movies" to "Movies",
        "/system" to "System",
        "/data" to "Data",
        "/cache" to "Cache"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Search Path") },
        text = {
            LazyColumn {
                items(commonPaths) { (path, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                onPathSelected(LocalPath.build(path))
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    val fileRowData = FileRowData(
        name = result.name,
        path = result.path.path,
        fileType = mapFileType(result.fileType),
        size = result.size,
        modifiedAt = result.modifiedAt,
        metadata = extractFileMetadata(result)
    )
    
    SmartFileRow(
        data = fileRowData,
        onClick = onClick
    )
}

/**
 * Map from domain FileType to UI FileType
 */
private fun mapFileType(fileType: FileType): UIFileType {
    return when (fileType) {
        FileType.FILE -> UIFileType.FILE
        FileType.DIRECTORY -> UIFileType.DIRECTORY
        FileType.SYMBOLIC_LINK -> UIFileType.SYMBOLIC_LINK
        else -> UIFileType.UNKNOWN
    }
}

/**
 * Extract metadata from search result for enhanced display
 */
private fun extractFileMetadata(result: SearchResult): Map<String, String> {
    // TODO: In the future, this could extract metadata like:
    // - Image dimensions for image files
    // - Duration for video/audio files  
    // - Package name/version for APK files
    // - etc.
    return emptyMap()
}

@Preview2
@Composable
private fun SearchPagePreview() {
    PreviewWrapper {
        SearcherWorkspacePage(
            state = SearcherWorkspaceViewModel.State(
                id = Workspace.Id(),
                searchPath = LocalPath.build("/storage/emulated/0/Android/data/eu.darken.butler")
            )
        )
    }
}