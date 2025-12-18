package eu.darken.butler.explorer.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.dialogs.AddDeviceStorageSheet
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogHost
import eu.darken.butler.explorer.ui.explorer.elements.EmptyDirectoryState
import eu.darken.butler.explorer.ui.explorer.elements.EmptyState
import eu.darken.butler.explorer.ui.explorer.elements.ErrorSnackbar
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerInfoBar
import eu.darken.butler.explorer.ui.explorer.elements.ExplorerToolbarCard
import eu.darken.butler.explorer.ui.explorer.elements.LoadingProgressBar
import eu.darken.butler.explorer.ui.explorer.elements.PermissionRequestCard
import eu.darken.butler.explorer.ui.explorer.items.ExplorerItemRenderer
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.explorer.ui.explorer.util.OpenDocumentTreeWithIntent
import eu.darken.butler.explorer.ui.explorer.util.explorerKeyboardShortcuts
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBar
import eu.darken.butler.workspace.ui.error.WorkspaceErrorCard
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.OperationsBar
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationDialog
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import eu.darken.butler.workspace.ui.scroll.rememberBottomBarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.rememberTopToolbarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.setHeight
import eu.darken.butler.workspace.ui.scroll.setHeights
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    mainStateSource: Flow<ExplorerWorkspaceViewModel.State>,
    operationsStateSource: Flow<ExplorerWorkspaceViewModel.OperationsState>,
    clipboardStateSource: Flow<ExplorerWorkspaceViewModel.ClipboardState>,
    workspaceStateSource: Flow<WorkspaceButtonViewModel.State?>,
    vm: ExplorerWorkspaceViewModel? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    initialOperationsExpanded: Boolean = false,
    initialClipboardExpanded: Boolean = false,
) {
    val mainState by mainStateSource.collectAsState(ExplorerWorkspaceViewModel.State())
    val operationsState by operationsStateSource.collectAsState(ExplorerWorkspaceViewModel.OperationsState())
    val clipboardState by clipboardStateSource.collectAsState(ExplorerWorkspaceViewModel.ClipboardState())
    val workspaceButtonState by workspaceStateSource.collectAsState(null)
    val isWorkspaceFocused = LocalWorkspaceFocused.current

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val topToolbarScrollBehavior = rememberTopToolbarScrollBehavior()
    val bottomBarScrollBehavior = rememberBottomBarScrollBehavior()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Focus state comes from ViewModel (survives rotation)
    val focusedItem = mainState.focusedItemIndex?.let { mainState.items?.getOrNull(it) }

    // Track actual measured height of the toolbar card
    val density = LocalDensity.current
    var actualToolbarHeightPx by remember { mutableIntStateOf(0) }
    val actualToolbarHeightDp = with(density) { actualToolbarHeightPx.toDp() }

    // Track actual measured height of the info bar
    var actualInfoBarHeightPx by remember { mutableIntStateOf(0) }
    val actualInfoBarHeightDp = with(density) { actualInfoBarHeightPx.toDp() }

    // Set the top toolbar heights (expanded and collapsed)
    topToolbarScrollBehavior.state.setHeights(
        expandedHeightDp = 56.dp,
        collapsedHeightDp = 44.dp
    )

    // Pull-to-refresh indicator state - shows briefly then hides to let progress banner take over
    var showPullToRefreshIndicator by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

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

    // Save scroll position when navigating away from current location
    DisposableEffect(mainState.locationId) {
        val locationId = mainState.locationId
        onDispose {
            if (locationId != null) {
                val (index, offset) = when (mainState.viewStyle) {
                    is ExplorerViewStyle.Grid -> gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                    is ExplorerViewStyle.List -> listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                }
                vm?.saveScrollPosition(locationId, index, offset)
            }
        }
    }

    // Track previous location to detect actual navigation vs item updates
    var previousLocationId by remember { mutableStateOf<String?>(null) }
    val hasItems = mainState.items != null

    // Restore or reset scroll position ONLY when navigating to a different location
    LaunchedEffect(mainState.locationId, hasItems) {
        val scrollTag = logTag("Explorer", "Page", "ScrollRestore")
        val locationId = mainState.locationId ?: return@LaunchedEffect

        // Wait for items to be loaded before restoring scroll
        if (!hasItems) return@LaunchedEffect

        // Skip if this is the same location (items just updated, not navigation)
        if (locationId == previousLocationId) return@LaunchedEffect
        previousLocationId = locationId

        log(scrollTag) { "Restoring scroll for $locationId" }
        val savedPosition = vm?.getScrollPosition(locationId)

        if (savedPosition != null) {
            // Restore saved position (coming back to previously visited location)
            when (mainState.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.scrollToItem(savedPosition.first, savedPosition.second)
                is ExplorerViewStyle.List -> listState.scrollToItem(savedPosition.first, savedPosition.second)
            }
        } else {
            // New location - scroll to top
            when (mainState.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.scrollToItem(0)
                is ExplorerViewStyle.List -> listState.scrollToItem(0)
            }
        }

        // Always reset toolbar visibility on navigation for proper orientation
        topToolbarScrollBehavior.state.heightOffset = 0f
        bottomBarScrollBehavior.state.heightOffset = 0f
    }

    // Synchronize scroll position when view mode changes
    LaunchedEffect(mainState.viewStyle) {
        val items = mainState.items
        if (items != null && items.isNotEmpty()) {
            val currentIndex = when (mainState.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.firstVisibleItemIndex
                is ExplorerViewStyle.List -> listState.firstVisibleItemIndex
            }

            // Apply the scroll position to the new view mode
            when (mainState.viewStyle) {
                is ExplorerViewStyle.Grid -> gridState.scrollToItem(currentIndex, 0)
                is ExplorerViewStyle.List -> listState.scrollToItem(currentIndex, 0)
            }
        }
    }

    // Auto-scroll to top when sort settings change
    LaunchedEffect(mainState.sortSettings) {
        when (mainState.viewStyle) {
            is ExplorerViewStyle.Grid -> gridState.animateScrollToItem(0)
            is ExplorerViewStyle.List -> listState.animateScrollToItem(0)
        }
    }

    // Auto-scroll to keep focused item visible during keyboard navigation
    LaunchedEffect(mainState.focusedItemIndex) {
        val focusedIndex = mainState.focusedItemIndex ?: return@LaunchedEffect
        when (mainState.viewStyle) {
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
            // Single observation to get both index and viewStyle (avoid redundant .first() call)
            val result = mainStateSource
                .mapNotNull { state ->
                    val items = state.items
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
                    index?.takeIf { it >= 0 }?.let { it to state.viewStyle }
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

    // Set the bottom bar height for scroll behavior
    bottomBarScrollBehavior.state.setHeight(64.dp)

    // Handle back button for picker mode
    if (mainState.pickerConfig != null) {
        BackHandler(enabled = true) {
            if (mainState.canGoBack) {
                // Navigate up in directory hierarchy
                vm?.goBack()
            } else {
                // At root, dismiss picker
                vm?.cancelPicker()
            }
        }
    }

    // Handle back button for navigation history (when setting enabled)
    if (mainState.useBackButtonForNavigation && mainState.pickerConfig == null) {
        BackHandler(enabled = mainState.canGoBack) {
            vm?.goBack()
        }
    }

    // Derived states for stable recomposition
    val hasOperations by remember {
        derivedStateOf { operationsState.operations.isNotEmpty() }
    }
    val hasClipboard by remember {
        derivedStateOf { clipboardState.entries.isNotEmpty() }
    }
    val hasActions by remember {
        derivedStateOf { mainState.availableActions.isNotEmpty() }
    }

    // Auto-show action bar when entering selection mode
    LaunchedEffect(mainState.selectionState.isSelectionMode) {
        if (mainState.selectionState.isSelectionMode) {
            // Smoothly animate action bar to visible when selection is activated
            bottomBarScrollBehavior.state.animateToExpanded()
        }
    }

    // Pull-to-refresh handler - shows indicator for 200ms then hides
    val handleRefresh: () -> Unit = {
        coroutineScope.launch {
            showPullToRefreshIndicator = true
            vm?.retryNavigation()
            delay(200)
            showPullToRefreshIndicator = false
        }
    }

    // Track action bar visibility for clipboard animations
    val isActionBarHidden by remember {
        derivedStateOf {
            bottomBarScrollBehavior.state.collapsedFraction > 0.1f || !hasActions
        }
    }

    // Animate clipboard bar position playfully based on action bar state
    val clipboardVerticalOffset by animateFloatAsState(
        targetValue = if (isActionBarHidden) 8f else 64f, // Drop to bottom when action bar hidden or no actions
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "clipboardOffset"
    )

    // Add slight scale animation for extra playfulness
    val clipboardScale by animateFloatAsState(
        targetValue = if (isActionBarHidden) 1.02f else 1f, // Slightly bigger when expanded
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "clipboardScale"
    )

    // Grid columns for keyboard navigation (approximate for adaptive grid)
    val gridColumns = 3

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .explorerKeyboardShortcuts(
                availableActions = mainState.availableActions,
                clipboardEntries = clipboardState.entries,
                selectedItems = mainState.selectionState.selectedItems,
                focusedItem = focusedItem,
                viewStyle = mainState.viewStyle,
                gridColumns = gridColumns,
                trashEnabled = mainState.trashEnabled,
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
                onRenameFocusedItem = { (focusedItem as? ExplorerItem.Lookup)?.let { vm?.renameFile(it) } },
                onDeleteFocusedItem = { vm?.deleteFocusedItem() },
                onPermanentDeleteFocusedItem = {
                    // If items are selected, permanently delete them; otherwise delete focused item
                    if (mainState.selectionState.selectedItems.isNotEmpty()) {
                        vm?.permanentDeleteSelectedItems()
                    } else {
                        vm?.deleteFocusedItem(forcePermDelete = true)
                    }
                },
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Determine if info bar should be visible
            val showInfoBar = mainState.info != null || mainState.selectionState.selectedItems.isNotEmpty()

            // Content padding calculation
            val topContentPadding = 8.dp + // Distance between screen and bars
                actualToolbarHeightDp +
                (if (showInfoBar) actualInfoBarHeightDp + 8.dp else 0.dp) +
                8.dp // Padding between list and upper toolbars

            // Offset for pull-to-refresh indicator to appear below toolbar/info bar
            val indicatorOffset = 8.dp + actualToolbarHeightDp +
                (if (showInfoBar) actualInfoBarHeightDp + 8.dp else 0.dp)

            // Main content with PullToRefresh
            PullToRefreshBox(
                isRefreshing = showPullToRefreshIndicator,
                onRefresh = handleRefresh,
                modifier = Modifier.fillMaxSize(),
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = indicatorOffset),
                        state = pullToRefreshState,
                        isRefreshing = showPullToRefreshIndicator,
                    )
                },
            ) {
                val mainStateSnap = mainState
                when {
                    mainStateSnap.setupRequirements.needsAction -> {
                        // Show setup request or SAF picker card when action needed
                        PermissionRequestCard(
                            setupRequirements = mainState.setupRequirements,
                            onNavigateToSetup = {
                                vm?.navigateToSetup(mainState.setupRequirements)
                            },
                            onLaunchSAFPicker = { grant ->
                                vm?.launchAndroidDataSAFPicker(grant)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = topContentPadding),
                        )
                    }
                    else -> {
                        // Always render the lazy list/grid for structural stability
                        when (mainStateSnap.viewStyle) {
                            is ExplorerViewStyle.List -> {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .nestedScroll(topToolbarScrollBehavior.nestedScrollConnection)
                                        .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    contentPadding = PaddingValues(
                                        start = 12.dp,
                                        end = 12.dp,
                                        top = topContentPadding,
                                        bottom = run {
                                            val actionBarHeight = if (hasActions) 64.dp else 0.dp
                                            val clipboardHeight = if (hasClipboard) 88.dp else 0.dp
                                            val operationsHeight = if (hasOperations) 80.dp else 0.dp
                                            actionBarHeight + clipboardHeight + operationsHeight + 12.dp
                                        }
                                    )
                                ) {
                                    // Handle loading state
                                    if (mainStateSnap.items == null) {
                                        item(key = "loading") {
                                            EmptyState(modifier = Modifier.fillParentMaxSize())
                                        }
                                    }
                                    // Handle empty directory state
                                    else if (mainStateSnap.items.isEmpty()) {
                                        item(key = "empty") {
                                            Box(
                                                modifier = Modifier.fillParentMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                EmptyDirectoryState()
                                            }
                                        }
                                    }
                                    // Handle normal items
                                    else {
                                        items(
                                            items = mainStateSnap.items,
                                            key = { it.id }
                                        ) { item ->
                                            ExplorerItemRenderer(
                                                item = item,
                                                viewStyle = mainStateSnap.viewStyle,
                                                state = mainStateSnap,
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
                                        .nestedScroll(topToolbarScrollBehavior.nestedScrollConnection)
                                        .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    contentPadding = PaddingValues(
                                        start = 2.dp,
                                        end = 2.dp,
                                        top = topContentPadding,
                                        bottom = run {
                                            val actionBarHeight = if (hasActions) 64.dp else 0.dp
                                            val clipboardHeight = if (hasClipboard) 88.dp else 0.dp
                                            val operationsHeight = if (hasOperations) 80.dp else 0.dp
                                            actionBarHeight + clipboardHeight + operationsHeight + 2.dp
                                        }
                                    )
                                ) {
                                    // Handle loading state
                                    if (mainStateSnap.items == null) {
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                                            EmptyState(modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                    // Handle empty directory state
                                    else if (mainStateSnap.items.isEmpty()) {
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                EmptyDirectoryState()
                                            }
                                        }
                                    }
                                    // Handle normal items
                                    else {
                                        items(
                                            items = mainStateSnap.items,
                                            key = { it.id }
                                        ) { item ->
                                            ExplorerItemRenderer(
                                                item = item,
                                                viewStyle = mainStateSnap.viewStyle,
                                                state = mainStateSnap,
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
                }
            }

            // Error card (floating)
            mainState.error?.let { error ->
                WorkspaceErrorCard(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 8.dp + actualToolbarHeightDp)
                        .padding(horizontal = 16.dp),
                    title = stringResource(R.string.explorer_navigation_error_title),
                    error = error,
                    onCopyError = { vm?.copyNavigationError() },
                    onRetry = { vm?.retryNavigation() },
                    onDismiss = { vm?.dismissNavigationError() },
                )
            }

            // Loading progress bar (floating)
            mainState.progress?.let {
                LoadingProgressBar(
                    progress = it,
                    onCancel = { vm?.navigate(ExplorerNavigation.Cancel) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 8.dp + actualToolbarHeightDp + (if (showInfoBar) actualInfoBarHeightDp + 8.dp else 0.dp))
                        .padding(horizontal = 16.dp)
                )
            }

            // Floating toolbar card at top
            ExplorerToolbarCard(
                workspaceId = workspaceId,
                breadcrumbs = mainState.breadcrumbs,
                design = design,
                collapsedFraction = topToolbarScrollBehavior.state.collapsedFraction,
                onBreadcrumbClick = { target -> vm?.navigate(target) },
                onNavigateToPath = { path -> vm?.navigateToPath(path) },
                onSetAsHome = { target -> vm?.setAsDefaultStartLocation(target) },
                onCopyPath = { path -> vm?.copyPathToSystemClipboard(path) },
                workspaceButtonState = workspaceButtonState,
                workspaceActionHandler = workspaceActionHandler,
                safLocationManager = vm?.safLocationManager,
                pickerSelection = mainState.pickerConfig?.selection,
                selectionCount = mainState.selectionState.selectedItems.size,
                saveAsFilename = mainState.saveAsFilename,
                canConfirmSelection = mainState.canConfirmSelection,
                onSaveAsFilenameChange = { filename -> vm?.updateSaveAsFilename(filename) },
                onCancel = { vm?.cancelPicker() },
                onConfirm = { vm?.confirmPickerSelection() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onGloballyPositioned { layoutCoordinates ->
                        actualToolbarHeightPx = layoutCoordinates.size.height
                    }
            )

            // Floating info bar below toolbar
            if (showInfoBar) {
                ExplorerInfoBar(
                    info = mainState.info,
                    selectedCount = mainState.selectionState.selectedItems.size,
                    onClearSelection = { vm?.clearSelection() },
                    isTrashDisabled = !mainState.trashEnabled,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 16.dp + actualToolbarHeightDp)
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned { layoutCoordinates ->
                            actualInfoBarHeightPx = layoutCoordinates.size.height
                        }
                )
            }

            // Snackbar host
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { data ->
                ErrorSnackbar(snackbarData = data)
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
                            onCancelOperation = { id -> vm?.cancelOperation(id) },
                            onDismissOperation = { id -> vm?.dismissOperation(id) },
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
                            onClearCompleted = { vm?.clearCompletedOperations() },
                            initialExpanded = initialOperationsExpanded,
                        )
                    }

                    // Clipboard Bar (bottom)
                    AnimatedVisibility(
                        visible = hasClipboard,
                        enter = slideInVertically(animationSpec = tween(150)) { it },
                        exit = slideOutVertically(animationSpec = tween(150)) { it },
                    ) {
                        ClipboardBar(
                            workspaceType = Workspace.Type.EXPLORER,
                            clipboardEntries = clipboardState.entries,
                            onPasteClick = { clip -> vm?.pasteClipboard(clip) },
                            onRemoveClick = { clip -> vm?.removeClipboardEntry(clip) },
                            onEntryClick = { clip ->
                                vm?.showClipboardInfo(clip)
                            },
                            onClearAll = { vm?.clearAllClipboard() },
                            initialExpanded = initialClipboardExpanded,
                        )
                    }
                }
            }

            // Floating Bottom ActionBar
            if (hasActions) {
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
                    actions = mainState.availableActions,
                    onActionClick = { action -> vm?.executeAction(action as ExplorerAction) },
                    onActionLongClick = { action -> vm?.executeActionLongClick(action as ExplorerAction) },
                )
            }

            ExplorerDialogHost(
                dialogState = mainState.dialogState,
                trashEnabled = mainState.trashEnabled,
                vm = vm
            )

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

            // Show conflict bottom sheet when needed
            if (issueState != null && showIssueSheet) {
                IssuesBottomSheet(
                    issue = issueState!!,
                    onResolution = { resolution -> vm?.resolveConflict(resolution) },
                    onDismiss = { showIssueSheet = false },
                )
            }
        } // Box
    }

    // Show add storage bottom sheet
    val showAddStorageSheet by (vm?.showAddStorageSheet?.collectAsState() ?: remember { mutableStateOf(false) })
    if (showAddStorageSheet) {
        AddDeviceStorageSheet(
            onDismiss = { vm?.dismissAddStorageSheet() },
            onContinue = { vm?.addSAFLocation() }
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
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm, workspaceButtonVm)

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // SAF directory picker launcher
    val safPickerLauncher = rememberLauncherForActivityResult(
        OpenDocumentTreeWithIntent()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                val grant = vm.pendingSAFPickerGrant.first()
                if (grant != null) {
                    // Android/data workaround flow - auto-label
                    vm.handleAndroidDataSAFPickerResult(it, grant)
                } else {
                    // Manual addition flow - prompt for label
                    vm.handleSAFPickerResult(it)
                }
            }
        }
    }

    // Handle SAF picker launch events
    LaunchedEffect(vm) {
        vm.safPickerEvents.collect { intent ->
            safPickerLauncher.launch(intent)
        }
    }

    ExplorerWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = vm.state,
        clipboardStateSource = vm.clipboard,
        operationsStateSource = vm.operations,
        workspaceStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
    )
}

@Preview2
@Composable
fun ExplorerWorkspacePagePreview() {
    val mockState = ExplorerWorkspaceViewModel.State(
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
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_device.toCaString(),
                target = ExplorerNavigation.Target.Device
            ),
            ExplorerBreadcrumb(
                label = "storage".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage"))
            ),
            ExplorerBreadcrumb(
                label = "emulated".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated"))
            ),
            ExplorerBreadcrumb(
                label = "0".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0"))
            )
        ),
        items = MockDataProvider.createAllFileTypes(),
        availableActions = listOf(
            ExplorerAction.Directory.Create(isEnabled = false),
            ExplorerAction.Common.Sort(),
            ExplorerAction.Common.Filter(isEnabled = false),
        ),
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
        )
    }
}

