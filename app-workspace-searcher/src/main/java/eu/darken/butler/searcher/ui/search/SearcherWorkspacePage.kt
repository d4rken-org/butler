package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.twotone.Clear
import androidx.compose.material.icons.twotone.ClearAll
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.TextFormat
import androidx.compose.material.icons.twotone.FormatQuote
import androidx.compose.material.icons.automirrored.twotone.WrapText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.ui.SwipeToDismissItem
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchEngine
import eu.darken.butler.searcher.core.SearchHistory
import eu.darken.butler.searcher.core.SearchResult
import eu.darken.butler.searcher.ui.search.rows.FileRowData
import eu.darken.butler.searcher.ui.search.rows.SmartFileRow
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.delay
import eu.darken.butler.searcher.ui.search.rows.FileType as UIFileType

@Composable
fun SearcherWorkspacePage(
    design: WorkspaceDesign = WorkspaceDesign(),
    state: SearcherWorkspaceViewModel.State,
    onUpdateQuery: (TextFieldValue) -> Unit = {},
    onUpdateSearchPath: (APath) -> Unit = {},
    onPerformSearch: () -> Unit = {},
    onCancelSearch: () -> Unit = {},
    onClearResults: () -> Unit = {},
    onResultClick: (SearchResult) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onHistoryItemRemove: (SearchHistory.SearchHistoryItem) -> Unit = {},
    onHistoryItemClick: (SearchHistory.SearchHistoryItem) -> Unit = {},
    onToggleCaseSensitive: () -> Unit = {},
    onToggleWholeWord: () -> Unit = {},
    onToggleRegex: () -> Unit = {},
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
) {
    var searchDebounce by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    // Debounce search input
    LaunchedEffect(state.searchQuery.text) {
        if (state.searchQuery.text.isNotBlank()) {
            searchDebounce = true
            delay(500) // Wait 500ms after user stops typing
            searchDebounce = false
            onPerformSearch()
        }
    }

    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp
        )
    ) {
        // Search toolbar - always shown
        item {
            SearchToolbarCard(
                state = state,
                design = design,
                onUpdateQuery = onUpdateQuery,
                onUpdateSearchPath = onUpdateSearchPath,
                onPerformSearch = onPerformSearch,
                onCancelSearch = onCancelSearch,
                onToggleCaseSensitive = onToggleCaseSensitive,
                onToggleWholeWord = onToggleWholeWord,
                onToggleRegex = onToggleRegex,
                workspaceButtonState = workspaceButtonState,
                onWorkspaceAction = onWorkspaceAction,
                onNavToWorkspaceManager = onNavToWorkspaceManager
            )
        }

        // Show search history when no search query
        if (state.searchQuery.text.isBlank() && state.searchHistory.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.searcher_recent_searches),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Surface(
                        modifier = Modifier.clickable { showClearHistoryDialog = true },
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent
                    ) {
                        Text(
                            text = stringResource(R.string.searcher_history_clear_all_action),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            items(state.searchHistory, key = { it.id }) { historyItem ->
                SwipeToDismissItem(
                    modifier = Modifier.fillMaxWidth(),
                    onDismiss = { onHistoryItemRemove(historyItem) },
                    dismissThreshold = 0.5f,
                    backgroundShape = RoundedCornerShape(12.dp),
                    dismissContent = {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.searcher_history_remove_action),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.TwoTone.ClearAll,
                            contentDescription = stringResource(R.string.searcher_history_remove_action),
                            tint = MaterialTheme.colorScheme.onError
                        )
                    }
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onHistoryItemClick(historyItem) }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Line 1: Search query with icon
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = historyItem.baseQuery,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            
                            // Line 2: Path with icon
                            historyItem.searchQuery?.path?.let { path ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.TwoTone.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = path.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.StartEllipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            
                            // Line 3: Results and time with icon
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = formatRelativeTime(historyItem.searchedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    historyItem.resultCount?.let { count ->
                                        Text(
                                            text = "• ${if (count == 0) "No results" else "$count results"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Status card - always visible when there's a query or search activity
        if (state.searchQuery.text.isNotBlank() || state.isSearching || state.searchState.results.isNotEmpty() || state.searchState.error != null) {
            item {
                SearchStatusCard(
                    state = state,
                    onCancel = onCancelSearch,
                    onClear = onClearResults
                )
            }
        }

        // Search results
        if (state.searchState.results.isNotEmpty()) {
            items(state.searchState.results) { result ->
                SearchResultRow(
                    result = result,
                    onClick = { onResultClick(result) }
                )
            }
        }

        // Empty state placeholder when no query and no history
        if (state.searchQuery.text.isBlank() && state.searchHistory.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.searcher_placeholder_search),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            }
        }
    }
    
    // Clear history confirmation dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = {
                Text(text = stringResource(R.string.searcher_history_clear_dialog_title))
            },
            text = {
                Text(text = stringResource(R.string.searcher_history_clear_dialog_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        showClearHistoryDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.searcher_history_clear_confirm_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearHistoryDialog = false }
                ) {
                    Text(text = stringResource(R.string.general_cancel_action))
                }
            }
        )
    }
}

@Composable
fun SearcherWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SearcherWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SearcherWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    val workspaceButtonState by workspaceButtonVm.state.collectAsState(null)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }

    state?.let { state ->
        SearcherWorkspacePage(
            design = design,
            state = state,
            onUpdateQuery = vm::updateSearchQuery,
            onUpdateSearchPath = vm::updateSearchPath,
            onPerformSearch = vm::performSearch,
            onCancelSearch = vm::cancelSearch,
            onClearResults = vm::clearResults,
            onResultClick = vm::onSearchResultClick,
            onClearHistory = vm::clearSearchHistory,
            onHistoryItemRemove = vm::removeHistoryItem,
            onHistoryItemClick = { item ->
                item.searchQuery?.let { query ->
                    vm.updateSearchQuery(TextFieldValue(query.query))
                    vm.updateSearchPath(query.path)
                    vm.updateFilter(query.filter)
                    vm.performSearch()
                } ?: run {
                    // Fallback to just the base query if full query unavailable
                    vm.updateSearchQuery(TextFieldValue(item.baseQuery))
                    vm.performSearch()
                }
            },
            onToggleCaseSensitive = vm::toggleCaseSensitive,
            onToggleWholeWord = vm::toggleWholeWord,
            onToggleRegex = vm::toggleRegex,
            workspaceButtonState = workspaceButtonState,
            onWorkspaceAction = workspaceButtonVm::onWorkspaceAction,
            onNavToWorkspaceManager = workspaceButtonVm::onNavToWorkspaceManager,
        )
    }
}

// SearchToolbarCard moved to SearchToolbarCard.kt


// Input components moved to SearchInputComponents.kt

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
            ),
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {}
        )
    }
}


