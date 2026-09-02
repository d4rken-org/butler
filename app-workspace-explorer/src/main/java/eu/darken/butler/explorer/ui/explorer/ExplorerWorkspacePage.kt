package eu.darken.butler.explorer.ui.explorer

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.OnValueChange
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.rememberDelayedState
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel.RevealRequest
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.dnd.ExplorerDragPayloadFactory
import eu.darken.butler.explorer.ui.explorer.dnd.explorerDropTarget
import eu.darken.butler.explorer.ui.explorer.dnd.rememberExplorerDropState
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerReadyContent
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerTopBars
import eu.darken.butler.explorer.ui.explorer.elements.PermissionRequestCard
import eu.darken.butler.explorer.ui.explorer.elements.favoriteContentIndex
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.preview.ProvideFolderPreviews
import eu.darken.butler.explorer.ui.explorer.util.OpenDocumentTreeWithIntent
import eu.darken.butler.explorer.ui.explorer.util.explorerKeyboardShortcuts
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.dnd.LocalDropZoneRegistry
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyGridState
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyListState
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

    // Same StateFlow unwrap as the main state above: a single-frame renderer never runs the
    // collection, so `initial = null` would leave the operations and clipboard bars permanently
    // hidden in screenshot tests and IDE previews.
    val operationsStateRaw by operationsStateSource.collectAsState(
        initial = (operationsStateSource as? StateFlow)?.value,
    )
    val operationsState = operationsStateRaw ?: OperationsDisplayState()
    val clipboardStateRaw by clipboardStateSource.collectAsState(
        initial = (clipboardStateSource as? StateFlow)?.value,
    )
    val clipboardState = clipboardStateRaw ?: ClipboardDisplayState()
    val isWorkspaceFocused = LocalWorkspaceFocused.current

    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
        estimatedContentPadding = 196.dp,
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
    // Progress indicator delay state - shows after 200ms to avoid flickering
    val showProgress = rememberDelayedState(state.progress, delayMs = 200)

    val listState = rememberWorkspaceLazyListState(workspaceId, slot = ExplorerScrollSlots.list(state.locationId))
    val gridState = rememberWorkspaceLazyGridState(workspaceId, slot = ExplorerScrollSlots.grid(state.locationId))

    // Navigation resets floating-bar scroll-collapse so bars don't stay hidden over new content.
    // Guarded: on initial composition there is no new content, and firing there would reset the
    // collapse state this workspace just restored.
    OnValueChange(state.locationId) { _, _ ->
        topBarStackState.resetScrollCollapse()
        bottomBarStackState.resetScrollCollapse()
    }

    val pullToRefreshState = rememberPullToRefreshState()

    SyncScrollPositionOnViewStyleChange(
        viewStyle = state.viewStyle,
        items = state.items,
        listState = listState,
        gridState = gridState,
    )
    ScrollToTopOnSortChange(
        resolvedSort = state.resolvedSort,
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
        onClearSelection = { vm?.clearSelection() },
    )

    // The payload is built from the state this composition already holds, so a drag can't lose
    // items to an in-flight update.
    val dragPayloadFactory: (ExplorerItem) -> WorkspaceDragPayload? = { pressed ->
        ExplorerDragPayloadFactory.build(state, workspaceId, pressed)
    }

    val dropState = rememberExplorerDropState()

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
                        vm?.deleteFocusedItem(initialPermanentDelete = true)
                    }
                },
            )
    ) {
        CompositionLocalProvider(LocalDropZoneRegistry provides dropState.registry) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .explorerDropTarget(
                        dropState = dropState,
                        workspaceId = workspaceId,
                        state = state,
                        listState = listState,
                        gridState = gridState,
                        onDrop = { payload, destination -> vm?.onDragDropped(payload, destination) },
                    ),
            ) {
                val topContentPadding = rememberFloatingBarContentPadding(topStackState = topBarStackState)

                // Main content area
                if (state.setupRequirements.needsAction) {
                    PermissionRequestCard(
                        setupRequirements = state.setupRequirements,
                        onNavigateToSetup = { vm?.navigateToSetup(state.setupRequirements) },
                        nestedScrollConnection = topBarStackState.nestedScrollConnection,
                        onLaunchSAFPicker = { grant -> vm?.launchAndroidDataSAFPicker(grant) },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(topContentPadding),
                    )
                } else {
                    ExplorerReadyContent(
                        modifier = Modifier.fillMaxSize(),
                        workspaceId = workspaceId,
                        state = state,
                        vm = vm,
                        listState = listState,
                        gridState = gridState,
                        topBarStackState = topBarStackState,
                        bottomBarStackState = bottomBarStackState,
                        operationsState = operationsState,
                        clipboardState = clipboardState,
                        isRefreshing = rememberRefreshIndication(state.refreshId, state.isRefreshing),
                        pullToRefreshState = pullToRefreshState,
                        onRefresh = { vm?.onPullToRefresh() },
                        initialOperationsExpanded = initialOperationsExpanded,
                        initialClipboardExpanded = initialClipboardExpanded,
                        onShowOperationDetails = { operationId -> vm?.showOperationDetails(operationId) },
                        dragPayloadFactory = dragPayloadFactory,
                        dropState = dropState,
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
}

/**
 * Whether the refresh indicator should be showing.
 *
 * [isRefreshing] alone would be enough if it always reached composition, but every step from the
 * browsing engine to here conflates, and a refresh that finds nothing changed can start and finish
 * in between two of them - the pull would then produce no feedback whatsoever. [refreshId] survives
 * that, so a refresh nobody saw running is still turned into one frame of indication, which
 * WorkspacePullToRefreshBox's own minimum-visible hold stretches into a readable spinner.
 */
@Composable
private fun rememberRefreshIndication(refreshId: Int, isRefreshing: Boolean): Boolean {
    var missedRefresh by remember { mutableStateOf(false) }
    OnValueChange(refreshId) { _, _ -> missedRefresh = true }
    LaunchedEffect(missedRefresh) {
        if (!missedRefresh) return@LaunchedEffect
        withFrameNanos { }
        missedRefresh = false
    }
    return isRefreshing || missedRefresh
}

// Carry the scroll position over when the user switches between list and grid, so the file they
// were looking at stays in view. List and grid keep their own remembered positions; an explicit
// switch overrides the incoming one.
@Composable
private fun SyncScrollPositionOnViewStyleChange(
    viewStyle: ExplorerViewStyle,
    items: List<ExplorerItem>?,
    listState: LazyListState,
    gridState: LazyGridState,
) {
    val hasItems = !items.isNullOrEmpty()
    OnValueChange(viewStyle) { previous, current ->
        if (!hasItems) return@OnValueChange
        val outgoingIndex = when (previous) {
            is ExplorerViewStyle.Grid -> gridState.firstVisibleItemIndex
            is ExplorerViewStyle.List -> listState.firstVisibleItemIndex
        }
        when (current) {
            is ExplorerViewStyle.Grid -> gridState.scrollToItem(outgoingIndex)
            is ExplorerViewStyle.List -> listState.scrollToItem(outgoingIndex)
        }
    }
}

/**
 * Auto-scroll to top when the sort changes *at a stable location*.
 *
 * Only two resolved sorts for the same location may trigger this: with per-folder rules, entering a
 * folder that sorts differently changes the sort as a side effect of navigating, and scrolling to
 * top there would animate away the per-directory scroll position that was just restored. An
 * unresolved (null) sort is likewise not a sort change - it is the gap while the rules are loaded.
 */
@Composable
private fun ScrollToTopOnSortChange(
    resolvedSort: ExplorerViewSettingsController.ResolvedSort?,
    viewStyle: ExplorerViewStyle,
    listState: LazyListState,
    gridState: LazyGridState,
) {
    OnValueChange(resolvedSort) { old, new ->
        if (old == null || new == null) return@OnValueChange
        if (old.locationKey != new.locationKey) return@OnValueChange
        if (old.resolution.settings == new.resolution.settings) return@OnValueChange
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
            log(tag) { "Received reveal request for path: ${request.path.path} (${request.scope})" }
            val result = mainStateSource
                .mapNotNull { emittedState ->
                    emittedState ?: return@mapNotNull null
                    val index = when (request.scope) {
                        // Favorites live in a trailing section, not in `items`.
                        RevealRequest.Scope.Favorites -> emittedState.favoriteContentIndex(request.path)
                        RevealRequest.Scope.Items -> {
                            val items = emittedState.items
                            log(tag) { "State emission: ${items?.size ?: 0} items" }
                            items?.indexOfFirst { item ->
                                when (item) {
                                    is ExplorerItem.Path -> {
                                        val match = item.path.path == request.path.path
                                        if (match) log(tag) { "Found match at item: ${item.path.path}" }
                                        match
                                    }
                                    else -> false
                                }
                            }
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
internal fun ExplorerBackHandlers(
    hasPickerConfig: Boolean,
    useBackButtonForNavigation: Boolean,
    canGoBack: Boolean,
    isSelectionMode: Boolean,
    onGoBack: () -> Unit,
    onCancelPicker: () -> Unit,
    onClearSelection: () -> Unit,
) {
    // One handler, explicit priority: separate handlers would rank by BackHandler's LIFO
    // registration order, which conditional composition perturbs - the picker branch appears only
    // once picker state exists and would then outrank an already-registered selection handler.
    // `enabled` is exactly the disjunction of the `when` guards below; keep them in sync or back
    // gets consumed with nothing to do.
    WorkspaceBackHandler(
        enabled = isSelectionMode || hasPickerConfig || (useBackButtonForNavigation && canGoBack),
    ) {
        when {
            isSelectionMode -> onClearSelection()
            hasPickerConfig && canGoBack -> onGoBack()
            hasPickerConfig -> onCancelPicker()
            else -> onGoBack()
        }
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
            try {
                safPickerLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                // Devices with DocumentsUI disabled or missing resolve nothing for
                // ACTION_OPEN_DOCUMENT_TREE, and an uncaught throw here takes down the collector.
                vm.onSafPickerUnavailable(e)
            }
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
