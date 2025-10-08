package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchHistory
import eu.darken.butler.searcher.core.SearchResult
import eu.darken.butler.searcher.ui.search.rows.FileRowData
import eu.darken.butler.searcher.ui.search.rows.SmartFileRow
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.delay

@Composable
fun SearcherWorkspacePage(
    design: WorkspaceDesign = WorkspaceDesign(),
    state: SearcherWorkspaceViewModel.State,
    onUpdateQuery: (TextFieldValue) -> Unit = {},
    onUpdateSearchPath: (APath) -> Unit = {},
    onPerformSearch: () -> Unit = {},
    onExplicitSearch: () -> Unit = {},
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
    onNavToSettings: () -> Unit,
    onOpenSetup: () -> Unit = {},
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
                onExplicitSearch = onExplicitSearch,
                onCancelSearch = onCancelSearch,
                onToggleCaseSensitive = onToggleCaseSensitive,
                onToggleWholeWord = onToggleWholeWord,
                onToggleRegex = onToggleRegex,
                workspaceButtonState = workspaceButtonState,
                onWorkspaceAction = onWorkspaceAction,
                onNavToWorkspaceManager = onNavToWorkspaceManager,
                onNavToSettings = onNavToSettings
            )
        }
        
        // Show permission card if needed
        if (state.needsPermissions) {
            item {
                PermissionSetupCard(
                    searchPath = state.searchPath,
                    permissionState = state.permissionState,
                    onOpenSetup = onOpenSetup,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Show search history when no search query
        if (state.searchQuery.text.isBlank() && state.searchHistory.isNotEmpty()) {
            searchHistorySection(
                searchHistory = state.searchHistory,
                onHistoryItemClick = onHistoryItemClick,
                onHistoryItemRemove = onHistoryItemRemove,
                onShowClearHistoryDialog = { showClearHistoryDialog = true }
            )
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
            onExplicitSearch = vm::performExplicitSearch,
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
                    vm.performExplicitSearch() // Use explicit search for history items too
                } ?: run {
                    // Fallback to just the base query if full query unavailable
                    vm.updateSearchQuery(TextFieldValue(item.baseQuery))
                    vm.performExplicitSearch() // Use explicit search for history items too
                }
            },
            onToggleCaseSensitive = vm::toggleCaseSensitive,
            onToggleWholeWord = vm::toggleWholeWord,
            onToggleRegex = vm::toggleRegex,
            workspaceButtonState = workspaceButtonState,
            onWorkspaceAction = workspaceButtonVm::onWorkspaceAction,
            onNavToWorkspaceManager = workspaceButtonVm::onNavToWorkspaceManager,
            onNavToSettings = workspaceButtonVm::onNavToSettings,
            onOpenSetup = vm::navigateToSetup,
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
        fileType = result.fileType,
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
 * Extract metadata from search result for enhanced display
 */
private fun extractFileMetadata(@Suppress("UNUSED_PARAMETER") result: SearchResult): Map<String, String> {
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
            onNavToWorkspaceManager = {},
            onNavToSettings = {}
        )
    }
}