@Preview2
@Composable
fun ExplorerWorkspacePageEmptyPreview() {
    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/sdcard/EmptyFolder"),
            items = emptyList(),
            progress = null,
        ),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_home.toCaString(),
                target = ExplorerNavigation.Target.Home
            ),
            ExplorerBreadcrumb(
                label = "sdcard".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard"))
            ),
            ExplorerBreadcrumb(
                label = "EmptyFolder".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/EmptyFolder"))
            ),
        ),
        items = emptyList(),
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
        )
    }
}


@Preview2
@Composable
fun ExplorerWorkspacePageErrorPreview() {
    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/permission/denied"),
            items = emptyList(),
            progress = null,
        ),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_home.toCaString(),
                target = ExplorerNavigation.Target.Home
            ),
            ExplorerBreadcrumb(
                label = "permission".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/permission"))
            ),
            ExplorerBreadcrumb(
                label = "denied".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/permission/denied"))
            ),
        ),
        items = emptyList(),
        error = ReadException(
            path = LocalPath.build("/permission/denied")
        )
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
        )
    }
}

@Preview2
@Composable
fun ExplorerWorkspacePageWithAllBarsPreview() {
    val mockFileItems = MockDataProvider.createAllFileTypes()
    val mockOperations = MockDataProvider.createMockOperationsState(runningCount = 2, completedCount = 1)
    val mockClipboardEntries = MockDataProvider.createMockClipboardState(copyCount = 2, cutCount = 1)

    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/storage/emulated/0"),
            items = MockDataProvider.createAllFileTypes(),
            info = ExplorerLocation.Directory.Info(
                fileCount = 25,
                directoryCount = 8,
                totalSize = 1024L * 1024L * 512L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 32L,
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
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_device.toCaString(),
                target = ExplorerNavigation.Target.Device
            ),
            ExplorerBreadcrumb(
                label = "0".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0"))
            )
        ),
        items = mockFileItems,
        availableActions = listOf(
            ExplorerAction.Directory.Create(isEnabled = true),
            ExplorerAction.Common.Sort(),
            ExplorerAction.Common.Filter(isEnabled = true),
        ),
        selectionState = ExplorerSelectionState(
            selectedItems = setOf(mockFileItems[0], mockFileItems[2]),
            selectableItems = setOf(mockFileItems[0], mockFileItems[2]),
        ),
    )

    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(mockState),
            clipboardStateSource = flowOf(mockClipboardEntries),
            operationsStateSource = flowOf(mockOperations),
            workspaceStateSource = flowOf(null),
            vm = null,
        )
    }
}

