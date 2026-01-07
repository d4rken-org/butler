package eu.darken.butler.explorer.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.elements.EmptyDirectoryState
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerInfoBar
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerToolbarCard
import eu.darken.butler.explorer.ui.explorer.elements.SkeletonGridItem
import eu.darken.butler.explorer.ui.explorer.elements.SkeletonListItem
import eu.darken.butler.explorer.ui.explorer.items.ExplorerItemRenderer
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBar
import eu.darken.butler.workspace.ui.error.ErrorCard
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.OperationsBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Content composable for the Explorer workspace when in Ready state.
 * Handles all Ready-specific logic including scroll management, selection, and content rendering.
 */
@Composable
internal fun ExplorerReadyContent(
    modifier: Modifier = Modifier,
    state: ExplorerWorkspaceViewModel.State.Ready,
    operationsState: ExplorerWorkspaceViewModel.OperationsState,
    clipboardState: ExplorerWorkspaceViewModel.ClipboardState,
    mainStateSource: Flow<ExplorerWorkspaceViewModel.State>,
    workspaceId: Workspace.Id,
    topBarStackState: FloatingBarStackState,
    bottomBarStackState: FloatingBarStackState,
    design: WorkspaceDesign,
    navBarInset: Dp,
    vm: ExplorerWorkspaceViewModel?,
    showProgress: Boolean,
    isWorkspaceFocused: Boolean,
    onShowOperationDetails: (Operation.Id) -> Unit,
    safLocationManager: SAFLocationManager?,
    initialOperationsExpanded: Boolean = false,
    initialClipboardExpanded: Boolean = false,
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // List and grid scroll states
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // Focus state from ViewModel
    val focusedItem = state.focusedItemIndex?.let { state.items?.getOrNull(it) }

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
    val isLoadingItems = state.items == null
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

    // Grid columns for keyboard navigation

    // Track previous location to detect actual navigation vs item updates
    var previousLocationId by remember { mutableStateOf<String?>(null) }

    // Save scroll position when navigating away from current location
    DisposableEffect(state.locationId) {
        val locationId = state.locationId
        val viewStyle = state.viewStyle
        onDispose {
            if (locationId != null) {
                val (index, offset) = when (viewStyle) {
                    is ExplorerViewStyle.Grid -> gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                    is ExplorerViewStyle.List -> listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                }
                vm?.saveScrollPosition(locationId, index, offset)
            }
        }
    }

    // Restore or reset scroll position when navigating to a different location
    LaunchedEffect(state.locationId, hasItems) {
        val scrollTag = logTag("Explorer", "Page", "ScrollRestore")
        val locationId = state.locationId ?: return@LaunchedEffect

        // Wait for items to be loaded before restoring scroll
        if (!hasItems) return@LaunchedEffect

        // Skip if this is the same location (items just updated, not navigation)
        if (locationId == previousLocationId) return@LaunchedEffect
        previousLocationId = locationId

        log(scrollTag) { "Restoring scroll for $locationId" }
        val savedPosition = vm?.getScrollPosition(locationId)

        if (savedPosition != null) {
            // Restore saved position (coming back to previously visited location)
            when (state.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.scrollToItem(savedPosition.first, savedPosition.second)
                is ExplorerViewStyle.List -> listState.scrollToItem(savedPosition.first, savedPosition.second)
            }
        } else {
            // New location - scroll to top
            when (state.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.scrollToItem(0)
                is ExplorerViewStyle.List -> listState.scrollToItem(0)
            }
        }
    }

    // Synchronize scroll position when view mode changes
    LaunchedEffect(state.viewStyle) {
        val items = state.items
        if (items != null && items.isNotEmpty()) {
            val currentIndex = when (state.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.firstVisibleItemIndex
                is ExplorerViewStyle.List -> listState.firstVisibleItemIndex
            }

            // Apply the scroll position to the new view mode
            when (state.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.scrollToItem(currentIndex, 0)
                is ExplorerViewStyle.List -> listState.scrollToItem(currentIndex, 0)
            }
        }
    }

    // Auto-scroll to top when sort settings change
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
            // Single observation to get both index and viewStyle
            val result = mainStateSource
                .mapNotNull { state ->
                    val readyState = state as? ExplorerWorkspaceViewModel.State.Ready ?: return@mapNotNull null
                    val items = readyState.items
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
                    index?.takeIf { it >= 0 }?.let { it to readyState.viewStyle }
                }
                .timeout(2.seconds)
                .catch { e ->
                    log(tag) { "Timeout or error waiting for item: $e" }
                }
                .firstOrNull()

            if (result == null) {
                log(tag) { "Target index is null, skipping scroll" }
                return@collect
            }

            val (targetIndex, currentViewStyle) = result
            log(tag) { "Scrolling to index: $targetIndex (centered), viewStyle: $currentViewStyle" }

            // Calculate offset to center the item in viewport
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

    // Handle back button for navigation history (when setting enabled)
    if (state.useBackButtonForNavigation && state.pickerConfig == null) {
        BackHandler(enabled = state.canGoBack) {
            vm?.goBack()
        }
    }

    // Handle back button for selection mode - clear selection first
    BackHandler(enabled = state.selectionState.isSelectionMode) {
        vm?.clearSelection()
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Content padding from top bar stack
        val topContentPadding = topBarStackState.contentPaddingDp()

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
                is ExplorerViewStyle.List -> {
                    LazyColumn(
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
                            items(10, key = { "skeleton-$it" }) {
                                SkeletonListItem()
                            }
                        } else if (state.items.isEmpty()) {
                            item(key = "empty") {
                                Box(
                                    modifier = Modifier.fillParentMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    EmptyDirectoryState()
                                }
                            }
                        } else {
                            items(
                                items = state.items,
                                key = { it.id }
                            ) { item ->
                                ExplorerItemRenderer(
                                    item = item,
                                    viewStyle = state.viewStyle,
                                    state = state,
                                    isFocused = item == focusedItem,
                                    onItemClick = { vm?.onItemClick(it) },
                                    onItemLongClick = { vm?.onItemLongClick(it) },
                                    onNavigate = { vm?.navigate(it) },
                                    onToggleSelection = { vm?.toggleItemSelection(it) },
                                )
                            }
                        }
                    }
                }

                is ExplorerViewStyle.Grid -> {
                    LazyVerticalGrid(
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
                            items(12, key = { "skeleton-grid-$it" }) {
                                SkeletonGridItem()
                            }
                        } else if (state.items.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    EmptyDirectoryState()
                                }
                            }
                        } else {
                            items(
                                items = state.items,
                                key = { it.id }
                            ) { item ->
                                ExplorerItemRenderer(
                                    item = item,
                                    viewStyle = state.viewStyle,
                                    state = state,
                                    isFocused = item == focusedItem,
                                    onItemClick = { vm?.onItemClick(it) },
                                    onItemLongClick = { vm?.onItemLongClick(it) },
                                    onNavigate = { vm?.navigate(it) },
                                    onToggleSelection = { vm?.toggleItemSelection(it) },
                                )
                            }
                        }
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
                onCopyError = { vm?.copyNavigationError() },
                onRetry = { vm?.retryNavigation() },
                onDismiss = { vm?.dismissNavigationError() },
            )
        }

        // Top FloatingBarStack with toolbar and InfoBar
        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                // Toolbar bar
                FloatingBar(
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(collapsedHeight = 44.dp),
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    ExplorerToolbarCard(
                        workspaceId = workspaceId,
                        breadcrumbs = state.breadcrumbs,
                        design = design,
                        collapsedFraction = collapsedFraction,
                        onBreadcrumbClick = { target -> vm?.navigate(target) },
                        onNavigateToPath = { path -> vm?.navigateToPath(path) },
                        onSetAsHome = { target -> vm?.setAsDefaultStartLocation(target) },
                        onCopyPath = { path -> vm?.copyPathToSystemClipboard(path) },
                        safLocationManager = safLocationManager,
                        pickerSelection = state.pickerConfig?.selection,
                        selectionCount = state.selectionState.selectedItems.size,
                        saveAsFilename = state.saveAsFilename,
                        canConfirmSelection = state.canConfirmSelection,
                        onSaveAsFilenameChange = { filename -> vm?.updateSaveAsFilename(filename) },
                        onCancel = { vm?.cancelPicker() },
                        onConfirm = { vm?.confirmPickerSelection() },
                    )
                }

                // InfoBar
                FloatingBar(
                    visible = showInfoBar,
                    scrollBehavior = BarScrollBehavior.Static,
                    animation = BarAnimation.Slide(),
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

        // Bottom FloatingBarStack
        FloatingBarStack(
            state = bottomBarStackState,
            position = BarPosition.BOTTOM,
            modifier = Modifier.align(Alignment.BottomCenter),
            bars = {
                // Operations bar - stays visible when active
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
                                is OperationDisplay.State.Waiting -> {
                                    vm?.showConflictSheet(operation.id)
                                }
                                else -> {
                                    onShowOperationDetails(operation.id)
                                }
                            }
                        },
                        onClearCompleted = { vm?.clearCompletedOperations() },
                        initialExpanded = initialOperationsExpanded,
                    )
                }

                // Clipboard bar - vanishes on scroll with pop effect
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

                // Action bar - hides on scroll
                FloatingBar(
                    visible = hasActions,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
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

@Preview2
@Composable
private fun ExplorerReadyContentPreview() {
    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/storage/emulated/0"),
            items = MockDataProvider.createAllFileTypes(),
            info = ExplorerLocation.Directory.Info(
                fileCount = 15,
                directoryCount = 5,
                totalSize = 1024L * 1024L * 250L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 50L,
                volumeTotalSpace = 1024L * 1024L * 1024L * 128L,
                isWritable = true,
            ),
            progress = null,
        ),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_home.toCaString(),
                target = ExplorerNavigation.Target.Home
            ),
        ),
        items = MockDataProvider.createAllFileTypes(),
        availableActions = listOf(
            ExplorerActionBarItem.Directory.Create(isEnabled = false),
            ExplorerActionBarItem.Common.Sort(),
            ExplorerActionBarItem.Common.Filter(isEnabled = false),
        ),
    )
    PreviewWrapper {
        // Note: Preview cannot fully render FloatingBarStack behavior
        Box(modifier = Modifier.fillMaxSize()) {
            ExplorerReadyContent(
                state = mockState,
                operationsState = ExplorerWorkspaceViewModel.OperationsState(),
                clipboardState = ExplorerWorkspaceViewModel.ClipboardState(),
                mainStateSource = flowOf(mockState),
                workspaceId = Workspace.Id(),
                topBarStackState = FloatingBarStackState(BarPosition.TOP),
                bottomBarStackState = FloatingBarStackState(BarPosition.BOTTOM),
                design = WorkspaceDesign(),
                navBarInset = 0.dp,
                vm = null,
                showProgress = false,
                isWorkspaceFocused = true,
                onShowOperationDetails = {},
                safLocationManager = null,
            )
        }
    }
}

