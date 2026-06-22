package eu.darken.butler.explorer.ui.explorer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.rememberDelayedState
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.dialogs.AddDeviceStorageSheet
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogHost
import eu.darken.butler.explorer.ui.explorer.elements.EmptyDirectoryState
import eu.darken.butler.explorer.core.favorites.PendingFavoriteRemoval
import eu.darken.butler.explorer.ui.explorer.elements.FavoritesUndoBar
import eu.darken.butler.explorer.ui.explorer.elements.favoritesSection
import eu.darken.butler.explorer.ui.explorer.elements.EmptyFilteredState
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerInfoBar
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerToolbarCard
import eu.darken.butler.explorer.ui.explorer.elements.PermissionRequestCard
import eu.darken.butler.explorer.ui.explorer.elements.SkeletonGridItem
import eu.darken.butler.explorer.ui.explorer.elements.SkeletonListItem
import eu.darken.butler.explorer.ui.explorer.items.ExplorerItemRenderer
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.OpenDocumentTreeWithIntent
import eu.darken.butler.explorer.ui.explorer.util.explorerKeyboardShortcuts
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBar
import eu.darken.butler.workspace.ui.error.ErrorCard
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.bar.OperationsBar
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationDialog
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Composable
fun ExplorerWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    mainStateSource: Flow<ExplorerWorkspaceViewModel.State?>,
    operationsStateSource: Flow<OperationsDisplayState?>,
    clipboardStateSource: Flow<ClipboardDisplayState?>,
    vm: ExplorerWorkspaceViewModel? = null,
    initialOperationsExpanded: Boolean = false,
    initialClipboardExpanded: Boolean = false,
) {
    // Early return - don't render until state is available (mapper shows loading)
    // StateFlow check: use current value as initial for single-frame renderers (screenshot tests, previews)
    val nullableState by mainStateSource.collectAsState(initial = (mainStateSource as? StateFlow)?.value)
    val state = nullableState ?: return

    val coroutineScope = rememberCoroutineScope()
    val operationsStateRaw by operationsStateSource.collectAsState(initial = null)
    val operationsState = operationsStateRaw ?: OperationsDisplayState()
    val clipboardStateRaw by clipboardStateSource.collectAsState(initial = null)
    val clipboardState = clipboardStateRaw ?: ClipboardDisplayState()
    val isWorkspaceFocused = LocalWorkspaceFocused.current

    val topBarStackState = rememberFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        includeSystemBarInset = design.paneEdges.touchesTop,
        estimatedContentPadding = 196.dp,
    )
    val bottomBarStackState = rememberFloatingBarStackState(
        position = BarPosition.BOTTOM,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        includeSystemBarInset = design.paneEdges.touchesBottom,
        estimatedContentPadding = 80.dp,
    )
    val density = LocalDensity.current
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else {
        0.dp
    }

    // Observe conflict state
    val issueState by (vm?.issueState?.collectAsState() ?: remember { mutableStateOf(null) })
    var showIssueSheet by remember { mutableStateOf(false) }

    // Listen for requests to show conflict sheet (manual trigger only)
    LaunchedEffect(vm) {
        vm?.showIssueSheetEvent?.collect { showIssueSheet = true }
    }

    // Operation dialog state
    var operationDialogState by remember { mutableStateOf<OperationDialogState>(OperationDialogState.None) }
    var showCancelConfirmation by remember { mutableStateOf<Operation.Id?>(null) }

    // Progress indicator delay state - shows after 200ms to avoid flickering
    val showProgress = rememberDelayedState(state.progress, delayMs = 200)

    // List and grid scroll states - keyed on locationId for clean slate each navigation
    val listState = key(state.locationId) { rememberLazyListState() }
    val gridState = key(state.locationId) { rememberLazyGridState() }

    // Navigation resets floating-bar scroll-collapse so bars don't stay hidden over new content
    LaunchedEffect(state.locationId) {
        topBarStackState.resetScrollCollapse()
        bottomBarStackState.resetScrollCollapse()
    }

    // Pull-to-refresh state
    var showPullToRefreshIndicator by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    // Derived states for stable recomposition
    val hasOperations by derivedStateOf { operationsState.operations.isNotEmpty() }
    val hasActiveOperations by derivedStateOf {
        operationsState.operations.any { op ->
            op.state is OperationDisplay.State.Queued ||
                op.state is OperationDisplay.State.Running ||
                op.state is OperationDisplay.State.Waiting
        }
    }
    val hasClipboard by derivedStateOf { clipboardState.entries.isNotEmpty() }
    val hasActions by derivedStateOf { state.availableActions.isNotEmpty() }

    // Cache the last non-null pending favorite removal so the undo bar has content to
    // animate out when the underlying state transitions back to null. FloatingBar keeps
    // its content composed during the slide-out, so the lambda must always have data.
    var lastPendingRemoval by remember { mutableStateOf<PendingFavoriteRemoval?>(null) }
    state.pendingFavoriteRemoval?.let { lastPendingRemoval = it }
    val showFavoritesUndoBar = state.pendingFavoriteRemoval != null && state.pickerConfig == null
    val isLoadingItems = state.items == null && state.error == null
    val hasItems = state.items != null

    // Determine if info bar should be visible
    val showInfoBar = state.info != null ||
        state.selectionState.selectedItems.isNotEmpty() ||
        isLoadingItems ||
        showProgress

    // Pull-to-refresh handler
    val handleRefresh: () -> Unit = {
        coroutineScope.launch {
            showPullToRefreshIndicator = true
            vm?.retryNavigation()
            delay(200)
            showPullToRefreshIndicator = false
        }
    }

    // Synchronize scroll position when view mode changes
    LaunchedEffect(state.viewStyle) {
        val items = state.items
        if (!items.isNullOrEmpty()) {
            val currentIndex = when (state.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.firstVisibleItemIndex
                is ExplorerViewStyle.List -> listState.firstVisibleItemIndex
            }
            when (state.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.scrollToItem(currentIndex, 0)
                is ExplorerViewStyle.List -> listState.scrollToItem(currentIndex, 0)
            }
        }
    }

    // Auto-scroll to top when sort settings change.
    LaunchedEffect(state.sortSettings) {
        when (state.viewStyle) {
            is ExplorerViewStyle.Grid -> gridState.animateScrollToItem(0)
            is ExplorerViewStyle.List -> listState.animateScrollToItem(0)
        }
    }

    // Auto-scroll to keep focused item visible during keyboard navigation
    LaunchedEffect(state.focusedItemIndex) {
        val focusedIndex = state.focusedItemIndex ?: return@LaunchedEffect
        when (state.viewStyle) {
            is ExplorerViewStyle.Grid -> gridState.animateScrollToItem(focusedIndex)
            is ExplorerViewStyle.List -> listState.animateScrollToItem(focusedIndex)
        }
    }

    // Handle reveal requests (scroll to and highlight item)
    LaunchedEffect(vm) {
        val tag = logTag("Explorer", "Page", "Reveal")
        log(tag) { "LaunchedEffect started, collecting revealRequests" }
        vm?.revealRequests?.collect { request ->
            log(tag) { "Received reveal request for path: ${request.path.path}" }
            val result = mainStateSource
                .mapNotNull { emittedState ->
                    emittedState ?: return@mapNotNull null
                    val items = emittedState.items
                    log(tag) { "State emission: ${items?.size ?: 0} items" }
                    val index = items?.indexOfFirst { item ->
                        when (item) {
                            is ExplorerItem.Path -> {
                                val match = item.path.path == request.path.path
                                if (match) log(tag) { "Found match at item: ${item.path.path}" }
                                match
                            }
                            else -> false
                        }
                    }
                    log(tag) { "Index search result: $index" }
                    index?.takeIf { it >= 0 }?.let { it to emittedState.viewStyle }
                }
                .timeout(2.seconds)
                .catch { e -> log(tag) { "Timeout or error waiting for item: $e" } }
                .firstOrNull()

            if (result == null) {
                log(tag) { "Target index is null, skipping scroll" }
                return@collect
            }

            val (targetIndex, currentViewStyle) = result
            log(tag) { "Scrolling to index: $targetIndex (centered), viewStyle: $currentViewStyle" }

            when (currentViewStyle) {
                is ExplorerViewStyle.Grid -> {
                    val layoutInfo = gridState.layoutInfo
                    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                    val avgItemHeight = layoutInfo.visibleItemsInfo
                        .takeIf { it.isNotEmpty() }
                        ?.let { items -> items.sumOf { it.size.height } / items.size }
                        ?: 0
                    val centerOffset = if (avgItemHeight > 0) -(viewportHeight - avgItemHeight) / 2 else 0
                    log(tag) { "Grid: viewportHeight=$viewportHeight, avgItemHeight=$avgItemHeight, centerOffset=$centerOffset" }
                    gridState.animateScrollToItem(targetIndex, centerOffset)
                }
                is ExplorerViewStyle.List -> {
                    val layoutInfo = listState.layoutInfo
                    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                    val avgItemHeight = layoutInfo.visibleItemsInfo
                        .takeIf { it.isNotEmpty() }
                        ?.let { items -> items.sumOf { it.size } / items.size }
                        ?: 0
                    val centerOffset = if (avgItemHeight > 0) -(viewportHeight - avgItemHeight) / 2 else 0
                    log(tag) { "List: viewportHeight=$viewportHeight, avgItemHeight=$avgItemHeight, centerOffset=$centerOffset" }
                    listState.animateScrollToItem(targetIndex, centerOffset)
                }
            }
            log(tag) { "Scroll completed" }
        }
    }

    // Handle back button for picker mode
    if (state.pickerConfig != null) {
        BackHandler(enabled = true) {
            if (state.canGoBack) {
                vm?.goBack()
            } else {
                vm?.cancelPicker()
            }
        }
    }

    // Handle back button for navigation history (when setting enabled).
    // At the top-level (can't go back) the tab closes, matching the setting's description.
    if (state.useBackButtonForNavigation && state.pickerConfig == null) {
        BackHandler(enabled = true) {
            if (state.canGoBack) {
                vm?.goBack()
            } else {
                vm?.closeWorkspace()
            }
        }
    }

    // Handle back button for selection mode - clear selection first
    BackHandler(enabled = state.selectionState.isSelectionMode) {
        vm?.clearSelection()
    }

    // Grid columns for keyboard navigation (approximate for adaptive grid)
    val gridColumns = 3
    val focusedItem = state.focusedItemIndex?.let { state.items?.getOrNull(it) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .explorerKeyboardShortcuts(
                availableActions = state.availableActions,
                clipboardEntries = clipboardState.entries,
                selectedItems = state.selectionState.selectedItems,
                focusedItem = focusedItem,
                viewStyle = state.viewStyle,
                gridColumns = gridColumns,
                trashEnabled = state.trashEnabled,
                enabled = isWorkspaceFocused,
                onExecuteAction = { vm?.executeAction(it) },
                onPaste = { vm?.pasteClipboard(it) },
                onSelectAll = { vm?.selectAll() },
                onClearSelection = { vm?.clearSelection() },
                onClearFocus = { vm?.clearFocus() },
                onNavigateToItem = { vm?.navigate(it) },
                onGoBack = { vm?.goBack() },
                onMoveFocusUp = { vm?.moveFocusUp() },
                onMoveFocusDown = { vm?.moveFocusDown() },
                onMoveFocusLeft = { vm?.moveFocusLeft(gridColumns) },
                onMoveFocusRight = { vm?.moveFocusRight(gridColumns) },
                onMoveFocusToFirst = { vm?.moveFocusToFirst() },
                onMoveFocusToLast = { vm?.moveFocusToLast() },
                onActivateFocusedItem = { focusedItem?.let { vm?.navigate(it) } },
                onRenameFocusedItem = {
                    (focusedItem as? ExplorerItem.Lookup)?.let {
                        vm?.executeAction(ExplorerActionBarItem.Common.Rename(it))
                    }
                },
                onDeleteFocusedItem = { vm?.deleteFocusedItem() },
                onPermanentDeleteFocusedItem = {
                    if (state.selectionState.selectedItems.isNotEmpty()) {
                        vm?.permanentDeleteSelectedItems()
                    } else {
                        vm?.deleteFocusedItem(forcePermDelete = true)
                    }
                },
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val topContentPadding = topBarStackState.contentPaddingDp()

            // Main content area
            if (state.setupRequirements.needsAction) {
                PermissionRequestCard(
                    setupRequirements = state.setupRequirements,
                    onNavigateToSetup = { vm?.navigateToSetup(state.setupRequirements) },
                    nestedScrollConnection = topBarStackState.nestedScrollConnection,
                    onLaunchSAFPicker = { grant -> vm?.launchAndroidDataSAFPicker(grant) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topContentPadding),
                )
            } else {
                // Focus state from ViewModel
                val contentFocusedItem = state.focusedItemIndex?.let { state.items?.getOrNull(it) }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Main content with pull-to-refresh
                    PullToRefreshBox(
                        isRefreshing = showPullToRefreshIndicator,
                        onRefresh = handleRefresh,
                        modifier = Modifier.fillMaxSize(),
                        state = pullToRefreshState,
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = topContentPadding),
                                state = pullToRefreshState,
                                isRefreshing = showPullToRefreshIndicator,
                            )
                        },
                    ) {
                        when (state.viewStyle) {
                            is ExplorerViewStyle.List -> LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(topBarStackState.nestedScrollConnection)
                                    .nestedScroll(bottomBarStackState.nestedScrollConnection),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                contentPadding = PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top = topContentPadding,
                                    bottom = bottomBarStackState.contentPaddingDp(),
                                )
                            ) {
                                if (state.items == null) {
                                    if (state.error == null) {
                                        items(10, key = { "skeleton-$it" }) {
                                            SkeletonListItem()
                                        }
                                    }
                                } else if (state.items.isEmpty()) {
                                    item(key = "empty") {
                                        // When favorites are visible below, don't fill the viewport
                                        // — otherwise the empty-state pushes favorites below the fold.
                                        val emptyModifier = if (state.showHomeFavoritesSection) {
                                            Modifier.fillMaxSize().padding(vertical = 48.dp)
                                        } else {
                                            Modifier.fillParentMaxSize()
                                        }
                                        Box(
                                            modifier = emptyModifier,
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (state.isFilteredEmpty) {
                                                EmptyFilteredState(
                                                    onResetFilters = { vm?.resetFilters() },
                                                )
                                            } else {
                                                EmptyDirectoryState()
                                            }
                                        }
                                    }
                                } else {
                                    items(
                                        items = state.items,
                                        key = { it.id },
                                        contentType = ExplorerItem::contentType,
                                    ) { item ->
                                        ExplorerItemRenderer(
                                            item = item,
                                            viewStyle = state.viewStyle,
                                            state = state,
                                            isFocused = item == contentFocusedItem,
                                            onItemClick = { vm?.onItemClick(it) },
                                            onItemLongClick = { vm?.onItemLongClick(it) },
                                            onNavigate = { vm?.navigate(it) },
                                            onToggleSelection = { vm?.toggleItemSelection(it) },
                                        )
                                    }
                                }
                                if (state.showHomeFavoritesSection) {
                                    item(key = "favorites:divider") {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    }
                                    favoritesSection(
                                        favorites = state.favorites,
                                        onClick = { vm?.onFavoriteClick(it) },
                                        onRemove = { vm?.onFavoriteRemove(it) },
                                    )
                                }
                            }

                            is ExplorerViewStyle.Grid -> LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(minSize = 120.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(topBarStackState.nestedScrollConnection)
                                    .nestedScroll(bottomBarStackState.nestedScrollConnection),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                contentPadding = PaddingValues(
                                    start = 2.dp,
                                    end = 2.dp,
                                    top = topContentPadding,
                                    bottom = bottomBarStackState.contentPaddingDp(),
                                )
                            ) {
                                if (state.items == null) {
                                    if (state.error == null) {
                                        items(12, key = { "skeleton-grid-$it" }) {
                                            SkeletonGridItem()
                                        }
                                    }
                                } else if (state.items.isEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                                        // When favorites are visible below, don't fill the viewport.
                                        val emptyModifier = if (state.showHomeFavoritesSection) {
                                            Modifier.fillMaxSize().padding(vertical = 48.dp)
                                        } else {
                                            Modifier.fillMaxSize()
                                        }
                                        Box(
                                            modifier = emptyModifier,
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (state.isFilteredEmpty) {
                                                EmptyFilteredState(
                                                    onResetFilters = { vm?.resetFilters() },
                                                )
                                            } else {
                                                EmptyDirectoryState()
                                            }
                                        }
                                    }
                                } else {
                                    items(
                                        items = state.items,
                                        key = { it.id },
                                        contentType = ExplorerItem::contentType,
                                    ) { item ->
                                        ExplorerItemRenderer(
                                            item = item,
                                            viewStyle = state.viewStyle,
                                            state = state,
                                            isFocused = item == contentFocusedItem,
                                            onItemClick = { vm?.onItemClick(it) },
                                            onItemLongClick = { vm?.onItemLongClick(it) },
                                            onNavigate = { vm?.navigate(it) },
                                            onToggleSelection = { vm?.toggleItemSelection(it) },
                                        )
                                    }
                                }
                                if (state.showHomeFavoritesSection) {
                                    item(
                                        span = { GridItemSpan(maxLineSpan) },
                                        key = "favorites:divider",
                                    ) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    }
                                    favoritesSection(
                                        favorites = state.favorites,
                                        onClick = { vm?.onFavoriteClick(it) },
                                        onRemove = { vm?.onFavoriteRemove(it) },
                                    )
                                }
                            }
                        }
                    }

                    // Error card (floating below top bar stack)
                    state.error?.let { error ->
                        ErrorCard(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = topContentPadding)
                                .padding(horizontal = 16.dp),
                            title = stringResource(R.string.explorer_navigation_error_title),
                            error = error,
                            onShareError = { vm?.shareNavigationError() },
                            onRetry = { vm?.retryNavigation() },
                            onDismiss = { vm?.dismissNavigationError() },
                        )
                    }

                    // Bottom FloatingBarStack
                    FloatingBarStack(
                        state = bottomBarStackState,
                        position = BarPosition.BOTTOM,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        bars = {
                            FloatingBar(
                                visible = hasOperations,
                                scrollBehavior = if (hasActiveOperations) BarScrollBehavior.Static else BarScrollBehavior.VanishOnScroll,
                                animation = BarAnimation.Slide(),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                OperationsBar(
                                    operations = operationsState.operations,
                                    onCancelOperation = { id -> vm?.cancelOperation(id) },
                                    onDismissOperation = { id -> vm?.dismissOperation(id) },
                                    onOperationClick = { operation ->
                                        when (operation.state) {
                                            is OperationDisplay.State.Waiting -> vm?.showConflictSheet(operation.id)
                                            else -> operationDialogState =
                                                OperationDialogState.OperationDetails(operation.id)
                                        }
                                    },
                                    onClearCompleted = { vm?.clearCompletedOperations() },
                                    initialExpanded = initialOperationsExpanded,
                                )
                            }

                            FloatingBar(
                                visible = hasClipboard,
                                scrollBehavior = BarScrollBehavior.VanishOnScroll,
                                animation = BarAnimation.Bouncy,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                ClipboardBar(
                                    workspaceType = Workspace.Type.EXPLORER,
                                    clipboardEntries = clipboardState.entries,
                                    onPasteClick = { clip -> vm?.pasteClipboard(clip) },
                                    onRemoveClick = { clip -> vm?.removeClipboardEntry(clip) },
                                    onEntryClick = { clip -> vm?.showClipboardInfo(clip) },
                                    onClearAll = { vm?.clearAllClipboard() },
                                    initialExpanded = initialClipboardExpanded,
                                )
                            }

                            FloatingBar(
                                visible = showFavoritesUndoBar,
                                scrollBehavior = BarScrollBehavior.Static,
                                animation = BarAnimation.Slide(),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                lastPendingRemoval?.let { pending ->
                                    FavoritesUndoBar(
                                        pendingRemoval = pending,
                                        onUndo = { vm?.undoFavoriteRemoval() },
                                    )
                                }
                            }

                            FloatingBar(
                                visible = hasActions,
                                scrollBehavior = BarScrollBehavior.HideOnScroll,
                                animation = BarAnimation.Slide(),
                                modifier = Modifier.padding(horizontal = 16.dp),
                                revealOn = state.selectionState.selectedItems,
                            ) {
                                WorkspaceActionBar(
                                    actions = state.availableActions,
                                    onActionClick = { action -> vm?.executeAction(action as ExplorerActionBarItem) },
                                    onActionLongClick = { action -> vm?.executeActionLongClick(action as ExplorerActionBarItem) },
                                )
                            }
                        },
                    )
                }
            }

            // Top FloatingBarStack with toolbar and InfoBar - always visible
            FloatingBarStack(
                state = topBarStackState,
                position = BarPosition.TOP,
                modifier = Modifier.align(Alignment.TopCenter),
                bars = {
                    FloatingBar(
                        visible = true,
                        scrollBehavior = BarScrollBehavior.CollapseOnScroll(collapsedHeight = 44.dp),
                        animation = BarAnimation.Slide(),
                        estimatedHeight = 64.dp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        ExplorerToolbarCard(
                            workspaceId = workspaceId,
                            breadcrumbs = state.breadcrumbs,
                            design = design,
                            collapsedFraction = collapsedFraction,
                            onBreadcrumbClick = { target -> vm?.navigate(target) },
                            onNavigateToPath = { path -> vm?.navigateToPath(path) },
                            onCommitEditedPath = { current, edited -> vm?.navigateToEditedPath(current, edited) },
                            onSetAsHome = { target -> vm?.setAsDefaultStartLocation(target) },
                            onCopyPath = { path -> vm?.copyPathToSystemClipboard(path) },
                            safLocationManager = vm?.safLocationManager,
                            pickerSelection = state.pickerConfig?.selection,
                            selectionCount = state.selectionState.selectedItems.size,
                            saveAsFilename = state.saveAsFilename,
                            canConfirmSelection = state.canConfirmSelection,
                            onSaveAsFilenameChange = { filename -> vm?.updateSaveAsFilename(filename) },
                            onCancel = { vm?.cancelPicker() },
                            onConfirm = { vm?.confirmPickerSelection() },
                        )
                    }

                    // InfoBar - only shown when NOT on permission screen
                    FloatingBar(
                        visible = showInfoBar && !state.setupRequirements.needsAction,
                        scrollBehavior = BarScrollBehavior.Static,
                        animation = BarAnimation.Slide(),
                        estimatedHeight = 32.dp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        ExplorerInfoBar(
                            info = state.info,
                            isLoading = isLoadingItems,
                            progress = if (showProgress) state.progress else null,
                            onCancel = { vm?.navigate(ExplorerNavigation.Cancel) },
                            selectedCount = state.selectionState.selectedItems.size,
                            selectedSize = state.selectionState.selectedSize,
                            onClearSelection = { vm?.clearSelection() },
                            onSelectFolders = { vm?.selectAllFolders() },
                            onSelectFiles = { vm?.selectAllFiles() },
                            isTrashDisabled = !state.trashEnabled,
                        )
                    }
                },
            )

            // Dialogs - stay in parent
            ExplorerDialogHost(
                dialogState = state.dialogState,
                trashEnabled = state.trashEnabled,
                vm = vm,
                bottomInset = navBarInset,
            )

            OperationDialogHost(
                dialogState = operationDialogState,
                operations = operationsState.operations,
                onDismissDialog = { operationDialogState = OperationDialogState.None },
                onCancelOperation = { operationId ->
                    operationDialogState = OperationDialogState.None
                    showCancelConfirmation = operationId
                },
                onShareError = { vm?.shareError(it) },
                onHandleIssue = { operationId -> vm?.showConflictSheet(operationId) },
                bottomInset = navBarInset,
            )

            // Show conflict bottom sheet when needed
            if (issueState != null && showIssueSheet) {
                IssuesBottomSheet(
                    issue = issueState!!,
                    onResolution = { resolution -> vm?.resolveConflict(resolution) },
                    onDismiss = { showIssueSheet = false },
                    bottomInset = navBarInset,
                )
            }
        }
    }

    // Show add storage bottom sheet
    val showAddStorageSheet by (vm?.showAddStorageSheet?.collectAsState() ?: remember { mutableStateOf(false) })
    if (showAddStorageSheet) {
        AddDeviceStorageSheet(
            onDismiss = { vm?.dismissAddStorageSheet() },
            onContinue = { vm?.addSAFLocation() },
            bottomInset = navBarInset,
        )
    }

    // Show cancel confirmation dialog when needed
    showCancelConfirmation?.let { operationId ->
        CancelOperationConfirmationDialog(
            onDismiss = { showCancelConfirmation = null },
            onConfirm = {
                vm?.cancelOperation(operationId)
                showCancelConfirmation = null
            }
        )
    }
}