@Preview2
@Composable
private fun ExplorerPickerMode_MixedMultiPreview() {
    val mockPickerConfig = PickerConfig(
        selection = PickerConfig.Selection.MixedMulti,
        callerWorkspaceId = Workspace.Id(),
    )

    val mockItems = listOf(
        MockDataProvider.createMockDirectory("Photos", childCount = 234),
        MockDataProvider.createMockDirectory("Videos", childCount = 56),
        MockDataProvider.createMockDirectory("Music", childCount = 189),
        MockDataProvider.createMockRegularFile("vacation.jpg"),
        MockDataProvider.createMockRegularFile("recipe.pdf"),
        MockDataProvider.createMockRegularFile("notes.txt"),
        MockDataProvider.createMockRegularFile("budget.xlsx"),
    )

    val mockState = ExplorerWorkspaceViewModel.State(
        pickerConfig = mockPickerConfig,
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/sdcard/Documents"),
            items = mockItems,
            info = ExplorerLocation.Directory.Info(
                fileCount = 4,
                directoryCount = 3,
                totalSize = 1024L * 1024L * 512L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 28L,
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
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_device.toCaString(),
                target = ExplorerNavigation.Target.Device
            ),
            ExplorerBreadcrumb(
                label = "sdcard".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard"))
            ),
            ExplorerBreadcrumb(
                label = "Documents".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Documents"))
            )
        ),
        items = mockItems,
        selectionState = ExplorerSelectionState(
            selectedItems = setOf(mockItems[0], mockItems[2], mockItems[3], mockItems[5], mockItems[6]),
            selectableItems = mockItems.toSet(),
        ),
    )

    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
            workspaceActionHandler = null,
        )
    }
}

