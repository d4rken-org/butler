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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.Slogans
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.keyboard.KeyboardShortcut
import eu.darken.butler.common.keyboard.keyboardShortcuts
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogHost
import eu.darken.butler.explorer.ui.explorer.issues.ErrorSnackbar
import eu.darken.butler.explorer.ui.explorer.issues.IssueBottomSheet
import eu.darken.butler.explorer.ui.explorer.items.grid.LookupItemGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.PeekGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.ShortcutGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.StorageGrid
import eu.darken.butler.explorer.ui.explorer.items.row.LookupItemRow
import eu.darken.butler.explorer.ui.explorer.items.row.PeekRow
import eu.darken.butler.explorer.ui.explorer.items.row.ShortcutRow
import eu.darken.butler.explorer.ui.explorer.items.row.StorageRow
import eu.darken.butler.explorer.ui.explorer.permissions.PermissionRequestCard
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
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
import eu.darken.butler.workspace.ui.scroll.rememberBottomBarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.setHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class)
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

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val bottomBarScrollBehavior = rememberBottomBarScrollBehavior()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Pull-to-refresh indicator state - shows briefly then hides to let progress banner take over
    var showPullToRefreshIndicator by remember { mutableStateOf(false) }

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
    var showCancelConfirmation by remember { mutableStateOf<Operation.Id?>(null) }

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
        BackHandler(enabled = true) {
            if (mainState.canGoBack) {
                // Navigate back through history
                vm?.goBack()
            } else {
                // At root, close workspace
                vm?.closeWorkspace()
            }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .keyboardShortcuts {
                on(KeyboardShortcut.Copy) {
                    val copyAction = mainState.availableActions
                        .filterIsInstance<ExplorerAction.Directory.Copy>()
                        .firstOrNull()
                    if (copyAction != null && copyAction.isEnabled) {
                        vm?.executeAction(copyAction)
                    }
                }
                on(KeyboardShortcut.Cut) {
                    val cutAction = mainState.availableActions
                        .filterIsInstance<ExplorerAction.Directory.Cut>()
                        .firstOrNull()
                    if (cutAction != null && cutAction.isEnabled) {
                        vm?.executeAction(cutAction)
                    }
                }
                on(KeyboardShortcut.Paste) {
                    clipboardState.entries.firstOrNull()?.let { clip -> vm?.pasteClipboard(clip) }
                }
                on(KeyboardShortcut.SelectAll) { vm?.selectAll() }
                on(KeyboardShortcut.New) {
                    val createAction = mainState.availableActions
                        .filterIsInstance<ExplorerAction.Directory.Create>()
                        .firstOrNull()
                    if (createAction != null && createAction.isEnabled) {
                        vm?.executeAction(createAction)
                    }
                }
                on(KeyboardShortcut.Delete) {
                    val deleteAction = mainState.availableActions
                        .filterIsInstance<ExplorerAction.Directory.Delete>()
                        .firstOrNull()
                    if (deleteAction != null && deleteAction.isEnabled) {
                        vm?.executeAction(deleteAction)
                    }
                }
                on(KeyboardShortcut.Escape) {
                    if (ExplorerAction.Directory.DeselectAll in mainState.availableActions) {
                        vm?.clearSelection()
                    }
                }
            }
    ) {
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
                val pickerConfig = mainState.pickerConfig
                if (pickerConfig != null) {
                    // Picker mode - use simplified picker top bar
                    eu.darken.butler.explorer.ui.picker.ExplorerPickerTopBar(
                        selection = pickerConfig.selection,
                        selectionCount = mainState.selectionState.selectedItems.size,
                        breadcrumbs = mainState.breadcrumbs,
                        currentLocation = mainState.currentLocation,
                        scrollBehavior = scrollBehavior,
                        onBreadcrumbClick = { navigation -> vm?.navigate(navigation) },
                        onCancel = { vm?.cancelPicker() },
                        onConfirm = { vm?.confirmPickerSelection() },
                    )
                } else {
                    // Normal mode - use full explorer top bar
                    ExplorerTopBar(
                        workspaceId = workspaceId,
                        breadcrumbs = mainState.breadcrumbs,
                        scrollBehavior = scrollBehavior,
                        onBreadcrumbClick = { target -> vm?.navigate(target) },
                        onNavigateToPath = { path -> vm?.navigateToPath(path) },
                        workspaceButtonState = workspaceButtonState,
                        showWorkspaceButton = design.isSingle,
                        workspaceActionHandler = workspaceActionHandler,
                        safLocationManager = vm?.safLocationManager,
                    )
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                PullToRefreshBox(
                    isRefreshing = showPullToRefreshIndicator,
                    onRefresh = handleRefresh
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        ExplorerInfoBar(
                            info = mainState.info,
                            selectedCount = mainState.selectionState.selectedItems.size,
                            onClearSelection = { vm?.clearSelection() },
                        )

                        mainState.error?.let { error ->
                            WorkspaceErrorCard(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                title = stringResource(R.string.explorer_navigation_error_title),
                                error = error,
                                onCopyError = { vm?.copyNavigationError() },
                                onRetry = { vm?.retryNavigation() },
                                onDismiss = { vm?.dismissNavigationError() },
                            )
                        }

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
                                BoxWithConstraints(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .heightIn(min = maxHeight)
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState()),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        EmptyDirectoryState()
                                    }
                                }
                            }
                            else -> {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    if (mainStateSnap.viewMode == ExplorerWorkspaceViewModel.ViewMode.LIST) {
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
                                            items(
                                                items = mainStateSnap.items,
                                                key = { it.id }
                                            ) { item ->
                                                when (item) {
                                                    is ExplorerItem.Lookup -> LookupItemRow(
                                                        item = item,
                                                        isSelected = mainStateSnap.selectionState.selectedItems.contains(
                                                            item
                                                        ),
                                                        onToggleSelection = { vm?.toggleItemSelection(item) },
                                                        onClick = { vm?.onItemClick(item) },
                                                        onLongClick = { vm?.onItemLongClick(item) },
                                                        showSelection = mainStateSnap.shouldShowSelection(item)
                                                    )

                                                    is ExplorerItem.Peek -> PeekRow(
                                                        item = item
                                                    )

                                                    is ExplorerItem.Shortcut -> ShortcutRow(
                                                        item = item,
                                                        onClick = { vm?.navigate(item) },
                                                    )

                                                    is ExplorerItem.Storage -> StorageRow(
                                                        item = item,
                                                        isSelected = mainStateSnap.selectionState.selectedItems.contains(
                                                            item
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
                                                        showSelection = mainStateSnap.selectionState.selectedItems.isNotEmpty() &&
                                                            item in mainStateSnap.selectionState.selectableItems
                                                    )
                                                }
                                            }
                                        }
                                    } else {
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
                                            items(
                                                items = mainStateSnap.items,
                                                key = { it.id }
                                            ) { item ->
                                                when (item) {
                                                    is ExplorerItem.Lookup -> LookupItemGrid(
                                                        item = item,
                                                        isSelected = mainStateSnap.selectionState.selectedItems.contains(
                                                            item
                                                        ),
                                                        onToggleSelection = { vm?.toggleItemSelection(item) },
                                                        onClick = { vm?.onItemClick(item) },
                                                        onLongClick = { vm?.onItemLongClick(item) },
                                                        showSelection = mainStateSnap.shouldShowSelection(item)
                                                    )
                                                    is ExplorerItem.Shortcut -> ShortcutGrid(
                                                        item = item,
                                                        onClick = { vm?.navigate(item) },
                                                    )

                                                    is ExplorerItem.Storage -> StorageGrid(
                                                        item = item,
                                                        isSelected = mainStateSnap.selectionState.selectedItems.contains(
                                                            item
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
                                                        showSelection = mainStateSnap.selectionState.selectedItems.isNotEmpty() &&
                                                            item in mainStateSnap.selectionState.selectableItems
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
                        translationY = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 64.dp.toPx() else 0f
                    },
                actions = mainState.availableActions,
                onActionClick = { action -> vm?.executeAction(action as ExplorerAction) },
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
            onCancelOperation = { operationId ->
                operationDialogState = OperationDialogState.None
                showCancelConfirmation = operationId
            },
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

    // Show add storage bottom sheet
    val showAddStorageSheet by (vm?.showAddStorageSheet?.collectAsState() ?: remember { mutableStateOf(false) })
    if (showAddStorageSheet) {
        eu.darken.butler.explorer.ui.explorer.dialogs.AddDeviceStorageSheet(
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
            path = LocalPath.build("/empty/folder"),
            items = emptyList(),
            progress = null,
        ),
        breadcrumbs = emptyList(),
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
            path = LocalPath.build("/empty/folder"),
            items = emptyList(),
            progress = null,
        ),
        breadcrumbs = emptyList(),
        items = emptyList(),
        error = ReadException(
            path = LocalPath.build("/empty/folder")
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
