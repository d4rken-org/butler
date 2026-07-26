package eu.darken.butler.explorer.ui.explorer

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.rememberDelayedState
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerReadyContent
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerTopBars
import eu.darken.butler.explorer.ui.explorer.elements.PermissionRequestCard
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.preview.ProvideFolderPreviews
import eu.darken.butler.explorer.ui.explorer.util.OpenDocumentTreeWithIntent
import eu.darken.butler.explorer.ui.explorer.util.explorerKeyboardShortcuts
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
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

    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
        estimatedContentPadding = 196.dp,
    )
    val bottomBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.BOTTOM,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        design = design,
        estimatedContentPadding = 80.dp,
    )
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

    // Pull-to-refresh handler
    val handleRefresh: () -> Unit = {
        coroutineScope.launch {
            showPullToRefreshIndicator = true
            vm?.retryNavigation()
            delay(200)
            showPullToRefreshIndicator = false
        }
    }

    SyncScrollPositionOnViewStyleChange(
        viewStyle = state.viewStyle,
        items = state.items,
        listState = listState,
        gridState = gridState,
    )
    ScrollToTopOnSortChange(
        sortSettings = state.sortSettings,
        viewStyle = state.viewStyle,
        listState = listState,
        gridState = gridState,
    )
    ScrollToFocusedItem(
        focusedItemIndex = state.focusedItemIndex,
        viewStyle = state.viewStyle,
        listState = listState,
        gridState = gridState,
    )
    ExplorerRevealEffect(
        vm = vm,
        mainStateSource = mainStateSource,
        listState = listState,
        gridState = gridState,
    )
    ExplorerBackHandlers(
        hasPickerConfig = state.pickerConfig != null,
        useBackButtonForNavigation = state.useBackButtonForNavigation,
        canGoBack = state.canGoBack,
        isSelectionMode = state.selectionState.isSelectionMode,
        onGoBack = { vm?.goBack() },
        onCancelPicker = { vm?.cancelPicker() },
        onCloseWorkspace = { vm?.closeWorkspace() },
        onClearSelection = { vm?.clearSelection() },
    )

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
                ExplorerReadyContent(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    vm = vm,
                    listState = listState,
                    gridState = gridState,
                    topBarStackState = topBarStackState,
                    bottomBarStackState = bottomBarStackState,
                    operationsState = operationsState,
                    clipboardState = clipboardState,
                    showPullToRefreshIndicator = showPullToRefreshIndicator,
                    pullToRefreshState = pullToRefreshState,
                    onRefresh = handleRefresh,
                    initialOperationsExpanded = initialOperationsExpanded,
                    initialClipboardExpanded = initialClipboardExpanded,
                    onShowOperationDetails = { operationId -> vm?.showOperationDetails(operationId) },
                )
            }

            // Top FloatingBarStack with toolbar and InfoBar - always visible
            FloatingBarStack(
                state = topBarStackState,
                position = BarPosition.TOP,
                modifier = Modifier.align(Alignment.TopCenter),
                bars = {
                    ExplorerTopBars(
                        workspaceId = workspaceId,
                        design = design,
                        state = state,
                        vm = vm,
                        showProgress = showProgress,
                    )
                },
            )

            // Dialogs and sheets live in the page host's overlay slot, see ExplorerWorkspaceOverlays
        }
    }
}

// Synchronize scroll position when view mode changes
@Composable
private fun SyncScrollPositionOnViewStyleChange(
    viewStyle: ExplorerViewStyle,
    items: List<ExplorerItem>?,
    listState: LazyListState,
    gridState: LazyGridState,
) {
    LaunchedEffect(viewStyle) {
        if (!items.isNullOrEmpty()) {
            val currentIndex = when (viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.firstVisibleItemIndex
                is ExplorerViewStyle.List -> listState.firstVisibleItemIndex
            }
            when (viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.scrollToItem(currentIndex, 0)
                is ExplorerViewStyle.List -> listState.scrollToItem(currentIndex, 0)
            }
        }
    }
}

// Auto-scroll to top when sort settings change.
@Composable
private fun ScrollToTopOnSortChange(
    sortSettings: SortSettings,
    viewStyle: ExplorerViewStyle,
    listState: LazyListState,
    gridState: LazyGridState,
) {
    LaunchedEffect(sortSettings) {
        when (viewStyle) {
            is ExplorerViewStyle.Grid -> gridState.animateScrollToItem(0)
            is ExplorerViewStyle.List -> listState.animateScrollToItem(0)
        }
    }
}

// Auto-scroll to keep focused item visible during keyboard navigation
@Composable
private fun ScrollToFocusedItem(
    focusedItemIndex: Int?,
    viewStyle: ExplorerViewStyle,
    listState: LazyListState,
    gridState: LazyGridState,
) {
    LaunchedEffect(focusedItemIndex) {
        val focusedIndex = focusedItemIndex ?: return@LaunchedEffect
        when (viewStyle) {
            is ExplorerViewStyle.Grid -> gridState.animateScrollToItem(focusedIndex)
            is ExplorerViewStyle.List -> listState.animateScrollToItem(focusedIndex)
        }
    }
}

// Handle reveal requests (scroll to and highlight item)
@Composable
private fun ExplorerRevealEffect(
    vm: ExplorerWorkspaceViewModel?,
    mainStateSource: Flow<ExplorerWorkspaceViewModel.State?>,
    listState: LazyListState,
    gridState: LazyGridState,
) {
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
}

@Composable
private fun ExplorerBackHandlers(
    hasPickerConfig: Boolean,
    useBackButtonForNavigation: Boolean,
    canGoBack: Boolean,
    isSelectionMode: Boolean,
    onGoBack: () -> Unit,
    onCancelPicker: () -> Unit,
    onCloseWorkspace: () -> Unit,
    onClearSelection: () -> Unit,
) {
    // Handle back button for picker mode
    if (hasPickerConfig) {
        WorkspaceBackHandler(enabled = true) {
            if (canGoBack) {
                onGoBack()
            } else {
                onCancelPicker()
            }
        }
    }

    // Handle back button for navigation history (when setting enabled).
    // At the top-level (can't go back) the tab closes, matching the setting's description.
    if (useBackButtonForNavigation && !hasPickerConfig) {
        WorkspaceBackHandler(enabled = true) {
            if (canGoBack) {
                onGoBack()
            } else {
                onCloseWorkspace()
            }
        }
    }

    // Handle back button for selection mode - clear selection first
    WorkspaceBackHandler(enabled = isSelectionMode) {
        onClearSelection()
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

    ProvideFolderPreviews(vm.folderPreviewObserver) {
        ExplorerWorkspacePage(
            workspaceId = id,
            design = design,
            mainStateSource = vm.state,
            clipboardStateSource = vm.clipboard,
            operationsStateSource = vm.operations,
            vm = vm,
        )
    }
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