@Preview2
@Composable
private fun ExplorerReadyContentEmptyPreview() {
    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/sdcard/EmptyFolder"),
            items = emptyList(),
            progress = null,
        ),
        breadcrumbs = emptyList(),
        items = emptyList(),
    )
    PreviewWrapper {
        Box(modifier = Modifier.fillMaxSize()) {
            ExplorerReadyContent(
                state = mockState,
                operationsState = ExplorerWorkspaceViewModel.OperationsState(),
                clipboardState = ExplorerWorkspaceViewModel.ClipboardState(),
                mainStateSource = flowOf(mockState),
                workspaceId = Workspace.Id(),
                topBarStackState = FloatingBarStackState(BarPosition.TOP),
                bottomBarStackState = FloatingBarStackState(BarPosition.BOTTOM),
                design = WorkspaceDesign(),
                navBarInset = 0.dp,
                vm = null,
                showProgress = false,
                isWorkspaceFocused = true,
                onShowOperationDetails = {},
                safLocationManager = null,
            )
        }
    }
}

@Preview2
@Composable
private fun ExplorerReadyContentLoadingPreview() {
    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        currentLocation = null,
        breadcrumbs = emptyList(),
        items = null,
    )
    PreviewWrapper {
        Box(modifier = Modifier.fillMaxSize()) {
            ExplorerReadyContent(
                state = mockState,
                operationsState = ExplorerWorkspaceViewModel.OperationsState(),
                clipboardState = ExplorerWorkspaceViewModel.ClipboardState(),
                mainStateSource = flowOf(mockState),
                workspaceId = Workspace.Id(),
                topBarStackState = FloatingBarStackState(BarPosition.TOP),
                bottomBarStackState = FloatingBarStackState(BarPosition.BOTTOM),
                design = WorkspaceDesign(),
                navBarInset = 0.dp,
                vm = null,
                showProgress = true,
                isWorkspaceFocused = true,
                onShowOperationDetails = {},
                safLocationManager = null,
            )
        }
    }
}

