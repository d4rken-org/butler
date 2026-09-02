package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.OnValueChange
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.dragselect.gridDragSelect
import eu.darken.butler.common.compose.dragselect.listDragSelect
import eu.darken.butler.common.keyboard.KeyboardShortcut
import eu.darken.butler.common.keyboard.keyboardShortcuts
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.resultKey
import eu.darken.butler.searcher.ui.search.dnd.SearcherDragPayloadFactory
import eu.darken.butler.searcher.ui.search.elements.PermissionSetupCard
import eu.darken.butler.searcher.ui.search.elements.SearchTargetsEmptyStateCard
import eu.darken.butler.searcher.ui.search.elements.SearcherBottomBars
import eu.darken.butler.searcher.ui.search.elements.SearcherTopBars
import eu.darken.butler.searcher.ui.search.elements.TemplatesCard
import eu.darken.butler.searcher.ui.search.elements.searchHistorySection
import eu.darken.butler.searcher.ui.search.items.SelectableFileGrid
import eu.darken.butler.searcher.ui.search.items.SelectableFileRow
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.util.SearchListItem
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dnd.rememberWorkspaceDragSource
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.error.ErrorCard
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.preview.ProvideFolderPreviews
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyGridState
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyListState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

@Composable
fun SearcherWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    stateSource: Flow<SearcherWorkspaceViewModel.State>,
    clipboardStateSource: Flow<ClipboardDisplayState?>,
    operationsStateSource: Flow<OperationsDisplayState?>,
    onPageAction: (SearcherPageAction) -> Unit = {},
) {
    // StateFlow check: use current value as initial for single-frame renderers (screenshot tests, previews)
    val mainState by stateSource.collectAsState(
        initial = (stateSource as? StateFlow)?.value ?: SearcherWorkspaceViewModel.State.Initializing
    )
    val clipboardStateRaw by clipboardStateSource.collectAsState(initial = null)
    val clipboardState = clipboardStateRaw ?: ClipboardDisplayState()
    val operationsStateRaw by operationsStateSource.collectAsState(initial = null)
    val operationsState = operationsStateRaw ?: OperationsDisplayState()

    // Setup and remember blocks at top level
    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
        estimatedContentPadding = 192.dp,
    )
    val bottomBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.BOTTOM,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        design = design,
        estimatedContentPadding = 80.dp,
    )
    val listState = rememberWorkspaceLazyListState(workspaceId, slot = SearcherScrollSlots.RESULTS_LIST)
    // Hoisted so the search-start reset covers list and grid in one guarded effect
    val gridState = rememberWorkspaceLazyGridState(workspaceId, slot = SearcherScrollSlots.RESULTS_GRID)
    val idleListState = rememberWorkspaceLazyListState(workspaceId, slot = SearcherScrollSlots.IDLE_LIST)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val shortcutsFocusRequester = remember { FocusRequester() }
    val isWorkspaceFocused = LocalWorkspaceFocused.current

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
            val readyState = mainState as? SearcherWorkspaceViewModel.State.Ready
            if (readyState?.selectionState?.isSelectionMode != true) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            currentOnPageAction(SearcherPageAction.Results.ToggleSelection(result))
        }
    }

    // Re-request focus for keyboard shortcuts after clearing focus
    // This ensures shortcuts continue working after selecting a result
    val isSelectionMode =
        (mainState as? SearcherWorkspaceViewModel.State.Ready)?.selectionState?.isSelectionMode == true
    LaunchedEffect(isSelectionMode) {
        if (isSelectionMode) {
            delay(50) // Small delay to let keyboard animation complete
            shortcutsFocusRequester.requestFocus()
        }
    }

    // Auto-scroll to top when sort settings change. Only a change between two known sort settings
    // counts - the first Ready state merely reveals them.
    val sortSettings = (mainState as? SearcherWorkspaceViewModel.State.Ready)?.sortSettings
    OnValueChange(sortSettings) { previous, current ->
        if (previous == null || current == null) return@OnValueChange
        listState.animateScrollToItem(0)
    }

    // Auto-scroll to top when a new search starts. Guarded on the transition, not on the value:
    // an unguarded effect resets to top on every recomposition that happens while a search runs,
    // which is exactly what a pane move or a rotation does.
    val searchStatus = (mainState as? SearcherWorkspaceViewModel.State.Ready)?.workspaceState?.searchStatus
    OnValueChange(searchStatus) { previous, current ->
        if (previous == null || current != SearcherWorkspace.State.SearchStatus.SEARCHING) {
            return@OnValueChange
        }
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    // A new search means fresh content underneath the bars; reset scroll-collapse so bars don't
    // stay hidden over content the user hasn't scrolled yet. Guarded on the transition like the
    // scroll reset above: unguarded it re-fires on every recomposition during a running search and
    // would undo the collapse state this workspace just restored.
    OnValueChange(searchStatus) { previous, current ->
        if (previous == null || current != SearcherWorkspace.State.SearchStatus.SEARCHING) {
            return@OnValueChange
        }
        topBarStackState.resetScrollCollapse()
        bottomBarStackState.resetScrollCollapse()
    }

    // Only render when Ready - WorkspaceMapper handles Init/Error overlays
    val currentState = mainState as? SearcherWorkspaceViewModel.State.Ready ?: return

    // Dragging results to another pane needs a second pane to drop them on. The payload comes from
    // the state this composition already holds, so a drag can't lose items to an in-flight update.
    val dragsToOtherPanes = !design.isSingle

    // Handle back button for selection mode - clear selection first
    WorkspaceBackHandler(enabled = currentState.selectionState.isSelectionMode) {
        onPageAction(SearcherPageAction.Results.ExitSelectionMode)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .keyboardShortcuts(focusRequester = shortcutsFocusRequester, enabled = isWorkspaceFocused) {
                on(KeyboardShortcut.Copy) {
                    val selectedResults = currentState.selectionState.selectedResults
                    if (selectedResults.isNotEmpty()) {
                        onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.Copy(selectedResults)))
                    }
                }
                on(KeyboardShortcut.Cut) {
                    val selectedResults = currentState.selectionState.selectedResults
                    if (selectedResults.isNotEmpty()) {
                        onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.Cut(selectedResults)))
                    }
                }
                on(KeyboardShortcut.SelectAll) {
                    if (currentState.selectionState.selectableResults.isNotEmpty()) {
                        onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.SelectAll))
                    }
                }
                on(KeyboardShortcut.Delete) {
                    val selectedResults = currentState.selectionState.selectedResults
                    if (selectedResults.isNotEmpty()) {
                        onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.Delete(selectedResults)))
                    }
                }
                on(KeyboardShortcut.Escape) {
                    if (currentState.selectionState.isSelectionMode) {
                        onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.DeselectAll))
                    }
                }
            }
    ) {
        // Folder previews load only once scrolling has settled ~120ms. Asymmetric on purpose —
        // false immediately on scroll start, so no new preview work begins during the gesture.
        val previewsSettled = remember { mutableStateOf(true) }
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.isScrollInProgress }.collectLatest { scrolling ->
                if (scrolling) {
                    previewsSettled.value = false
                } else {
                    delay(120)
                    previewsSettled.value = true
                }
            }
        }

        val contentPaddingValues = rememberFloatingBarContentPadding(
            topStackState = topBarStackState,
            bottomStackState = bottomBarStackState,
            start = WorkspacePaddings.ContentHorizontal,
            end = WorkspacePaddings.ContentHorizontal,
        )
        val gridContentPaddingValues = rememberFloatingBarContentPadding(
            topStackState = topBarStackState,
            bottomStackState = bottomBarStackState,
            start = WorkspacePaddings.GridHorizontal,
            end = WorkspacePaddings.GridHorizontal,
        )

        // Conditional rendering: Idle state (templates + history) vs Results mode
        val hasNoQuery = currentState.filenameQuery.isBlank() &&
            (!currentState.contentSearchEnabled || currentState.contentQuery.isBlank())
        // Show idle state when idle with no results (regardless of query text in input)
        val showIdleState = currentState.isIdle && !currentState.hasResults && currentState.workspaceState.searchTargets.isNotEmpty()

        when {
            // Idle state - show templates card and optionally history
            showIdleState -> LazyColumn(
                state = idleListState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(topBarStackState.nestedScrollConnection)
                    .nestedScroll(bottomBarStackState.nestedScrollConnection),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = contentPaddingValues
            ) {
                // Show templates card when idle and have search targets
                if (!currentState.isSearching && currentState.workspaceState.searchTargets.isNotEmpty()) {
                    item {
                        TemplatesCard(
                            onClick = { onPageAction(SearcherPageAction.Overlays.ShowTemplates) },
                        )
                    }
                }

                // Show search history if available
                if (currentState.searchHistory.isNotEmpty()) {
                    searchHistorySection(
                        searchHistory = currentState.searchHistory,
                        onHistoryItemClick = { onPageAction(SearcherPageAction.History.Click(it)) },
                        onHistoryItemRemove = { onPageAction(SearcherPageAction.History.Remove(it)) },
                        onShowClearHistoryDialog = { onPageAction(SearcherPageAction.History.ShowClearDialog) },
                    )
                }
            }

            // Results mode - List or Grid based on viewStyle
            else -> when (val style = currentState.viewStyle) {
                is SearcherViewStyle.List -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(topBarStackState.nestedScrollConnection)
                            .nestedScroll(bottomBarStackState.nestedScrollConnection)
                            .listDragSelect(
                                state = listState,
                                orderedKeys = { currentState.resultKeys() },
                                currentSelection = { currentState.selectionState.selectedResultIds },
                                onSelectionChange = { onPageAction(SearcherPageAction.Results.SetSelection(it)) },
                                // Every result is draggable - SearcherDragPayloadFactory has no
                                // per-item null case - so the pane/selection test is exact and no
                                // press ends up owned by neither gesture. In selection mode an
                                // unselected item is still claimed by drag-select, only already
                                // selected items fall through to the cross-pane drag.
                                enabled = { key ->
                                    !dragsToOtherPanes ||
                                        !currentState.selectionState.isSelectionMode ||
                                        key !in currentState.selectionState.selectedResultIds
                                },
                            ),
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
                        if (currentState.needsSetup && currentState.workspaceState.searchTargets.isNotEmpty()) {
                            item {
                                PermissionSetupCard(
                                    setupRequirements = currentState.workspaceState.setupRequirements,
                                    onOpenSetup = { onPageAction(SearcherPageAction.Setup.Open(currentState.workspaceState.setupRequirements)) },
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }

                        // Show empty state when no search targets configured
                        if (currentState.workspaceState.searchTargets.isEmpty()) {
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
                                        is SearchListItem.Result -> item.searchItem.resultKey
                                        is SearchListItem.Error -> "error"
                                    }
                                },
                                contentType = { item ->
                                    when (item) {
                                        is SearchListItem.Result -> "result"
                                        is SearchListItem.Error -> "error"
                                    }
                                },
                            ) { item ->
                                when (item) {
                                    is SearchListItem.Result -> {
                                        val dragSource = if (dragsToOtherPanes) {
                                            rememberWorkspaceDragSource {
                                                SearcherDragPayloadFactory.build(
                                                    currentState,
                                                    workspaceId,
                                                    item.searchItem,
                                                )
                                            }
                                        } else {
                                            null
                                        }
                                        SelectableFileRow(
                                            modifier = dragSource?.modifier ?: Modifier,
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
                                                // The cross-pane drag starts only from an already
                                                // selected item; long-pressing an unselected item
                                                // in selection mode extends the selection instead.
                                                if (currentState.selectionState.isSelectionMode &&
                                                    item.searchItem.resultKey in currentState.selectionState.selectedResultIds
                                                ) {
                                                    dragSource?.startDrag()
                                                }
                                                wrappedOnEnterSelectionMode(item.searchItem)
                                            },
                                        )
                                    }

                                    is SearchListItem.Error -> {
                                        ErrorCard(
                                            title = stringResource(R.string.searcher_search_error),
                                            error = item.throwable,
                                            onShareError = { onPageAction(SearcherPageAction.Error.Share(item.throwable)) },
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

                    val gridResultItems = remember(currentState.listItems) {
                        currentState.listItems.filterIsInstance<SearchListItem.Result>()
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = minSize),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(topBarStackState.nestedScrollConnection)
                            .nestedScroll(bottomBarStackState.nestedScrollConnection)
                            .gridDragSelect(
                                state = gridState,
                                orderedKeys = { currentState.resultKeys() },
                                currentSelection = { currentState.selectionState.selectedResultIds },
                                onSelectionChange = { onPageAction(SearcherPageAction.Results.SetSelection(it)) },
                                enabled = { key ->
                                    !dragsToOtherPanes ||
                                        !currentState.selectionState.isSelectionMode ||
                                        key !in currentState.selectionState.selectedResultIds
                                },
                                contentPadding = gridContentPaddingValues,
                            ),
                        verticalArrangement = Arrangement.spacedBy(WorkspacePaddings.GridGutter),
                        horizontalArrangement = Arrangement.spacedBy(WorkspacePaddings.GridGutter),
                        contentPadding = gridContentPaddingValues
                    ) {
                        // Show setup card if needed
                        if (currentState.needsSetup && currentState.workspaceState.searchTargets.isNotEmpty()) {
                            item {
                                PermissionSetupCard(
                                    setupRequirements = currentState.workspaceState.setupRequirements,
                                    onOpenSetup = { onPageAction(SearcherPageAction.Setup.Open(currentState.workspaceState.setupRequirements)) },
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }

                        // Show empty state when no search targets configured
                        if (currentState.workspaceState.searchTargets.isEmpty()) {
                            item {
                                SearchTargetsEmptyStateCard(
                                    onAddDefaultPaths = { onPageAction(SearcherPageAction.Targets.AddDefaultPaths) },
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }

                        // Search results (grid mode - only results, no errors)
                        if (gridResultItems.isNotEmpty()) {
                            items(
                                items = gridResultItems,
                                key = { item -> item.searchItem.resultKey },
                                contentType = { "result" },
                            ) { item ->
                                val dragSource = if (dragsToOtherPanes) {
                                    rememberWorkspaceDragSource {
                                        SearcherDragPayloadFactory.build(
                                            currentState,
                                            workspaceId,
                                            item.searchItem,
                                        )
                                    }
                                } else {
                                    null
                                }
                                SelectableFileGrid(
                                    modifier = dragSource?.modifier ?: Modifier,
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
                                        // See the list branch: only a long press on an already
                                        // selected item arms the cross-pane drag.
                                        if (currentState.selectionState.isSelectionMode &&
                                            item.searchItem.resultKey in currentState.selectionState.selectedResultIds
                                        ) {
                                            dragSource?.startDrag()
                                        }
                                        wrappedOnEnterSelectionMode(item.searchItem)
                                    },
                                    previewsSettled = previewsSettled,
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

        // Top FloatingBarStack - toolbar, progress card, info bar
        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                SearcherTopBars(
                    workspaceId = workspaceId,
                    design = design,
                    state = currentState,
                    onPageAction = onPageAction,
                )
            },
        )

        // Bottom FloatingBarStack - operations, clipboard, action bar
        FloatingBarStack(
            state = bottomBarStackState,
            position = BarPosition.BOTTOM,
            modifier = Modifier.align(Alignment.BottomCenter),
            bars = {
                SearcherBottomBars(
                    state = currentState,
                    operationsState = operationsState,
                    clipboardState = clipboardState,
                    onPageAction = onPageAction,
                )
            },
        )

    }

    // Dialogs and sheets live in the page host's overlay slot, see SearcherWorkspaceOverlays
}

/**
 * The keys a drag may sweep over, in display order. Errors and placeholder cards are not results
 * and therefore not selectable; the range simply spans them.
 */
private fun SearcherWorkspaceViewModel.State.Ready.resultKeys(): List<String> = listItems
    .filterIsInstance<SearchListItem.Result>()
    .map { it.searchItem.resultKey }

@Composable
fun SearcherWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SearcherWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SearcherWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    NavigationEventHandler(vm)

    // Handle share intent events
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.shareIntentEvent.collect { intent ->
            context.startActivity(intent)
        }
    }

    ProvideFolderPreviews(vm.folderPreviewObserver) {
        SearcherWorkspacePage(
            workspaceId = id,
            design = design,
            stateSource = vm.state,
            clipboardStateSource = vm.clipboard,
            operationsStateSource = vm.operations,
            onPageAction = vm::onPageAction,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherWorkspacePageEmptyPreview() {
    val workspaceId = Workspace.Id()
    SearcherWorkspacePage(
        workspaceId = workspaceId,
        stateSource = flowOf(SearcherMockDataProvider.createMockEmptyState()),
        clipboardStateSource = flowOf(ClipboardDisplayState()),
        operationsStateSource = flowOf(OperationsDisplayState()),
        onPageAction = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherWorkspacePageWithHistoryPreview() {
    val workspaceId = Workspace.Id()
    SearcherWorkspacePage(
        workspaceId = workspaceId,
        stateSource = flowOf(SearcherMockDataProvider.createMockHistoryState()),
        clipboardStateSource = flowOf(ClipboardDisplayState()),
        operationsStateSource = flowOf(OperationsDisplayState()),
        onPageAction = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherWorkspacePageWithResultsPreview() {
    val workspaceId = Workspace.Id()
    SearcherWorkspacePage(
        workspaceId = workspaceId,
        stateSource = flowOf(SearcherMockDataProvider.createMockResultsState()),
        clipboardStateSource = flowOf(ClipboardDisplayState()),
        operationsStateSource = flowOf(OperationsDisplayState()),
        onPageAction = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherWorkspacePageSearchingWithProgressPreview() {
    val workspaceId = Workspace.Id()
    SearcherWorkspacePage(
        workspaceId = workspaceId,
        stateSource = flowOf(SearcherMockDataProvider.createMockSearchingWithProgressState()),
        clipboardStateSource = flowOf(ClipboardDisplayState()),
        operationsStateSource = flowOf(OperationsDisplayState()),
        onPageAction = {},
    )
}
