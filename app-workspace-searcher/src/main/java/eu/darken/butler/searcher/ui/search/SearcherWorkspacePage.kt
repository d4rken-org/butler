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
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Show search history when no search query
        if (state.searchQuery.text.isBlank() && state.searchHistory.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.searcher_recent_searches),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(
                        onClick = { showClearHistoryDialog = true }
                    ) {
                        Text(
                            text = stringResource(R.string.searcher_history_clear_all_action),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
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
                Spacer(modifier = Modifier.height(8.dp))
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
fun SearchToolbarCard(
    state: SearcherWorkspaceViewModel.State,
    design: WorkspaceDesign,
    onUpdateQuery: (TextFieldValue) -> Unit,
    onUpdateSearchPath: (APath) -> Unit,
    onPerformSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onToggleCaseSensitive: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onToggleRegex: () -> Unit,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
    modifier: Modifier = Modifier
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
                    onSearch = onPerformSearch,
                    isSearching = state.isSearching,
                    onCancel = if (state.isSearching) onCancelSearch else null,
                    modifier = Modifier.weight(1f)
                )

                if (design.isSingle) {
                    Spacer(modifier = Modifier.width(8.dp))

                    WorkspaceButton(
                        state = workspaceButtonState,
                        onAction = onWorkspaceAction,
                        onNavToWorkspaceManager = onNavToWorkspaceManager,
                    )
                }
            }

            SearchPathBar(
                path = state.searchPath,
                onPathChange = onUpdateSearchPath,
                onPerformSearch = onPerformSearch,
                isSearching = state.isSearching
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

@Composable
fun SearchInputCard(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    path: APath,
    onPathChange: (APath) -> Unit,
    isSearching: Boolean,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SearchBar(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                isSearching = isSearching,
                onCancel = onCancel
            )

            SearchPathBar(
                path = path,
                onPathChange = onPathChange,
                isSearching = isSearching
            )
        }
    }
}

@Composable
fun CustomSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant.copy(alpha = if (isFocused) 0.9f else 0.7f),
        border = BorderStroke(
            width = if (isFocused) 2.dp else 0.dp,
            color = when {
                isError -> colors.error
                isFocused -> colors.primary
                else -> Color.Transparent
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                Box(modifier = Modifier.padding(end = 8.dp)) {
                    it()
                }
            }

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colors.onSurface
                ),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                singleLine = true,
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            trailingIcon?.let {
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    it()
                }
            }
        }
    }
}

