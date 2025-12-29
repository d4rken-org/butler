package eu.darken.butler.searcher.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.keyboard.KeyboardShortcut
import eu.darken.butler.common.keyboard.keyboardShortcuts
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.ui.search.dialogs.SearchErrorDialog
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogHost
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogState
import eu.darken.butler.searcher.ui.search.dialogs.DateConditionEditSheet
import eu.darken.butler.searcher.ui.search.dialogs.SizeConditionEditSheet
import eu.darken.butler.searcher.ui.search.dialogs.TypeConditionEditSheet
import eu.darken.butler.searcher.ui.search.elements.PermissionSetupCard
import eu.darken.butler.searcher.ui.search.elements.SearchProgressCard
import eu.darken.butler.searcher.ui.search.elements.SearchResultItemDetails
import eu.darken.butler.searcher.ui.search.elements.SearchTargetsEmptyStateCard
import eu.darken.butler.searcher.ui.search.elements.SearchToolbarCard
import eu.darken.butler.searcher.ui.search.elements.SearcherInfoBar
import eu.darken.butler.searcher.ui.search.elements.TemplatesBottomSheetContent
import eu.darken.butler.searcher.ui.search.elements.TemplatesCard
import eu.darken.butler.searcher.ui.search.elements.searchHistorySection
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.searcher.ui.search.items.SelectableFileGrid
import eu.darken.butler.searcher.ui.search.items.SelectableFileRow
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.util.SearchListItem
import eu.darken.butler.searcher.ui.search.util.SearcherAction
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBar
import eu.darken.butler.workspace.ui.error.WorkspaceErrorCard
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.OperationsBar
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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
    onPageAction: (SearcherPageAction) -> Unit = {},
) {
    val state by waitForState(stateSource)
    val clipboardState by clipboardStateSource.collectAsState(initial = SearcherWorkspaceViewModel.ClipboardState())
    val operationsState by operationsStateSource.collectAsState(initial = SearcherWorkspaceViewModel.OperationsState())
    val workspaceButtonState by workspaceStateSource.collectAsState(null)

    // Setup and remember blocks at top level
    val topBarStackState = rememberFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        includeSystemBarInset = design.paneEdges.touchesTop,
    )
    val bottomBarStackState = rememberFloatingBarStackState(
        position = BarPosition.BOTTOM,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        includeSystemBarInset = design.paneEdges.touchesBottom,
    )
    val density = LocalDensity.current
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp
    val listState = rememberLazyListState()
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showTemplatesSheet by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val shortcutsFocusRequester = remember { FocusRequester() }

    // Operation dialog state
    var operationDialogState by remember { mutableStateOf<OperationDialogState>(OperationDialogState.None) }

    // Use rememberUpdatedState for callback to avoid lambda recreation
    val currentOnPageAction by rememberUpdatedState(onPageAction)

    // Wrapped selection callbacks that clear focus and hide keyboard
    val wrappedOnEnterSelectionMode: (SearchItem) -> Unit = remember {
        { result ->
            focusManager.clearFocus()
            keyboardController?.hide()
            currentOnPageAction(SearcherPageAction.Results.EnterSelectionMode(result))
        }
    }

    val wrappedOnToggleSelection: (SearchItem) -> Unit = remember {
        { result ->
            // Only clear focus and hide keyboard when entering selection mode (first selection)
            // Not when already in selection mode (subsequent toggles)
            if (state?.selectionState?.isSelectionMode != true) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            currentOnPageAction(SearcherPageAction.Results.ToggleSelection(result))
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

    // Auto-scroll to top when sort settings change
    LaunchedEffect(state?.sortSettings) {
        if (state?.sortSettings != null) {
            listState.animateScrollToItem(0)
        }
    }

    // Auto-scroll to top when a new search starts
    LaunchedEffect(state?.workspaceState?.searchStatus) {
        if (state?.workspaceState?.searchStatus == SearcherWorkspace.State.SearchStatus.SEARCHING) {
            listState.scrollToItem(0)
        }
    }

    // Derived states for stable recomposition - at top level for immediate reactivity
    val hasOperations by remember {
        derivedStateOf { operationsState.operations.isNotEmpty() }
    }
    val hasActiveOperations by remember {
        derivedStateOf {
            operationsState.operations.any { op ->
                op.state is OperationDisplay.State.Queued ||
                    op.state is OperationDisplay.State.Running ||
                    op.state is OperationDisplay.State.Waiting
            }
        }
    }
    val hasClipboard by remember {
        derivedStateOf { clipboardState.entries.isNotEmpty() }
    }
    val hasActions by remember {
        derivedStateOf {
            val currentState = state ?: return@derivedStateOf false
            val showingHistory = !currentState.hasResults && currentState.searchHistory.isNotEmpty()

            currentState.selectionState.selectedResultIds.isNotEmpty() ||
                (!showingHistory && currentState.listItems.isNotEmpty())
        }
    }

    // Determine if progress card should be visible
    val showProgressCard by remember {
        derivedStateOf {
            state?.let { currentState ->
                currentState.workspaceState.targetProgress.isNotEmpty() &&
                    currentState.workspaceState.searchStatus != SearcherWorkspace.State.SearchStatus.IDLE
            } ?: false
        }
    }

    // Determine if info bar should be visible (when there are results OR selection)
    val showInfoBar by remember {
        derivedStateOf {
            val currentState = state ?: return@derivedStateOf false
            currentState.selectionState.selectionCount > 0 || currentState.hasResults
        }
    }

    // Calculate results info for info bar
    val resultsCount by remember {
        derivedStateOf {
            state?.listItems?.count { it is SearchListItem.Result } ?: 0
        }
    }

    val totalResultsSize by remember {
        derivedStateOf {
            state?.listItems
                ?.filterIsInstance<SearchListItem.Result>()
                ?.sumOf { it.searchItem.size ?: 0L }
                ?: 0L
        }
    }

    state?.let { currentState ->
        // Handle back button for selection mode - clear selection first
        BackHandler(enabled = currentState.selectionState.isSelectionMode) {
            onPageAction(SearcherPageAction.Results.ExitSelectionMode)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .keyboardShortcuts(focusRequester = shortcutsFocusRequester) {
                    on(KeyboardShortcut.Copy) {
                        val selectedResults = currentState.selectionState.selectedResults
                        if (selectedResults.isNotEmpty()) {
                            onPageAction(SearcherPageAction.WorkspaceAction(SearcherAction.Copy(selectedResults)))
                        }
                    }
                    on(KeyboardShortcut.Cut) {
                        val selectedResults = currentState.selectionState.selectedResults
                        if (selectedResults.isNotEmpty()) {
                            onPageAction(SearcherPageAction.WorkspaceAction(SearcherAction.Cut(selectedResults)))
                        }
                    }
                    on(KeyboardShortcut.SelectAll) {
                        if (currentState.selectionState.selectableResults.isNotEmpty()) {
                            onPageAction(SearcherPageAction.WorkspaceAction(SearcherAction.SelectAll))
                        }
                    }
                    on(KeyboardShortcut.Delete) {
                        val selectedResults = currentState.selectionState.selectedResults
                        if (selectedResults.isNotEmpty()) {
                            onPageAction(SearcherPageAction.WorkspaceAction(SearcherAction.Delete(selectedResults)))
                        }
                    }
                    on(KeyboardShortcut.Escape) {
                        if (currentState.selectionState.isSelectionMode) {
                            onPageAction(SearcherPageAction.WorkspaceAction(SearcherAction.DeselectAll))
                        }
                    }
                }
        ) {
            val gridState = rememberLazyGridState()

            // Auto-scroll grid to top when a new search starts
            LaunchedEffect(currentState.workspaceState.searchStatus) {
                if (currentState.workspaceState.searchStatus == SearcherWorkspace.State.SearchStatus.SEARCHING) {
                    gridState.scrollToItem(0)
                }
            }

            // Content padding - automatically calculated by FloatingBarStack
            val contentPaddingValues = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = topBarStackState.contentPaddingDp(),
                bottom = bottomBarStackState.contentPaddingDp(),
            )

            // Conditional rendering: History mode vs Results mode
            val hasNoQuery = currentState.filenameQuery.isBlank() && currentState.contentQuery.isBlank()
            // Show history only when truly idle (cleared state) - hide during/after search
            val showHistory = currentState.isIdle && currentState.searchHistory.isNotEmpty()

            when {
                showHistory -> {
                    // History mode - always LazyColumn
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(topBarStackState.nestedScrollConnection)
                            .nestedScroll(bottomBarStackState.nestedScrollConnection),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = contentPaddingValues
                    ) {
                        // Show templates card when idle and have search targets
                        if (!currentState.isSearching && currentState.searchTargets.isNotEmpty()) {
                            item {
                                TemplatesCard(
                                    onClick = { showTemplatesSheet = true },
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }

                        // Show search history (LazyListScope extension)
                        searchHistorySection(
                            searchHistory = currentState.searchHistory,
                            onHistoryItemClick = { onPageAction(SearcherPageAction.History.Click(it)) },
                            onHistoryItemRemove = { onPageAction(SearcherPageAction.History.Remove(it)) },
                            onShowClearHistoryDialog = { showClearHistoryDialog = true }
                        )
                    }
                }

                else -> {
                    // Results mode - List or Grid based on viewStyle
                    when (val style = currentState.viewStyle) {
                        is SearcherViewStyle.List -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(topBarStackState.nestedScrollConnection)
                                    .nestedScroll(bottomBarStackState.nestedScrollConnection),
                                verticalArrangement = Arrangement.spacedBy(
                                    when (style.density) {
                                        SearcherViewStyle.List.Density.COMPACT -> 4.dp
                                        SearcherViewStyle.List.Density.COMFORTABLE -> 8.dp
                                        SearcherViewStyle.List.Density.DETAILED -> 12.dp
                                    }
                                ),
                                contentPadding = contentPaddingValues
                            ) {
                                // Show setup card if needed
                                if (currentState.needsSetup && currentState.searchTargets.isNotEmpty()) {
                                    item {
                                        PermissionSetupCard(
                                            setupRequirements = currentState.setupRequirements,
                                            onOpenSetup = { onPageAction(SearcherPageAction.Setup.Open(currentState.setupRequirements)) },
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                }

                                // Show empty state when no search targets configured
                                if (currentState.searchTargets.isEmpty()) {
                                    item {
                                        SearchTargetsEmptyStateCard(
                                            onAddDefaultPaths = { onPageAction(SearcherPageAction.Targets.AddDefaultPaths) },
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
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
                                                            onPageAction(SearcherPageAction.Results.Click(item.searchItem))
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
                                                    onCopyError = { onPageAction(SearcherPageAction.Error.Copy(item.throwable)) },
                                                    onDismiss = null,
                                                )
                                            }
                                        }
                                    }
                                }

                                // Empty state placeholder when no query and no history
                                if (hasNoQuery && currentState.searchHistory.isEmpty()) {
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
                        }

                        is SearcherViewStyle.Grid -> {
                            val minSize = when (style.size) {
                                SearcherViewStyle.Grid.GridSize.SMALL -> 90.dp
                                SearcherViewStyle.Grid.GridSize.MEDIUM -> 120.dp
                                SearcherViewStyle.Grid.GridSize.LARGE -> 160.dp
                            }

                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = minSize),
                                state = gridState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(topBarStackState.nestedScrollConnection)
                                    .nestedScroll(bottomBarStackState.nestedScrollConnection),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = contentPaddingValues
                            ) {
                                // Show setup card if needed
                                if (currentState.needsSetup && currentState.searchTargets.isNotEmpty()) {
                                    item {
                                        PermissionSetupCard(
                                            setupRequirements = currentState.setupRequirements,
                                            onOpenSetup = { onPageAction(SearcherPageAction.Setup.Open(currentState.setupRequirements)) },
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                }

                                // Show empty state when no search targets configured
                                if (currentState.searchTargets.isEmpty()) {
                                    item {
                                        SearchTargetsEmptyStateCard(
                                            onAddDefaultPaths = { onPageAction(SearcherPageAction.Targets.AddDefaultPaths) },
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                }

                                // Search results (grid mode - only results, no errors)
                                if (currentState.listItems.isNotEmpty()) {
                                    items(
                                        items = currentState.listItems.filterIsInstance<SearchListItem.Result>(),
                                        key = { item -> item.searchItem.path.path }
                                    ) { item ->
                                        SelectableFileGrid(
                                            result = item.searchItem,
                                            isSelected = currentState.selectionState.isSelected(item.searchItem),
                                            isSelectionMode = currentState.selectionState.isSelectionMode,
                                            onClick = {
                                                if (currentState.selectionState.isSelectionMode) {
                                                    wrappedOnToggleSelection(item.searchItem)
                                                } else {
                                                    onPageAction(SearcherPageAction.Results.Click(item.searchItem))
                                                }
                                            },
                                            onLongPress = {
                                                wrappedOnEnterSelectionMode(item.searchItem)
                                            },
                                        )
                                    }
                                }

                                // Empty state placeholder when no query and no history
                                if (hasNoQuery && currentState.searchHistory.isEmpty()) {
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
                        }
                    }
                }
            }

            // Error dialog state (declared before FloatingBarStack that uses it)
            var errorDialogState by remember { mutableStateOf<Pair<String, Throwable>?>(null) }

            // Top FloatingBarStack - toolbar, progress card, info bar
            FloatingBarStack(
                state = topBarStackState,
                position = BarPosition.TOP,
                modifier = Modifier.align(Alignment.TopCenter),
                bars = {
                    // Toolbar - closest to top edge, collapses on scroll
                    FloatingBar(
                        visible = true,
                        scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                        animation = BarAnimation.Slide(),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        SearchToolbarCard(
                            workspaceId = workspaceId,
                            state = currentState,
                            design = design,
                            collapsedFraction = collapsedFraction,
                            onUpdateFilenameQuery = { onPageAction(SearcherPageAction.Search.UpdateFilenameQuery(it)) },
                            onUpdateContentQuery = { onPageAction(SearcherPageAction.Search.UpdateContentQuery(it)) },
                            onRemoveSearchPath = { onPageAction(SearcherPageAction.Targets.Remove(it)) },
                            onTogglePathEnabled = { onPageAction(SearcherPageAction.Targets.ToggleEnabled(it)) },
                            onPerformSearch = { onPageAction(SearcherPageAction.Search.Perform) },
                            onExplicitSearch = { onPageAction(SearcherPageAction.Search.Explicit) },
                            onCancelSearch = { onPageAction(SearcherPageAction.Search.Cancel) },
                            onToggleFilenameCaseSensitive = { onPageAction(SearcherPageAction.Options.ToggleFilenameCaseSensitive) },
                            onToggleFilenameWholeWord = { onPageAction(SearcherPageAction.Options.ToggleFilenameWholeWord) },
                            onToggleFilenameRegex = { onPageAction(SearcherPageAction.Options.ToggleFilenameRegex) },
                            onToggleContentCaseSensitive = { onPageAction(SearcherPageAction.Options.ToggleContentCaseSensitive) },
                            onToggleContentWholeWord = { onPageAction(SearcherPageAction.Options.ToggleContentWholeWord) },
                            onToggleContentRegex = { onPageAction(SearcherPageAction.Options.ToggleContentRegex) },
                            onToggleContentSearch = { onPageAction(SearcherPageAction.Options.ToggleContentSearch) },
                            onOpenPathPicker = { onPageAction(SearcherPageAction.Targets.OpenPicker) },
                            onConditionClick = { onPageAction(SearcherPageAction.Filter.EditCondition(it)) },
                            onAddSizeCondition = { onPageAction(SearcherPageAction.Filter.OpenSizeConditionEditor) },
                            onAddDateCondition = { onPageAction(SearcherPageAction.Filter.OpenDateConditionEditor) },
                            onAddTypeCondition = { onPageAction(SearcherPageAction.Filter.OpenTypeConditionEditor) },
                            onRemoveCondition = { onPageAction(SearcherPageAction.Filter.RemoveCondition(it)) },
                            workspaceButtonState = workspaceButtonState,
                            workspaceActionHandler = workspaceActionHandler,
                        )
                    }

                    // Progress card - vanishes on scroll
                    FloatingBar(
                        visible = showProgressCard,
                        scrollBehavior = BarScrollBehavior.VanishOnScroll,
                        animation = BarAnimation.Slide(),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        SearchProgressCard(
                            targetProgress = currentState.workspaceState.targetProgress,
                            overallProgress = currentState.workspaceState.progress,
                            searchStatus = currentState.workspaceState.searchStatus,
                            onCancel = { onPageAction(SearcherPageAction.Search.Cancel) },
                            onClear = { onPageAction(SearcherPageAction.Search.ClearResults) },
                            onErrorClick = { path, exception ->
                                errorDialogState = path to exception
                            },
                        )
                    }

                    // Info bar - static (stays visible when results or selection)
                    FloatingBar(
                        visible = showInfoBar,
                        scrollBehavior = BarScrollBehavior.Static,
                        animation = BarAnimation.Slide(),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        SearcherInfoBar(
                            resultsCount = resultsCount,
                            totalSize = totalResultsSize,
                            selectedCount = currentState.selectionState.selectionCount,
                            onClearSelection = { onPageAction(SearcherPageAction.Results.ExitSelectionMode) },
                        )
                    }
                },
                content = {},
            )

            // Bottom FloatingBarStack - operations, clipboard, action bar
            FloatingBarStack(
                state = bottomBarStackState,
                position = BarPosition.BOTTOM,
                modifier = Modifier.align(Alignment.BottomCenter),
                bars = {
                    // Operations bar - furthest from bottom edge
                    // Static when active operations, VanishOnScroll when only completed
                    FloatingBar(
                        visible = hasOperations,
                        scrollBehavior = if (hasActiveOperations) BarScrollBehavior.Static else BarScrollBehavior.VanishOnScroll,
                        animation = BarAnimation.Slide(),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        OperationsBar(
                            operations = operationsState.operations,
                            onCancelOperation = { onPageAction(SearcherPageAction.Operations.Cancel(it)) },
                            onDismissOperation = { onPageAction(SearcherPageAction.Operations.Dismiss(it)) },
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
                            onClearCompleted = { onPageAction(SearcherPageAction.Operations.ClearCompleted) },
                        )
                    }

                    // Clipboard bar - middle, vanishes on scroll with bouncy animation
                    FloatingBar(
                        visible = hasClipboard,
                        scrollBehavior = BarScrollBehavior.VanishOnScroll,
                        animation = BarAnimation.Bouncy,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        ClipboardBar(
                            workspaceType = Workspace.Type.SEARCHER,
                            clipboardEntries = clipboardState.entries,
                            onPasteClick = { clip -> vm?.openClipboardInExplorer(clip) },
                            onRemoveClick = { onPageAction(SearcherPageAction.Clipboard.RemoveEntry(it)) },
                            onEntryClick = { onPageAction(SearcherPageAction.Clipboard.ClickEntry(it)) },
                            onClearAll = { onPageAction(SearcherPageAction.Clipboard.ClearAll) },
                        )
                    }

                    // Action bar - closest to bottom edge, hides on scroll
                    FloatingBar(
                        visible = hasActions,
                        scrollBehavior = BarScrollBehavior.HideOnScroll,
                        animation = BarAnimation.Slide(),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        WorkspaceActionBar(
                            actions = currentState.availableActions,
                            onActionClick = { action ->
                                when (val searcherAction = action as SearcherAction) {
                                    is SearcherAction.DeselectAll -> onPageAction(SearcherPageAction.Results.ExitSelectionMode)
                                    else -> onPageAction(SearcherPageAction.WorkspaceAction(searcherAction))
                                }
                            },
                            onActionLongClick = { action ->
                                vm?.onActionLongClick(action as SearcherAction)
                            },
                        )
                    }
                },
                content = {},
            )

            // Error dialog for individual search target failures
            errorDialogState?.let { (path, exception) ->
                SearchErrorDialog(
                    path = path,
                    exception = exception,
                    onCopyError = {
                        onPageAction(SearcherPageAction.Error.Copy(exception))
                        errorDialogState = null
                    },
                    onDismiss = { errorDialogState = null }
                )
            }

            // Templates bottom sheet
            PaneScopedBottomSheet(
                visible = showTemplatesSheet,
                onDismiss = { showTemplatesSheet = false },
            ) {
                TemplatesBottomSheetContent(
                    onTemplateClick = { template ->
                        showTemplatesSheet = false
                        onPageAction(SearcherPageAction.Templates.Apply(template))
                    },
                )
            }
        }

        // Item details bottom sheet
        currentState.quickActionsResult?.let { result ->
            SearchResultItemDetails(
                result = result,
                trashEnabled = currentState.trashEnabled,
                onAction = { action ->
                    onPageAction(SearcherPageAction.WorkspaceAction(action))
                    onPageAction(SearcherPageAction.Results.HideQuickActions)
                },
                onLongPress = {
                    wrappedOnEnterSelectionMode(it)
                    onPageAction(SearcherPageAction.Results.HideQuickActions)
                },
                onDismiss = { onPageAction(SearcherPageAction.Results.HideQuickActions) },
                bottomInset = navBarInset,
            )
        }

        // Issue/conflict resolution bottom sheet
        val issueState by (vm?.issueState?.collectAsState() ?: remember { mutableStateOf(null) })
        if (issueState != null) {
            eu.darken.butler.workspace.ui.issues.IssuesBottomSheet(
                issue = issueState!!,
                onResolution = { resolution -> vm?.resolveIssue(resolution) },
                onDismiss = { /* Issue will auto-clear when resolved or cancelled */ },
                bottomInset = navBarInset,
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
                            onPageAction(SearcherPageAction.History.Clear)
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
                        Text(text = stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                    }
                }
            )
        }

        // Dialog host
        SearcherDialogHost(
            dialogState = currentState.dialogState,
            trashEnabled = currentState.trashEnabled,
            onDismiss = { vm?.dismissDialog() },
            onDeleteConfirmed = { items, forcePermDelete -> vm?.onDeleteConfirmed(items, forcePermDelete) },
            onCopyToClipboard = { text -> vm?.copyPathToSystemClipboard(text) },
            onNavigateToClipboardSource = { clip -> vm?.navigateToClipboardSource(clip) },
            onRemoveClipboardEntry = { clip -> vm?.removeClipboardEntry(clip) },
            onSortOptionsConfirmed = { vm?.onSortOptions(it) },
        )

        // Size condition edit bottom sheet
        val sizeConditionState = currentState.dialogState as? SearcherDialogState.EditSizeCondition
        SizeConditionEditSheet(
            visible = sizeConditionState != null,
            existingCondition = sizeConditionState?.existing,
            onDismiss = { vm?.dismissDialog() },
            onApply = { newCondition ->
                // Remove existing condition if editing, then add new one
                sizeConditionState?.existing?.let {
                    onPageAction(SearcherPageAction.Filter.RemoveCondition(it))
                }
                onPageAction(SearcherPageAction.Filter.AddCondition(newCondition))
            },
            bottomInset = navBarInset,
        )

        // Date condition edit bottom sheet
        val dateConditionState = currentState.dialogState as? SearcherDialogState.EditDateCondition
        DateConditionEditSheet(
            visible = dateConditionState != null,
            existingCondition = dateConditionState?.existing,
            onDismiss = { vm?.dismissDialog() },
            onApply = { newCondition ->
                // Remove existing condition if editing, then add new one
                dateConditionState?.existing?.let {
                    onPageAction(SearcherPageAction.Filter.RemoveCondition(it))
                }
                onPageAction(SearcherPageAction.Filter.AddCondition(newCondition))
            },
            bottomInset = navBarInset,
        )

        // Type condition edit bottom sheet
        val typeConditionState = currentState.dialogState as? SearcherDialogState.EditTypeCondition
        TypeConditionEditSheet(
            visible = typeConditionState != null,
            existingCondition = typeConditionState?.existing,
            onDismiss = { vm?.dismissDialog() },
            onApply = { newCondition ->
                // Remove existing condition if editing, then add new one
                typeConditionState?.existing?.let {
                    onPageAction(SearcherPageAction.Filter.RemoveCondition(it))
                }
                onPageAction(SearcherPageAction.Filter.AddCondition(newCondition))
            },
            bottomInset = navBarInset,
        )

        // Operation dialog host
        OperationDialogHost(
            dialogState = operationDialogState,
            operations = operationsState.operations,
            onDismissDialog = { operationDialogState = OperationDialogState.None },
            onCancelOperation = { operationId ->
                operationDialogState = OperationDialogState.None
                vm?.cancelOperation(operationId)
            },
            onCopyError = { vm?.copyError(it) },
            onHandleIssue = { operationId ->
                vm?.showConflictSheet(operationId)
            },
        )
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
    NavigationEventHandler(vm, workspaceButtonVm)

    SearcherWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        clipboardStateSource = vm.clipboard,
        operationsStateSource = vm.operations,
        workspaceStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
        onPageAction = vm::onPageAction,
    )
}

@Preview2
@Composable
private fun SearcherWorkspacePageEmptyPreview() {
    PreviewWrapper {
        val workspaceId = Workspace.Id()
        SearcherWorkspacePage(
            workspaceId = workspaceId,
            stateSource = flowOf(SearcherMockDataProvider.createMockEmptyState(workspaceId)),
            clipboardStateSource = flowOf(SearcherWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(SearcherWorkspaceViewModel.OperationsState()),
            workspaceStateSource = flowOf(null),
            onPageAction = {},
        )
    }
}

@Preview2
@Composable
private fun SearcherWorkspacePageWithHistoryPreview() {
    PreviewWrapper {
        val workspaceId = Workspace.Id()
        SearcherWorkspacePage(
            workspaceId = workspaceId,
            stateSource = flowOf(SearcherMockDataProvider.createMockHistoryState(workspaceId)),
            clipboardStateSource = flowOf(SearcherWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(SearcherWorkspaceViewModel.OperationsState()),
            workspaceStateSource = flowOf(null),
            onPageAction = {},
        )
    }
}

@Preview2
@Composable
private fun SearcherWorkspacePageWithResultsPreview() {
    PreviewWrapper {
        val workspaceId = Workspace.Id()
        SearcherWorkspacePage(
            workspaceId = workspaceId,
            stateSource = flowOf(SearcherMockDataProvider.createMockResultsState(workspaceId)),
            clipboardStateSource = flowOf(SearcherWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(SearcherWorkspaceViewModel.OperationsState()),
            workspaceStateSource = flowOf(null),
            onPageAction = {},
        )
    }
}

@Preview2
@Composable
private fun SearcherWorkspacePageSearchingWithProgressPreview() {
    PreviewWrapper {
        val workspaceId = Workspace.Id()
        SearcherWorkspacePage(
            workspaceId = workspaceId,
            stateSource = flowOf(SearcherMockDataProvider.createMockSearchingWithProgressState(workspaceId)),
            clipboardStateSource = flowOf(SearcherWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(SearcherWorkspaceViewModel.OperationsState()),
            workspaceStateSource = flowOf(null),
            onPageAction = {},
        )
    }
}