@Preview2
@Composable
private fun ExplorerReadyContentSelectionModePreview() {
    val mockItems = MockDataProvider.createAllFileTypes()
    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/storage/emulated/0"),
            items = mockItems,
            info = ExplorerLocation.Directory.Info(
                fileCount = 15,
                directoryCount = 5,
                totalSize = 1024L * 1024L * 250L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 50L,
                volumeTotalSpace = 1024L * 1024L * 1024L * 128L,
                isWritable = true,
            ),
            progress = null,
        ),
        breadcrumbs = emptyList(),
        items = mockItems,
        selectionState = ExplorerSelectionState(
            selectedItems = setOf(mockItems[0], mockItems[2]),
            selectableItems = mockItems.toSet(),
        ),
        availableActions = listOf(
            ExplorerActionBarItem.Directory.Copy(),
            ExplorerActionBarItem.Directory.Cut(),
            ExplorerActionBarItem.Directory.Delete(),
        ),
    )
    PreviewWrapper {
        Box(modifier = Modifier.fillMaxSize()) {
            ExplorerReadyContent(
                state = mockState,
                operationsState = ExplorerWorkspaceViewModel.OperationsState(),
                clipboardState = ExplorerWorkspaceViewModel.ClipboardState(),
                mainStateSource = flowOf(mockState),
                workspaceId = Workspace.Id(),
                topBarStackState = FloatingBarStackState(BarPosition.TOP),
                bottomBarStackState = FloatingBarStackState(BarPosition.BOTTOM),
                design = WorkspaceDesign(),
                navBarInset = 0.dp,
                vm = null,
                showProgress = false,
                isWorkspaceFocused = true,
                onShowOperationDetails = {},
                safLocationManager = null,
            )
        }
    }
}

