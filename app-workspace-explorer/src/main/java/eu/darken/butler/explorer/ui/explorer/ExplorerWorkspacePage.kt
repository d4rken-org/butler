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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.common.ConflictBottomSheet
import eu.darken.butler.explorer.ui.common.ErrorSnackbar
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogHost
import eu.darken.butler.explorer.ui.explorer.items.grid.PathItemGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.ShortcutGrid
import eu.darken.butler.explorer.ui.explorer.items.row.PathItemRow
import eu.darken.butler.explorer.ui.explorer.items.row.ShortcutRow
import eu.darken.butler.explorer.ui.explorer.permissions.PermissionRequestCard
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.clipboard.ClipboardBar
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

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

    val workspaceButtonState by workspaceButtonVm.state.collectAsState(null)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: ${state?.items?.size} items" }

    state?.let { state ->
        ExplorerWorkspacePage(
            design = design,
            state = state,
            vm = vm,
            workspaceButtonState = workspaceButtonState,
            onWorkspaceAction = workspaceButtonVm::onWorkspaceAction,
            onNavToWorkspaceManager = workspaceButtonVm::onNavToWorkspaceManager,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerWorkspacePage(
    design: WorkspaceDesign = WorkspaceDesign(),
    state: ExplorerWorkspaceViewModel.State,
    vm: ExplorerWorkspaceViewModel? = null,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val bottomBarScrollBehavior = rememberBottomBarScrollBehavior()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe conflict state
    val conflictState by (vm?.conflictState?.collectAsState() ?: remember { mutableStateOf(null) })

    LaunchedEffect(state.locationId) {
        if (state.locationId != null) {
            if (state.viewMode == ExplorerWorkspaceViewModel.ViewMode.LIST) {
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

    // Track action bar visibility for clipboard animations
    val isActionBarHidden by remember {
        derivedStateOf {
            bottomBarScrollBehavior.state.collapsedFraction > 0.1f || state.availableActions.isEmpty()
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
                    breadcrumbs = state.breadcrumbs,
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                // InfoBar moved to top
                ExplorerInfoBar(
                    info = state.currentLocation?.info,
                    selectedCount = state.selectionState.selectedItems.size,
                )

                if (state.permissionState.needsPermissions) {
                    // Show permission request card when permissions are missing
                    PermissionRequestCard(
                        permissionState = state.permissionState,
                        onNavigateToSetup = {
                            vm?.navigateToSetup()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (state.isLoading) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.explorer_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                } else if (state.items.isEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection)
                    ) {
                        item {
                            EmptyFolderState(
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (state.viewMode == ExplorerWorkspaceViewModel.ViewMode.LIST) {
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
                                            if (state.availableActions.isNotEmpty()) 64.dp else 0.dp // 48dp + 16dp padding
                                        val clipboardHeight =
                                            if (state.clipboardEntries.isNotEmpty()) 88.dp else 0.dp // ~80dp + 8dp padding
                                        actionBarHeight + clipboardHeight + 12.dp // Extra space
                                    }
                                )
                            ) {
                                items(state.items) { item ->
                                    when (item) {
                                        is ExplorerItem.PathItem -> PathItemRow(
                                            item = item,
                                            isSelected = state.selectionState.selectedItems.contains(item.id),
                                            onToggleSelection = { vm?.toggleItemSelection(item) },
                                            onClick = {
                                                if (state.selectionState.selectedItems.isNotEmpty()) {
                                                    vm?.toggleItemSelection(item)
                                                } else {
                                                    vm?.navigate(item)
                                                }
                                            },
                                            onLongClick = { vm?.toggleItemSelection(item) },
                                            showSelection = state.selectionState.selectedItems.isNotEmpty()
                                        )
                                        is ExplorerItem.Shortcut -> ShortcutRow(
                                            item = item,
                                            onClick = { vm?.navigate(item) },
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
                                            if (state.availableActions.isNotEmpty()) 64.dp else 0.dp // 48dp + 16dp padding
                                        val clipboardHeight =
                                            if (state.clipboardEntries.isNotEmpty()) 88.dp else 0.dp // ~80dp + 8dp padding
                                        actionBarHeight + clipboardHeight + 2.dp // Extra space
                                    }
                                )
                            ) {
                                items(state.items) { item ->
                                    when (item) {
                                        is ExplorerItem.PathItem -> PathItemGrid(
                                            item = item,
                                            isSelected = state.selectionState.selectedItems.contains(item.lookup.path),
                                            onToggleSelection = { vm?.toggleItemSelection(item) },
                                            onClick = {
                                                if (state.selectionState.selectedItems.isNotEmpty()) {
                                                    vm?.toggleItemSelection(item)
                                                } else {
                                                    vm?.navigate(item)
                                                }
                                            },
                                            onLongClick = { vm?.toggleItemSelection(item) },
                                            showSelection = state.selectionState.selectedItems.isNotEmpty()
                                        )
                                        is ExplorerItem.Shortcut -> ShortcutGrid(
                                            item = item,
                                            onClick = { vm?.navigate(item) },
                                        )
                                    }
                                }
                            }
                        }

                        if (state.isLoadingExtended) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating ClipboardBar
        AnimatedVisibility(
            visible = state.clipboardEntries.isNotEmpty(),
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
            ClipboardBar(
                clipboardEntries = state.clipboardEntries,
                onPasteClick = { clip -> vm?.pasteClipboard(clip) },
                onRemoveClick = { clip -> vm?.removeClipboardEntry(clip) },
                onEntryClick = { clip ->
                    // TODO: Show detailed clipboard info dialog with file details, navigation options, and preview
                },
                onClearAll = { vm?.clearAllClipboard() },
            )
        }

        // Floating Bottom ActionBar
        if (state.availableActions.isNotEmpty()) {
            ExplorerActionBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .graphicsLayer {
                        // Immediate snap behavior: fully visible or fully hidden
                        alpha = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 0f else 1f
                        translationY = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 64.dp.toPx() else 0f
                    },
                actions = state.availableActions,
                onActionClick = { action -> vm?.executeAction(action) },
            )
        }

        ExplorerDialogHost(
            dialogState = state.dialogState,
            vm = vm
        )
    }

    // Show conflict bottom sheet when needed
    conflictState?.let { conflict ->
        ConflictBottomSheet(
            conflict = conflict,
            onResolution = { resolution -> vm?.resolveConflict(resolution) },
            onDismiss = { vm?.resolveConflict(null) },
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
            )
        ),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_nav_home.toCaString(),
                target = ExplorerNavigation.Target.Home
            ),
            ExplorerBreadcrumb(
                label = R.string.explorer_nav_device.toCaString(),
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
        isLoading = false,
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            state = mockState,
            vm = null,
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerWorkspacePageLoadingPreview() {
    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(LocalPath.build("/storage/emulated/0")),
        breadcrumbs = emptyList(),
        items = emptyList(),
        isLoading = true,
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            state = mockState,
            vm = null,
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerWorkspacePageEmptyPreview() {
    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(LocalPath.build("/empty/folder")),
        breadcrumbs = emptyList(),
        items = emptyList(),
        isLoading = false,
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            state = mockState,
            vm = null,
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerWorkspacePageWithSelectionPreview() {
    val mockFileItems = MockDataProvider.createAllFileTypes()
    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(LocalPath.build("/storage/emulated/0")),
        breadcrumbs = emptyList(),
        items = mockFileItems,
        isLoading = false,
        selectionState = ExplorerSelectionState(
            selectedItems = setOf(mockFileItems[0].lookup.path, mockFileItems[2].lookup.path),
            selectableItems = setOf(mockFileItems[0].lookup.path, mockFileItems[2].lookup.path),
        ),
        availableActions = listOf(
            ExplorerAction.Common.Sort(),
            ExplorerAction.Common.Filter(isEnabled = false),
        ),
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            state = mockState,
            vm = null,
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerWorkspacePageGridModePreview() {
    val mockBreadcrumbs = listOf(
        ExplorerBreadcrumb(
            label = "Home".toCaString(),
            target = ExplorerNavigation.Target.Home
        ),
        ExplorerBreadcrumb(
            label = "Pictures".toCaString(),
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0/Pictures"))
        )
    )
    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(LocalPath.build("/storage/emulated/0/Pictures")),
        breadcrumbs = mockBreadcrumbs,
        items = MockDataProvider.createAllFileTypes(),
        isLoading = false,
        viewMode = ExplorerWorkspaceViewModel.ViewMode.GRID,
        availableActions = listOf(
            ExplorerAction.Common.Sort(),
            ExplorerAction.Common.Filter(isEnabled = false),
        ),
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            state = mockState,
            vm = null,
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerWorkspacePageGridModeWithSelectionPreview() {
    val mockFileItems = MockDataProvider.createAllFileTypes()
    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(LocalPath.build("/storage/emulated/0/Downloads")),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_nav_device.toCaString(),
                target = ExplorerNavigation.Target.Device
            ),
            ExplorerBreadcrumb(
                label = "Downloads".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0/Downloads"))
            )
        ),
        items = mockFileItems,
        isLoading = false,
        selectionState = ExplorerSelectionState(
            selectedItems = setOf(mockFileItems[0].lookup.path, mockFileItems[2].lookup.path),
            selectableItems = setOf(
                mockFileItems[0].lookup.path,
                mockFileItems[2].lookup.path,
                mockFileItems[3].lookup.path
            ),
        ),
        viewMode = ExplorerWorkspaceViewModel.ViewMode.GRID,
        availableActions = listOf(
            ExplorerAction.Common.Sort(),
            ExplorerAction.Common.Filter(isEnabled = false),
        ),
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            state = mockState,
            vm = null,
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
        )
    }
}