@Composable
fun SearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    CustomSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.searcher_placeholder_search),
        leadingIcon = {
            Icon(
                imageVector = Icons.TwoTone.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            when {
                isSearching && onCancel != null -> {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = stringResource(R.string.general_cancel_action),
                        modifier = Modifier
                            .clickable { onCancel() }
                            .size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                query.text.isNotEmpty() -> {
                    Icon(
                        imageVector = Icons.TwoTone.Clear,
                        contentDescription = "Clear",
                        modifier = Modifier
                            .clickable { onQueryChange(TextFieldValue("")) }
                            .size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> null
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
    )
}

@Composable
fun SearchPathBar(
    path: APath,
    onPathChange: (APath) -> Unit,
    onPerformSearch: () -> Unit = {},
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    var pathText by remember { mutableStateOf(TextFieldValue(path.path)) }
    var showPathPicker by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Update pathText only when path changes from external source and field is not focused
    LaunchedEffect(path, isFocused) {
        if (!isFocused && pathText.text != path.path) {
            pathText = TextFieldValue(path.path)
        }
    }

    // Validate and update path when user finishes editing (loses focus)
    LaunchedEffect(isFocused) {
        if (!isFocused) {
            try {
                val newPath = LocalPath.build(pathText.text)
                onPathChange(newPath)
            } catch (e: Exception) {
                // Invalid path, revert to current valid path
                pathText = TextFieldValue(path.path)
            }
        }
    }

    CustomSearchField(
        value = pathText,
        onValueChange = { newPath ->
            pathText = newPath
            // Don't validate path on every keystroke - wait for focus loss or Done action
        },
        placeholder = stringResource(R.string.searcher_placeholder_path),
        leadingIcon = {
            Icon(
                imageVector = Icons.TwoTone.Folder,
                contentDescription = "Search Path",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.TwoTone.FolderOpen,
                contentDescription = "Browse",
                modifier = Modifier
                    .clickable { showPathPicker = true }
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            try {
                val newPath = LocalPath.build(pathText.text)
                onPathChange(newPath)
                keyboardController?.hide() // Dismiss soft keyboard (no-op for external keyboards)
                onPerformSearch() // Trigger search with new path
            } catch (e: Exception) {
                // Invalid path, revert to current valid path
                pathText = TextFieldValue(path.path)
            }
        }),
        enabled = !isSearching,
        modifier = modifier.focusRequester(focusRequester),
        interactionSource = interactionSource
    )

    if (showPathPicker) {
        PathPickerDialog(
            onPathSelected = { selectedPath ->
                pathText = TextFieldValue(selectedPath.path)
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
        title = { Text(stringResource(R.string.searcher_select_path_title)) },
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
                            imageVector = Icons.TwoTone.Folder,
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
                Text(stringResource(R.string.general_cancel_action))
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

@Composable
fun SearchStatusCard(
    state: SearcherWorkspaceViewModel.State,
    onCancel: () -> Unit,
    onClear: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon/Progress indicator
            if (state.isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.TwoTone.Search,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Primary message
                Text(
                    text = if (state.isSearching) {
                        state.searchState.progress?.let { progress ->
                            val folderName = when (val path = progress.currentPath) {
                                is LocalPath -> path.parent()?.name ?: path.name
                                else -> path.name
                            }
                            stringResource(R.string.searcher_progress_searching_in, folderName)
                        } ?: stringResource(R.string.searcher_progress_searching)
                    } else {
                        when {
                            state.searchState.error != null -> stringResource(R.string.searcher_search_error)
                            state.searchState.results.isNotEmpty() -> stringResource(R.string.searcher_status_results_found, state.searchState.results.size)
                            else -> stringResource(R.string.searcher_status_no_results)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Secondary message - always present to maintain card height
                Text(
                    text = when {
                        state.isSearching -> {
                            state.searchState.progress?.let { progress ->
                                stringResource(
                                    R.string.searcher_progress_stats,
                                    progress.itemsScanned,
                                    progress.resultsFound
                                )
                            } ?: stringResource(R.string.searcher_progress_searching)
                        }
                        state.searchState.results.isNotEmpty() -> {
                            stringResource(R.string.searcher_status_search_completed)
                        }
                        else -> {
                            // No results - provide helpful text
                            stringResource(R.string.searcher_placeholder_search)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    minLines = 1, // Ensure minimum height even when empty
                    maxLines = 1 // Keep it single line for consistency
                )
            }
            
            // Fixed-width container for action area to prevent width changes
            Box(
                modifier = Modifier.width(72.dp), // Fixed width for consistent layout
                contentAlignment = Alignment.Center
            ) {
                // Always show an action button to maintain consistent UI
                if (state.isSearching) {
                    // Cancel button when searching
                    TextButton(
                        onClick = onCancel,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.general_cancel_action),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else {
                    // Clear button when showing results or no results
                    TextButton(
                        onClick = onClear,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.searcher_history_clear_confirm_action),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
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
                searchPath = LocalPath.build("/storage/emulated/0/Android/data/eu.darken.butler")
            ),
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {}
        )
    }
}

@Preview2
@Composable
private fun SearchInputCardPreview() {
    PreviewWrapper {
        SearchInputCard(
            query = TextFieldValue("test search"),
            onQueryChange = {},
            onSearch = {},
            path = LocalPath.build("/storage/emulated/0/Android/data/eu.darken.butler"),
            onPathChange = {},
            isSearching = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview2
@Composable
private fun SearchStatusCardPreview() {
    PreviewWrapper {
        SearchStatusCard(
            state = SearcherWorkspaceViewModel.State(
                id = Workspace.Id(),
                searchPath = LocalPath.build("/storage/emulated/0/Documents"),
                searchState = SearcherWorkspaceViewModel.SearchState(
                    status = SearcherWorkspaceViewModel.SearchState.Status.COMPLETED,
                    results = listOf(), // Empty for "no results" state
                    progress = null
                )
            ),
            onCancel = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}