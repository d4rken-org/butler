package eu.darken.butler.explorer.ui.explorer

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.Slogans
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogHost
import eu.darken.butler.explorer.ui.explorer.issues.ErrorSnackbar
import eu.darken.butler.explorer.ui.explorer.issues.IssueBottomSheet
import eu.darken.butler.explorer.ui.explorer.items.grid.LookupItemGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.PeekGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.ShortcutGrid
import eu.darken.butler.explorer.ui.explorer.items.row.LookupItemRow
import eu.darken.butler.explorer.ui.explorer.items.row.PeekRow
import eu.darken.butler.explorer.ui.explorer.items.row.ShortcutRow
import eu.darken.butler.explorer.ui.explorer.permissions.PermissionRequestCard
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.clipboard.ClipboardBar
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.OperationsBar
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import kotlinx.coroutines.flow.Flow

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

    ExplorerWorkspacePage(
        design = design,
        mainStateSource = vm.state,
        clipboardStateSource = vm.clipboard,
        operationsStateSource = vm.operations,
        workspaceStateSource = workspaceButtonVm.state,
        vm = vm,
        onWorkspaceAction = workspaceButtonVm::onWorkspaceAction,
        onNavToWorkspaceManager = workspaceButtonVm::onNavToWorkspaceManager,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerWorkspacePage(
    design: WorkspaceDesign = WorkspaceDesign(),
    mainStateSource: Flow<ExplorerWorkspaceViewModel.State>,
    operationsStateSource: Flow<ExplorerWorkspaceViewModel.OperationsState>,
    clipboardStateSource: Flow<ExplorerWorkspaceViewModel.ClipboardState>,
    workspaceStateSource: Flow<WorkspaceButtonViewModel.State?>,
    vm: ExplorerWorkspaceViewModel? = null,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
    initialOperationsExpanded: Boolean = false,
    initialClipboardExpanded: Boolean = false,
) {
    val mainState by mainStateSource.collectAsState(ExplorerWorkspaceViewModel.State())
    val operationsState by operationsStateSource.collectAsState(ExplorerWorkspaceViewModel.OperationsState())
    val clipboardState by clipboardStateSource.collectAsState(ExplorerWorkspaceViewModel.ClipboardState())
    val workspaceButtonState by workspaceStateSource.collectAsState(null)


    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val bottomBarScrollBehavior = rememberBottomBarScrollBehavior()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe conflict state
    val issueState by (vm?.issueState?.collectAsState() ?: remember { mutableStateOf(null) })
    var showIssueSheet by remember { mutableStateOf(false) }

    // Automatically show conflict sheet when conflictState becomes non-null
    LaunchedEffect(issueState) {
        if (issueState != null) showIssueSheet = true
    }

    // Listen for requests to show conflict sheet
    LaunchedEffect(vm) {
        vm?.showIssueSheetEvent?.collect { showIssueSheet = true }
    }

    // Operation dialog state
    var operationDialogState by remember { mutableStateOf<OperationDialogState>(OperationDialogState.None) }

    LaunchedEffect(mainState.locationId) {
        if (mainState.locationId != null) {
            if (mainState.viewMode == ExplorerWorkspaceViewModel.ViewMode.LIST) {
                listState.animateScrollToItem(0)
            } else {
                gridState.animateScrollToItem(0)
            }
            scrollBehavior.state.heightOffset = 0f
            bottomBarScrollBehavior.state.heightOffset = 0f
        }
    }

    // Set the bottom bar height for scroll behavior
    bottomBarScrollBehavior.state.setHeight(64.dp)

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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier,
                ) { data ->
                    ErrorSnackbar(snackbarData = data)
                }
            },
            topBar = {
                ExplorerTopBar(
                    breadcrumbs = mainState.breadcrumbs,
                    scrollBehavior = scrollBehavior,
                    onBreadcrumbClick = { target -> vm?.navigate(target) },
                    onNavigateToPath = { path -> vm?.navigateToPathString(path) },
                    workspaceButtonState = workspaceButtonState,
                    showWorkspaceButton = design.isSingle,
                    onWorkspaceAction = onWorkspaceAction,
                    onNavToWorkspaceManager = onNavToWorkspaceManager,
                )
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    ExplorerInfoBar(
                        info = mainState.info,
                        selectedCount = mainState.selectionState.selectedItems.size,
                    )

                    mainState.error?.let { error ->
                        NavigationErrorCard(
                            error = error,
                            onCopyError = { vm?.copyNavigationError() },
                            onRetry = { vm?.retryNavigation() },
                            onDismiss = { vm?.dismissNavigationError() },
                        )
                    }

                    val mainStateSnap = mainState
                    when {
                        mainStateSnap.permissionState.needsPermissions -> {
                            // Show permission request card when permissions are missing
                            PermissionRequestCard(
                                permissionState = mainState.permissionState,
                                onNavigateToSetup = {
                                    vm?.navigateToSetup()
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        mainStateSnap.items == null -> {
                            val randomSlogan = remember { Slogans.random }
                            EmptyState(
                                modifier = Modifier.fillMaxSize(),
                                slogan = randomSlogan.asComposable()
                            )
                        }
                        mainStateSnap.items.isEmpty() -> {
                            EmptyFolderState(
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> {
                            Box(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (mainStateSnap.viewMode == ExplorerWorkspaceViewModel.ViewMode.LIST) {
                                    PullToRefreshBox(
                                        isRefreshing = mainStateSnap.progress != null,
                                        onRefresh = { vm?.retryNavigation() }
                                    ) {
                                        LazyColumn(
                                        state = listState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                                            .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        contentPadding = PaddingValues(
                                            start = 12.dp,
                                            end = 12.dp,
                                            top = 12.dp,
                                            bottom = run {
                                                val actionBarHeight =
                                                    if (hasActions) 64.dp else 0.dp // 48dp + 16dp padding
                                                val clipboardHeight =
                                                    if (hasClipboard) 88.dp else 0.dp // ~80dp + 8dp padding
                                                val operationsHeight =
                                                    if (hasOperations) 80.dp else 0.dp // Operations bar height + padding
                                                actionBarHeight + clipboardHeight + operationsHeight + 12.dp // Extra space
                                            }
                                        )
                                    ) {
                                        items(mainStateSnap.items) { item ->
                                            when (item) {
                                                is ExplorerItem.Lookup -> LookupItemRow(
                                                    item = item,
                                                    isSelected = mainStateSnap.selectionState.selectedItems.contains(
                                                        item.id
                                                    ),
                                                    onToggleSelection = { vm?.toggleItemSelection(item) },
                                                    onClick = {
                                                        if (mainStateSnap.selectionState.selectedItems.isNotEmpty()) {
                                                            vm?.toggleItemSelection(item)
                                                        } else {
                                                            vm?.navigate(item)
                                                        }
                                                    },
                                                    onLongClick = { vm?.toggleItemSelection(item) },
                                                    showSelection = mainStateSnap.selectionState.selectedItems.isNotEmpty()
                                                )

                                                is ExplorerItem.Peek -> PeekRow(
                                                    item = item
                                                )

                                                is ExplorerItem.Shortcut -> ShortcutRow(
                                                    item = item,
                                                    onClick = { vm?.navigate(item) },
                                                )
                                            }
                                        }
                                    }
                                    }
                                } else {
                                    PullToRefreshBox(
                                        isRefreshing = mainStateSnap.progress != null,
                                        onRefresh = { vm?.retryNavigation() }
                                    ) {
                                        LazyVerticalGrid(
                                        state = gridState,
                                        columns = GridCells.Adaptive(minSize = 120.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                                            .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        contentPadding = PaddingValues(
                                            start = 2.dp,
                                            end = 2.dp,
                                            top = 2.dp,
                                            bottom = run {
                                                val actionBarHeight =
                                                    if (hasActions) 64.dp else 0.dp // 48dp + 16dp padding
                                                val clipboardHeight =
                                                    if (hasClipboard) 88.dp else 0.dp // ~80dp + 8dp padding
                                                val operationsHeight =
                                                    if (hasOperations) 80.dp else 0.dp // Operations bar height + padding
                                                actionBarHeight + clipboardHeight + operationsHeight + 2.dp // Extra space
                                            }
                                        )
                                    ) {
                                        items(mainStateSnap.items) { item ->
                                            when (item) {
                                                is ExplorerItem.Lookup -> LookupItemGrid(
                                                    item = item,
                                                    isSelected = mainStateSnap.selectionState.selectedItems.contains(
                                                        item.path.path
                                                    ),
                                                    onToggleSelection = { vm?.toggleItemSelection(item) },
                                                    onClick = {
                                                        if (mainStateSnap.selectionState.selectedItems.isNotEmpty()) {
                                                            vm?.toggleItemSelection(item)
                                                        } else {
                                                            vm?.navigate(item)
                                                        }
                                                    },
                                                    onLongClick = { vm?.toggleItemSelection(item) },
                                                    showSelection = mainStateSnap.selectionState.selectedItems.isNotEmpty()
                                                )
                                                is ExplorerItem.Shortcut -> ShortcutGrid(
                                                    item = item,
                                                    onClick = { vm?.navigate(item) },
                                                )

                                                is ExplorerItem.Peek -> PeekGrid(
                                                    item = item
                                                )
                                            }
                                        }
                                    }
                                    }
                                }
                            }
                        }
                    }
                }

                mainState.progress?.let {
                    LoadingProgressBar(
                        progress = it,
                        onCancel = { vm?.navigate(ExplorerNavigation.Cancel) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 40.dp) // Position below InfoBar + NavigationErrorCard
                    )
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
                        onCancelOperation = { id -> vm?.cancelOperation(id) },
                        onDismissOperation = { id -> vm?.dismissOperation(id) },
                        onOperationClick = { operation ->
                            when (operation.state) {
                                is OperationDisplay.State.Waiting -> {
                                    vm?.showConflictSheet()
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
            ExplorerActionBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .graphicsLayer {
                        // Immediate snap behavior: fully visible or fully hidden
                        alpha = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 0f else 1f
                        translationY = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 64.dp.toPx() else 0f
                    },
                actions = mainState.availableActions,
                onActionClick = { action -> vm?.executeAction(action) },
                onButlerIconClick = { vm?.onButlerIconClick() },
                isPro = mainState.isPro,
            )
        }

        ExplorerDialogHost(
            dialogState = mainState.dialogState,
            vm = vm
        )

        OperationDialogHost(
            dialogState = operationDialogState,
            operations = operationsState.operations,
            onDismissDialog = { operationDialogState = OperationDialogState.None },
            onCancelOperation = { vm?.cancelOperation(it) },
            onCopyError = { vm?.copyError(it) }
        )
    }

    // Show conflict bottom sheet when needed
    if (issueState != null && showIssueSheet) {
        IssueBottomSheet(
            issue = issueState!!,
            onResolution = { resolution -> vm?.resolveConflict(resolution) },
            onDismiss = { showIssueSheet = false },
        )
    }
}