@Preview2
@Composable
private fun ExplorerReadyContentGridViewPreview() {
    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/storage/emulated/0"),
            items = MockDataProvider.createAllFileTypes(),
            progress = null,
        ),
        breadcrumbs = emptyList(),
        items = MockDataProvider.createAllFileTypes(),
        viewStyle = ExplorerViewStyle.Grid(),
        availableActions = listOf(
            ExplorerActionBarItem.Directory.Create(isEnabled = false),
            ExplorerActionBarItem.Common.Sort(),
        ),
    )
    PreviewWrapper {
        Box(modifier = Modifier.fillMaxSize()) {
            ExplorerReadyContent(
                state = mockState,
                operationsState = ExplorerWorkspaceViewModel.OperationsState(),
                clipboardState = ExplorerWorkspaceViewModel.ClipboardState(),
                mainStateSource = flowOf(mockState),
                workspaceId = Workspace.Id(),
                topBarStackState = FloatingBarStackState(BarPosition.TOP),
                bottomBarStackState = FloatingBarStackState(BarPosition.BOTTOM),
                design = WorkspaceDesign(),
                navBarInset = 0.dp,
                vm = null,
                showProgress = false,
                isWorkspaceFocused = true,
                onShowOperationDetails = {},
                safLocationManager = null,
            )
        }
    }
}
