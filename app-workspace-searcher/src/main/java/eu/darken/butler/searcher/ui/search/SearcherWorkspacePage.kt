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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.keyboard.KeyboardShortcut
import eu.darken.butler.common.keyboard.keyboardShortcuts
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchHistory
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogHost
import eu.darken.butler.searcher.ui.search.input.SearchStatusCard
import eu.darken.butler.searcher.ui.search.input.SearchToolbarCard
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBar
import eu.darken.butler.workspace.ui.error.WorkspaceErrorCard
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.OperationsBar
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationDialog
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import eu.darken.butler.workspace.ui.scroll.getCurrentHeightDp
import eu.darken.butler.workspace.ui.scroll.rememberBottomBarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.rememberTopToolbarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.setHeight
import eu.darken.butler.workspace.ui.scroll.setHeights
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearcherWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    stateSource: Flow<SearcherWorkspaceViewModel.State>,
    clipboardStateSource: Flow<SearcherWorkspaceViewModel.ClipboardState>,
    operationsStateSource: Flow<SearcherWorkspaceViewModel.OperationsState>,
    workspaceStateSource: Flow<WorkspaceButtonViewModel.State?>,
    vm: SearcherWorkspaceViewModel? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    onUpdateQuery: (TextFieldValue) -> Unit = {},
    onRemoveSearchPath: (SearchTarget) -> Unit = {},
    onTogglePathEnabled: (SearchTarget) -> Unit = {},
    onPerformSearch: () -> Unit = {},
    onExplicitSearch: () -> Unit = {},
    onCancelSearch: () -> Unit = {},
    onClearResults: () -> Unit = {},
    onResultClick: (SearchItem) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onHistoryItemRemove: (SearchHistory.SearchHistoryItem) -> Unit = {},
    onHistoryItemClick: (SearchHistory.SearchHistoryItem) -> Unit = {},
    onToggleCaseSensitive: () -> Unit = {},
    onToggleWholeWord: () -> Unit = {},
    onToggleRegex: () -> Unit = {},
    onAction: (SearcherAction) -> Unit = {},
    onEnterSelectionMode: (SearchItem) -> Unit = {},
    onToggleSelection: (SearchItem) -> Unit = {},
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
    onCopyError: (Throwable) -> Unit = {},
) {
    val state by waitForState(stateSource)
    val clipboardState by clipboardStateSource.collectAsState(initial = SearcherWorkspaceViewModel.ClipboardState())
    val operationsState by operationsStateSource.collectAsState(initial = SearcherWorkspaceViewModel.OperationsState())
    val workspaceButtonState by workspaceStateSource.collectAsState(null)

    // Setup and remember blocks at top level
    val bottomBarScrollBehavior = rememberBottomBarScrollBehavior()
    val topToolbarScrollBehavior = rememberTopToolbarScrollBehavior()
    val listState = rememberLazyListState()
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val shortcutsFocusRequester = remember { FocusRequester() }

    // Track actual measured height of the toolbar card
    val density = LocalDensity.current
    var actualToolbarHeightPx by remember { mutableStateOf(0) }
    val actualToolbarHeightDp = with(density) { actualToolbarHeightPx.toDp() }

    // Operation dialog state
    var operationDialogState by remember { mutableStateOf<OperationDialogState>(OperationDialogState.None) }
    var showCancelConfirmation by remember { mutableStateOf<Operation.Id?>(null) }

    // Wrapped selection callbacks that clear focus and hide keyboard
    val wrappedOnEnterSelectionMode: (SearchItem) -> Unit = remember(focusManager, keyboardController, shortcutsFocusRequester) {
        { result ->
            focusManager.clearFocus()
            keyboardController?.hide()
            onEnterSelectionMode(result)
        }
    }

    val wrappedOnToggleSelection: (SearchItem) -> Unit = remember(focusManager, keyboardController, shortcutsFocusRequester) {
        { result ->
            // Only clear focus and hide keyboard when entering selection mode (first selection)
            // Not when already in selection mode (subsequent toggles)
            if (state?.selectionState?.isSelectionMode != true) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            onToggleSelection(result)
        }
    }

    // Re-request focus for keyboard shortcuts after clearing focus
    // This ensures shortcuts continue working after selecting a result
    LaunchedEffect(state?.selectionState?.isSelectionMode) {
        if (state?.selectionState?.isSelectionMode == true) {
            delay(50) // Small delay to let keyboard animation complete
            shortcutsFocusRequester.requestFocus()
        }
    }

    // Set the bottom bar height for scroll behavior
    bottomBarScrollBehavior.state.setHeight(64.dp)

    // Set the top toolbar heights (expanded and collapsed)
    topToolbarScrollBehavior.state.setHeights(
        expandedHeightDp = 164.dp, // Full card with all options (actual measured height)
        collapsedHeightDp = 44.dp  // Minimal compact state (actual measured height)
    )

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

    // Get current toolbar height for layout calculations
    topToolbarScrollBehavior.state.getCurrentHeightDp()
    val statusCardHeight = 60.dp // Fixed height for status card

    // Determine if status card should be visible
    val showStatusCard by remember {
        derivedStateOf {
            state?.let { currentState ->
                currentState.searchQuery.text.isNotBlank() ||
                        currentState.isSearching ||
                        currentState.searchState.results.isNotEmpty() ||
                        currentState.searchState.error != null
            } ?: false
        }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .keyboardShortcuts(focusRequester = shortcutsFocusRequester) {
                    on(KeyboardShortcut.Copy) {
                        val selectedResults = currentState.selectionState.selectedResults
                        if (selectedResults.isNotEmpty()) {
                            onAction(SearcherAction.Copy(selectedResults))
                        }
                    }
                    on(KeyboardShortcut.Cut) {
                        val selectedResults = currentState.selectionState.selectedResults
                        if (selectedResults.isNotEmpty()) {
                            onAction(SearcherAction.Cut(selectedResults))
                        }
                    }
                    on(KeyboardShortcut.SelectAll) {
                        if (currentState.selectionState.selectableResults.isNotEmpty()) {
                            onAction(SearcherAction.SelectAll)
                        }
                    }
                    on(KeyboardShortcut.Delete) {
                        val selectedResults = currentState.selectionState.selectedResults
                        if (selectedResults.isNotEmpty()) {
                            onAction(SearcherAction.Delete(selectedResults))
                        }
                    }
                    on(KeyboardShortcut.Escape) {
                        if (currentState.selectionState.isSelectionMode) {
                            onAction(SearcherAction.DeselectAll)
                        }
                    }
                }
        ) {
            // Scrollable content layer - with padding for pinned cards
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(topToolbarScrollBehavior.nestedScrollConnection)
                    .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp + actualToolbarHeightDp + (if (showStatusCard) statusCardHeight + 8.dp else 0.dp),
                    bottom = run {
                        val actionBarHeight = if (hasActions) 64.dp else 0.dp
                        val clipboardHeight = if (hasClipboard) 88.dp else 0.dp
                        val operationsHeight = if (hasOperations) 80.dp else 0.dp
                        actionBarHeight + clipboardHeight + operationsHeight + 8.dp
                    }
                )
            ) {
                // Show permission card if needed
                if (currentState.needsPermissions && currentState.searchTargets.isNotEmpty()) {
                    item {
                        val searchPath = when (val firstTarget = currentState.searchTargets.first()) {
                            is SearchTarget.Path -> firstTarget.path
                        }
                        PermissionSetupCard(
                            searchPath = searchPath,
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

                // Search results and errors
                if (currentState.listItems.isNotEmpty()) {
                    items(
                        items = currentState.listItems,
                        key = { item ->
                            when (item) {
                                is SearchListItem.Result -> item.searchItem.path.path
                                is SearchListItem.Error -> "error_${item.timestamp}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is SearchListItem.Result -> {
                                SelectableFileRow(
                                    result = item.searchItem,
                                    isSelected = currentState.selectionState.isSelected(item.searchItem),
                                    isSelectionMode = currentState.selectionState.isSelectionMode,
                                    onClick = {
                                        if (currentState.selectionState.isSelectionMode) {
                                            wrappedOnToggleSelection(item.searchItem)
                                        } else {
                                            onResultClick(item.searchItem)
                                        }
                                    },
                                    onLongPress = {
                                        wrappedOnEnterSelectionMode(item.searchItem)
                                    },
                                )
                            }
                            is SearchListItem.Error -> {
                                WorkspaceErrorCard(
                                    title = stringResource(R.string.searcher_search_error),
                                    error = item.throwable,
                                    onCopyError = { onCopyError(item.throwable) },
                                    onDismiss = null,
                                )
                            }
                        }
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

            // Pinned search toolbar at top - collapses on scroll
            SearchToolbarCard(
                workspaceId = workspaceId,
                state = currentState,
                design = design,
                collapsedFraction = topToolbarScrollBehavior.state.collapsedFraction,
                onUpdateQuery = onUpdateQuery,
                onRemoveSearchPath = onRemoveSearchPath,
                onTogglePathEnabled = onTogglePathEnabled,
                onPerformSearch = onPerformSearch,
                onExplicitSearch = onExplicitSearch,
                onCancelSearch = onCancelSearch,
                onToggleCaseSensitive = onToggleCaseSensitive,
                onToggleWholeWord = onToggleWholeWord,
                onToggleRegex = onToggleRegex,
                onOpenPathPicker = onOpenPathPicker,
                workspaceButtonState = workspaceButtonState,
                workspaceActionHandler = workspaceActionHandler,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onGloballyPositioned { layoutCoordinates ->
                        actualToolbarHeightPx = layoutCoordinates.size.height
                    }
            )

            // Pinned status card below toolbar - always visible when needed
            if (showStatusCard) {
                SearchStatusCard(
                    state = currentState,
                    onCancel = onCancelSearch,
                    onClear = onClearResults,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 16.dp + actualToolbarHeightDp) // Account for toolbar's vertical padding + gap
                        .padding(horizontal = 16.dp)
                )
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
                            workspaceType = Workspace.Type.SEARCHER,
                            clipboardEntries = clipboardState.entries,
                            onPasteClick = { clip -> vm?.openClipboardInExplorer(clip) },
                            onRemoveClick = onClipboardEntryRemove,
                            onEntryClick = onClipboardEntryClick,
                            onClearAll = onClipboardClearAll
                        )
                    }
                }
            }

            // Floating Bottom ActionBar - Selection mode
            if (hasActions) {
                WorkspaceActionBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .graphicsLayer {
                            // Immediate snap behavior: fully visible or fully hidden
                            alpha = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 0f else 1f
                            translationY =
                                if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 64.dp.toPx() else 0f
                        },
                    actions = currentState.availableActions,
                    onActionClick = { action ->
                        when (val searcherAction = action as SearcherAction) {
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
                    wrappedOnEnterSelectionMode(it)
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
            onDeleteConfirmed = { vm?.onDeleteConfirmed(it) },
            onCopyToClipboard = { text -> vm?.copyPathToSystemClipboard(text) },
            onNavigateToClipboardSource = { clip -> vm?.navigateToClipboardSource(clip) },
            onRemoveClipboardEntry = { clip -> vm?.removeClipboardEntry(clip) },
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
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        clipboardStateSource = vm.clipboard,
        operationsStateSource = vm.operations,
        workspaceStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
        onUpdateQuery = vm::updateSearchQuery,
        onRemoveSearchPath = vm::removeSearchTarget,
        onTogglePathEnabled = vm::toggleTargetEnabled,
        onPerformSearch = vm::performSearch,
        onExplicitSearch = vm::performExplicitSearch,
        onCancelSearch = vm::cancelSearch,
        onClearResults = vm::clearResults,
        onResultClick = vm::showQuickActions,
        onClearHistory = vm::clearSearchHistory,
        onHistoryItemRemove = vm::removeHistoryItem,
        onHistoryItemClick = vm::restoreFromHistory,
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
        onCopyError = vm::copySearchError,
    )
}
