package eu.darken.butler.searcher.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchRepository
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
            onResultClick = vm::onSearchResultClick,
            onClearHistory = vm::clearSearchHistory,
            onHistoryItemClick = { item ->
                vm.updateSearchQuery(item.query)
                vm.performSearch()
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
    onUpdateQuery: (String) -> Unit = {},
    onUpdateSearchPath: (APath) -> Unit = {},
    onPerformSearch: () -> Unit = {},
    onCancelSearch: () -> Unit = {},
    onResultClick: (SearchResult) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onHistoryItemClick: (SearchRepository.SearchHistoryItem) -> Unit = {},
    onToggleCaseSensitive: () -> Unit = {},
    onToggleWholeWord: () -> Unit = {},
    onToggleRegex: () -> Unit = {},
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
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

    val listState = rememberLazyListState()
    val showToolbar by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 50
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content
        when {
            state.searchQuery.isBlank() && state.searchHistory.isNotEmpty() -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(
                        top = 180.dp, // Space for toolbar + header + workspace button
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 8.dp
                    )
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.searcher_recent_searches),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
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
                                    imageVector = Icons.TwoTone.Search,
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 180.dp),
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

            state.searchState.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.searcher_search_error),
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
            }

            !state.isSearching && state.searchState.results.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.searcher_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(
                        top = 180.dp, // Space for toolbar + header + workspace button
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 8.dp
                    )
                ) {
                    item {
                        Text(
                            text = "${state.searchState.results.size} results",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(state.searchState.results) { result ->
                        SearchResultRow(
                            result = result,
                            onClick = { onResultClick(result) }
                        )
                    }
                }
            }
        }

        // Collapsing toolbar
        AnimatedVisibility(
            visible = showToolbar,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
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

        // Progress indicator
        if (state.isSearching || searchDebounce) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (showToolbar) 180.dp else 0.dp)
            ) {
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
        }
    }
}

@Composable
fun SearchInputCard(
    query: String,
    onQueryChange: (String) -> Unit,
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
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
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
                        if (value.isEmpty()) {
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
    query: String,
    onQueryChange: (String) -> Unit,
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

                query.isNotEmpty() -> {
                    Icon(
                        imageVector = Icons.TwoTone.Clear,
                        contentDescription = "Clear",
                        modifier = Modifier
                            .clickable { onQueryChange("") }
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
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    var pathText by remember(path) { mutableStateOf(path.path) }
    var showPathPicker by remember { mutableStateOf(false) }

    CustomSearchField(
        value = pathText,
        onValueChange = { newPath ->
            pathText = newPath
            try {
                onPathChange(LocalPath.build(newPath))
            } catch (e: Exception) {
                // Invalid path, don't update
            }
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
        enabled = !isSearching,
        modifier = modifier
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
            query = "test search",
            onQueryChange = {},
            onSearch = {},
            path = LocalPath.build("/storage/emulated/0/Android/data/eu.darken.butler"),
            onPathChange = {},
            isSearching = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}