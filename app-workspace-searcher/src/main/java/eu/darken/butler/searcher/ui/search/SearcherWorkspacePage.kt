package eu.darken.butler.searcher.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchHistory
import eu.darken.butler.searcher.core.SearchResult
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogHost
import eu.darken.butler.searcher.ui.search.rows.FileRowData
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBar
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.OperationsBar
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationDialog
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import eu.darken.butler.workspace.ui.scroll.rememberBottomBarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.setHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearcherWorkspacePage(
    design: WorkspaceDesign = WorkspaceDesign(),
    stateSource: Flow<SearcherWorkspaceViewModel.State>,
    clipboardStateSource: Flow<SearcherWorkspaceViewModel.ClipboardState>,
    operationsStateSource: Flow<SearcherWorkspaceViewModel.OperationsState>,
    workspaceStateSource: Flow<WorkspaceButtonViewModel.State?>,
    vm: SearcherWorkspaceViewModel? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    onUpdateQuery: (TextFieldValue) -> Unit = {},
    onUpdateSearchPath: (APath<*>) -> Unit = {},
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
    onAction: (SearcherAction) -> Unit = {},
    onEnterSelectionMode: (SearchResult) -> Unit = {},
    onToggleSelection: (SearchResult) -> Unit = {},
    onExitSelectionMode: () -> Unit = {},
    onHideQuickActions: () -> Unit = {},
    onClipboardEntryClick: (ClipboardClip) -> Unit = {},
    onClipboardEntryRemove: (ClipboardClip) -> Unit = {},
    onClipboardClearAll: () -> Unit = {},
    onOperationCancel: (Operation.Id) -> Unit = {},
    onOperationDismiss: (Operation.Id) -> Unit = {},
    onOperationsClearCompleted: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    onOpenPathPicker: (() -> Unit)? = null,
) {
    val state by waitForState(stateSource)
    val clipboardState by clipboardStateSource.collectAsState(initial = SearcherWorkspaceViewModel.ClipboardState())
    val operationsState by operationsStateSource.collectAsState(initial = SearcherWorkspaceViewModel.OperationsState())
    val workspaceButtonState by workspaceStateSource.collectAsState(null)

    // Setup and remember blocks at top level
    val bottomBarScrollBehavior = rememberBottomBarScrollBehavior()
    val listState = rememberLazyListState()
    var searchDebounce by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    // Operation dialog state
    var operationDialogState by remember { mutableStateOf<OperationDialogState>(OperationDialogState.None) }
    var showCancelConfirmation by remember { mutableStateOf<Operation.Id?>(null) }

    // Set the bottom bar height for scroll behavior
    bottomBarScrollBehavior.state.setHeight(64.dp)

    // Derived states for stable recomposition - at top level for immediate reactivity
    val hasOperations by remember {
        derivedStateOf { operationsState.operations.isNotEmpty() }
    }
    val hasClipboard by remember {
        derivedStateOf { clipboardState.entries.isNotEmpty() }
    }
    val hasActions by remember {
        derivedStateOf { state?.selectionState?.selectedResultIds?.isNotEmpty() == true }
    }

    // Auto-show action bar when entering selection mode
    LaunchedEffect(hasActions) {
        if (hasActions) {
            // Smoothly animate action bar to visible when selection is activated
            bottomBarScrollBehavior.state.animateToExpanded()
        }
    }

    // Track action bar visibility for clipboard/operations animations
    val isActionBarHidden by remember {
        derivedStateOf {
            bottomBarScrollBehavior.state.collapsedFraction > 0.1f || !hasActions
        }
    }

    // Animate clipboard/operations bar position based on action bar state
    val clipboardVerticalOffset by animateFloatAsState(
        targetValue = if (isActionBarHidden) 8f else 64f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "clipboardOffset"
    )

    // Add slight scale animation for extra playfulness
    val clipboardScale by animateFloatAsState(
        targetValue = if (isActionBarHidden) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "clipboardScale"
    )

    state?.let { currentState ->
        // Debounce search input - needs currentState
        LaunchedEffect(currentState.searchQuery.text) {
            if (currentState.searchQuery.text.isNotBlank()) {
                searchDebounce = true
                delay(500) // Wait 500ms after user stops typing
                searchDebounce = false
                onPerformSearch()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = run {
                        val actionBarHeight = if (hasActions) 64.dp else 0.dp // 48dp + 16dp padding
                        val clipboardHeight = if (hasClipboard) 88.dp else 0.dp // ~80dp + 8dp padding
                        val operationsHeight = if (hasOperations) 80.dp else 0.dp // Operations bar height + padding
                        actionBarHeight + clipboardHeight + operationsHeight + 8.dp // Extra space
                    }
                )
            ) {
                // Search toolbar - always shown
                item {
                    SearchToolbarCard(
                        state = currentState,
                        design = design,
                        onUpdateQuery = onUpdateQuery,
                        onUpdateSearchPath = onUpdateSearchPath,
                        onPerformSearch = onPerformSearch,
                        onExplicitSearch = onExplicitSearch,
                        onCancelSearch = onCancelSearch,
                        onToggleCaseSensitive = onToggleCaseSensitive,
                        onToggleWholeWord = onToggleWholeWord,
                        onToggleRegex = onToggleRegex,
                        onOpenPathPicker = onOpenPathPicker,
                        workspaceButtonState = workspaceButtonState,
                        workspaceActionHandler = workspaceActionHandler,
                    )
                }

                // Show permission card if needed
                if (currentState.needsPermissions) {
                    item {
                        PermissionSetupCard(
                            searchPath = currentState.searchPath,
                            permissionState = currentState.permissionState,
                            onOpenSetup = onOpenSetup,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                // Show search history when no search query
                if (currentState.searchQuery.text.isBlank() && currentState.searchHistory.isNotEmpty()) {
                    searchHistorySection(
                        searchHistory = currentState.searchHistory,
                        onHistoryItemClick = onHistoryItemClick,
                        onHistoryItemRemove = onHistoryItemRemove,
                        onShowClearHistoryDialog = { showClearHistoryDialog = true }
                    )
                }

                // Status card - always visible when there's a query or search activity
                if (currentState.searchQuery.text.isNotBlank() || currentState.isSearching || currentState.searchState.results.isNotEmpty() || currentState.searchState.error != null) {
                    item {
                        SearchStatusCard(
                            state = currentState,
                            onCancel = onCancelSearch,
                            onClear = onClearResults
                        )
                    }
                }

                // Search results
                if (currentState.searchState.results.isNotEmpty()) {
                    items(currentState.searchState.results) { result ->
                        SearchResultRow(
                            result = result,
                            selectionState = currentState.selectionState,
                            onClick = { onResultClick(result) },
                            onLongPress = { onEnterSelectionMode(result) },
                            onSelectionToggle = { onToggleSelection(result) }
                        )
                    }
                }

                // Empty state placeholder when no query and no history
                if (currentState.searchQuery.text.isBlank() && currentState.searchHistory.isEmpty()) {
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

            // Floating Operations and Clipboard Bars Container
            AnimatedVisibility(
                visible = hasOperations || hasClipboard,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 8.dp,
                        end = 8.dp,
                        bottom = clipboardVerticalOffset.coerceAtLeast(0f).dp
                    )
                    .graphicsLayer {
                        scaleY = clipboardScale
                    },
                enter = slideInVertically(animationSpec = tween(150)) { it },
                exit = slideOutVertically(animationSpec = tween(150)) { it },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Operations Bar (top)
                    AnimatedVisibility(
                        visible = hasOperations,
                        enter = slideInVertically(animationSpec = tween(150)) { it },
                        exit = slideOutVertically(animationSpec = tween(150)) { it },
                    ) {
                        OperationsBar(
                            operations = operationsState.operations,
                            onCancelOperation = onOperationCancel,
                            onDismissOperation = onOperationDismiss,
                            onOperationClick = { operation ->
                                when (operation.state) {
                                    is OperationDisplay.State.Waiting -> {
                                        vm?.showConflictSheet(operation.id)
                                    }
                                    else -> {
                                        operationDialogState = OperationDialogState.OperationDetails(operation.id)
                                    }
                                }
                            },
                            onClearCompleted = onOperationsClearCompleted,
                        )
                    }

                    // Clipboard Bar (bottom)
                    AnimatedVisibility(
                        visible = hasClipboard,
                        enter = slideInVertically(animationSpec = tween(150)) { it },
                        exit = slideOutVertically(animationSpec = tween(150)) { it },
                    ) {
                        ClipboardBar(
                            clipboardEntries = clipboardState.entries,
                            onPasteClick = {}, // Searcher doesn't support paste
                            onRemoveClick = onClipboardEntryRemove,
                            onEntryClick = onClipboardEntryClick,
                            onClearAll = onClipboardClearAll
                        )
                    }
                }
            }

            // Floating Bottom ActionBar - Selection mode
            if (hasActions) {
                val actions = buildList {
                    // Select All / Deselect All
                    if (currentState.selectionState.isAllSelected) {
                        add(SearcherAction.DeselectAll)
                    } else if (currentState.selectionState.selectableResults.isNotEmpty()) {
                        add(SearcherAction.SelectAll)
                    }

                    // Copy
                    add(SearcherAction.Copy(currentState.selectionState.selectedResults))

                    // Cut
                    add(SearcherAction.Cut(currentState.selectionState.selectedResults))

                    // Share (if reasonable number of items)
                    val shareAction = SearcherAction.Share(currentState.selectionState.selectedResults)
                    if (shareAction.isVisible) {
                        add(shareAction)
                    }

                    // Delete
                    add(SearcherAction.Delete(currentState.selectionState.selectedResults))
                }

                eu.darken.butler.workspace.ui.actions.WorkspaceActionBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .graphicsLayer {
                            // Immediate snap behavior: fully visible or fully hidden
                            alpha = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 0f else 1f
                            translationY =
                                if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 64.dp.toPx() else 0f
                        },
                    actions = actions,
                    onActionClick = { action ->
                        val searcherAction = action as SearcherAction
                        when (searcherAction) {
                            is SearcherAction.DeselectAll -> onExitSelectionMode()
                            else -> onAction(searcherAction)
                        }
                    },
                    selectionCount = currentState.selectionState.selectionCount
                )
            }
        }

        // Quick actions bottom sheet
        currentState.quickActionsResult?.let { result ->
            SearchResultQuickActions(
                result = result,
                onAction = { action ->
                    onAction(action)
                    onHideQuickActions()
                },
                onLongPress = {
                    onEnterSelectionMode(it)
                    onHideQuickActions()
                },
                onDismiss = onHideQuickActions
            )
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

        // Dialog host
        SearcherDialogHost(
            dialogState = currentState.dialogState,
            onDismiss = { vm?.dismissDialog() },
            onDeleteConfirmed = { vm?.onDeleteConfirmed(it) }
        )

        // Operation dialog host
        OperationDialogHost(
            dialogState = operationDialogState,
            operations = operationsState.operations,
            onDismissDialog = { operationDialogState = OperationDialogState.None },
            onCancelOperation = { operationId ->
                operationDialogState = OperationDialogState.None
                showCancelConfirmation = operationId
            },
            onCopyError = { vm?.copyError(it) }
        )

        // Cancel operation confirmation dialog
        showCancelConfirmation?.let { operationId ->
            CancelOperationConfirmationDialog(
                onDismiss = { showCancelConfirmation = null },
                onConfirm = {
                    vm?.cancelOperation(operationId)
                    showCancelConfirmation = null
                }
            )
        }
    }  // End of state?.let
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

    SearcherWorkspacePage(
        design = design,
        stateSource = vm.state,
        clipboardStateSource = vm.clipboard,
        operationsStateSource = vm.operations,
        workspaceStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
        onUpdateQuery = vm::updateSearchQuery,
        onUpdateSearchPath = vm::updateSearchPath,
        onPerformSearch = vm::performSearch,
        onExplicitSearch = vm::performExplicitSearch,
        onCancelSearch = vm::cancelSearch,
        onClearResults = vm::clearResults,
        onResultClick = vm::showQuickActions,
        onClearHistory = vm::clearSearchHistory,
        onHistoryItemRemove = vm::removeHistoryItem,
        onHistoryItemClick = { item ->
            item.searchQuery?.let { query ->
                vm.updateSearchQuery(TextFieldValue(query.query))
                vm.updateSearchPath(query.path)
                vm.updateFilter(query.filter)
                vm.performExplicitSearch()
            } ?: run {
                vm.updateSearchQuery(TextFieldValue(item.baseQuery))
                vm.performExplicitSearch()
            }
        },
        onToggleCaseSensitive = vm::toggleCaseSensitive,
        onToggleWholeWord = vm::toggleWholeWord,
        onToggleRegex = vm::toggleRegex,
        onAction = vm::onAction,
        onEnterSelectionMode = vm::enterSelectionMode,
        onToggleSelection = vm::toggleSelection,
        onExitSelectionMode = vm::deselectAll,
        onHideQuickActions = vm::hideQuickActions,
        onClipboardEntryClick = vm::showClipboardInfo,
        onClipboardEntryRemove = vm::removeClipboardEntry,
        onClipboardClearAll = vm::clearAllClipboard,
        onOperationCancel = vm::cancelOperation,
        onOperationDismiss = vm::dismissOperation,
        onOperationsClearCompleted = vm::clearCompletedOperations,
        onOpenSetup = vm::navigateToSetup,
        onOpenPathPicker = vm::openPathPicker,
    )
}

// SearchToolbarCard moved to SearchToolbarCard.kt


// Input components moved to SearchInputComponents.kt

@Composable
fun SearchResultRow(
    result: SearchResult,
    selectionState: SearcherSelectionState,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSelectionToggle: () -> Unit
) {
    val fileRowData = FileRowData(
        name = result.name,
        path = result.path.path,
        fileType = result.fileType,
        size = result.size,
        modifiedAt = result.modifiedAt,
        metadata = extractFileMetadata(result)
    )

    SelectableFileRow(
        data = fileRowData,
        isSelected = selectionState.isSelected(result),
        isSelectionMode = selectionState.isSelectionMode,
        onClick = if (selectionState.isSelectionMode) onSelectionToggle else onClick,
        onLongPress = onLongPress
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