@Composable
fun ExplorerWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: ExplorerWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: ExplorerWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val coroutineScope = rememberCoroutineScope()

    val safPickerLauncher = rememberLauncherForActivityResult(
        OpenDocumentTreeWithIntent()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                val grant = vm.pendingSAFPickerGrant.first()
                if (grant != null) {
                    vm.handleAndroidDataSAFPickerResult(it, grant)
                } else {
                    vm.handleSAFPickerResult(it)
                }
            }
        }
    }

    LaunchedEffect(vm) {
        vm.safPickerEvents.collect { intent ->
            safPickerLauncher.launch(intent)
        }
    }

    // Handle share intent events
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.shareIntentEvent.collect { intent ->
            context.startActivity(intent)
        }
    }

    // Handle one-shot toast confirmations (e.g. breadcrumb "Copy path")
    LaunchedEffect(vm) {
        vm.toastEvents.collect { message ->
            Toast.makeText(context, message.get(context), Toast.LENGTH_SHORT).show()
        }
    }

    ExplorerWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = vm.state,
        clipboardStateSource = vm.clipboard,
        operationsStateSource = vm.operations,
        vm = vm,
    )
}

@Composable
private fun ExplorerWorkspacePagePreviewBase(
    mockState: ExplorerWorkspaceViewModel.State,
    clipboardState: ClipboardDisplayState = ClipboardDisplayState(),
    operationsState: OperationsDisplayState = OperationsDisplayState(),
) = PreviewWrapper {
    ExplorerWorkspacePage(
        workspaceId = Workspace.Id(),
        mainStateSource = flowOf(mockState),
        clipboardStateSource = flowOf(clipboardState),
        operationsStateSource = flowOf(operationsState),
        vm = null,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspacePagePreview() {
    ExplorerWorkspacePagePreviewBase(
        mockState = MockDataProvider.createReadyState(
            actions = MockDataProvider.createDefaultDirectoryActions(createEnabled = false, filterEnabled = false),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspacePageEmptyPreview() {
    ExplorerWorkspacePagePreviewBase(mockState = MockDataProvider.createEmptyState())
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspacePageErrorPreview() {
    ExplorerWorkspacePagePreviewBase(
        mockState = MockDataProvider.createErrorState(
            error = ReadException(path = LocalPath.build("/permission/denied")),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspacePageWithAllBarsPreview() {
    ExplorerWorkspacePagePreviewBase(
        mockState = MockDataProvider.createStateWithSelection(),
        clipboardState = MockDataProvider.createMockClipboardState(copyCount = 2, cutCount = 1),
        operationsState = MockDataProvider.createMockOperationsState(runningCount = 2, completedCount = 1),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspacePagePickerPreview() {
    val mockItems = MockDataProvider.createAllFileTypes() + listOf(
        MockDataProvider.createMockDirectory("Photos", childCount = 234),
        MockDataProvider.createMockDirectory("Videos", childCount = 56),
        MockDataProvider.createMockDirectory("Music", childCount = 189),
    )
    ExplorerWorkspacePagePreviewBase(
        mockState = MockDataProvider.createPickerState(
            items = mockItems,
            selectedItems = setOf(mockItems[0], mockItems[2], mockItems[4], mockItems[5], mockItems[6]),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspacePageGridPreview() {
    ExplorerWorkspacePagePreviewBase(
        mockState = MockDataProvider.createReadyState().copy(
            viewStyle = ExplorerViewStyle.Grid(),
            availableActions = listOf(
                ExplorerActionBarItem.Directory.Create(isEnabled = false),
                ExplorerActionBarItem.Common.Sort(),
            ),
        ),
    )
}

private fun ExplorerItem.contentType(): String = when (this) {
    is ExplorerItem.Lookup -> "lookup"
    is ExplorerItem.Peek -> "peek"
    is ExplorerItem.Shortcut -> "shortcut"
    is ExplorerItem.Storage -> "storage"
    is ExplorerItem.Trash.Root -> "trashRoot"
    is ExplorerItem.Trash.Nested -> "trashNested"
}
