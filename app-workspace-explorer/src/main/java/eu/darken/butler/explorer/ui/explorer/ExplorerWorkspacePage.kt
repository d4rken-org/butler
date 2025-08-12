package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.items.row.PathItemRow
import eu.darken.butler.explorer.ui.explorer.items.row.ShortcutRow
import eu.darken.butler.explorer.ui.explorer.items.grid.PathItemGrid
import eu.darken.butler.explorer.ui.explorer.items.grid.ShortcutGrid
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogHost
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
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
    log(vm.tag) { "Compose state: $state" }

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
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var isBottomBarVisible by remember { mutableStateOf(true) }
    
    LaunchedEffect(listState, gridState, state.viewMode) {
        var previousIndex = 0
        var previousScrollOffset = 0
        
        if (state.viewMode == ExplorerWorkspaceViewModel.ViewMode.LIST) {
            snapshotFlow { 
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset 
            }.collect { (currentIndex, currentOffset) ->
                if (currentIndex > previousIndex || 
                    (currentIndex == previousIndex && currentOffset > previousScrollOffset)) {
                    isBottomBarVisible = false
                } else if (currentIndex < previousIndex || 
                          (currentIndex == previousIndex && currentOffset < previousScrollOffset)) {
                    isBottomBarVisible = true
                }
                previousIndex = currentIndex
                previousScrollOffset = currentOffset
            }
        } else {
            snapshotFlow { 
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset 
            }.collect { (currentIndex, currentOffset) ->
                if (currentIndex > previousIndex || 
                    (currentIndex == previousIndex && currentOffset > previousScrollOffset)) {
                    isBottomBarVisible = false
                } else if (currentIndex < previousIndex || 
                          (currentIndex == previousIndex && currentOffset < previousScrollOffset)) {
                    isBottomBarVisible = true
                }
                previousIndex = currentIndex
                previousScrollOffset = currentOffset
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
            if (state.isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            } else if (state.items.isEmpty()) {
                EmptyFolderState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = paddingValues.calculateTopPadding())
                ) {
                    if (state.viewMode == ExplorerWorkspaceViewModel.ViewMode.LIST) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 12.dp,
                                bottom = if (isBottomBarVisible) 88.dp else 12.dp
                            )
                        ) {
                            items(state.items) { item ->
                                when (item) {
                                    is ExplorerItem.PathItem -> PathItemRow(
                                        item = item,
                                        isSelected = state.selectedItems.contains(item.lookup.path),
                                        onToggleSelection = { vm?.toggleItemSelection(item) },
                                        onClick = { 
                                            if (state.selectedItems.isNotEmpty()) {
                                                vm?.toggleItemSelection(item)
                                            } else {
                                                vm?.navigate(item)
                                            }
                                        },
                                        onLongClick = { vm?.toggleItemSelection(item) },
                                        showSelection = state.selectedItems.isNotEmpty()
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
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(
                                start = 2.dp,
                                end = 2.dp,
                                top = 2.dp,
                                bottom = if (isBottomBarVisible) 78.dp else 2.dp
                            )
                        ) {
                            items(state.items) { item ->
                                when (item) {
                                    is ExplorerItem.PathItem -> PathItemGrid(
                                        item = item,
                                        isSelected = state.selectedItems.contains(item.lookup.path),
                                        onToggleSelection = { vm?.toggleItemSelection(item) },
                                        onClick = { 
                                            if (state.selectedItems.isNotEmpty()) {
                                                vm?.toggleItemSelection(item)
                                            } else {
                                                vm?.navigate(item)
                                            }
                                        },
                                        onLongClick = { vm?.toggleItemSelection(item) },
                                        showSelection = state.selectedItems.isNotEmpty()
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
        
        AnimatedVisibility(
            visible = isBottomBarVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            Column {
                ExplorerInfoBar(
                    info = state.currentLocation?.info,
                    selectedCount = state.selectedItems.size,
                )
                if (state.availableActions.isNotEmpty()) {
                    ExplorerActionBar(
                        actions = state.availableActions,
                        onActionClick = { action -> vm?.executeAction(action) },
                    )
                }
            }
        }
        
        ExplorerDialogHost(
            dialogState = state.dialogState,
            vm = vm
        )
    }
}

@Preview2
@Composable
fun ExplorerWorkspacePagePreview() {
    val mockBreadcrumbs = listOf(
        ExplorerBreadcrumb(
            label = "Home".toCaString(),
            target = ExplorerNavigation.Target.Home
        ),
        ExplorerBreadcrumb(
            label = "Device".toCaString(),
            target = ExplorerNavigation.Target.Device
        ),
        ExplorerBreadcrumb(
            label = "storage".toCaString(),
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage"))
        ),
        ExplorerBreadcrumb(
            label = "emulated".toCaString(),
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage/emulated"))
        ),
        ExplorerBreadcrumb(
            label = "0".toCaString(),
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage/emulated/0"))
        )
    )
    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(
            path = RawPath.build("/storage/emulated/0"),
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
        breadcrumbs = mockBreadcrumbs,
        items = MockDataProvider.createAllFileTypes(),
        isLoading = false,
        selectedItems = emptySet()
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
        currentLocation = ExplorerLocation.Directory(RawPath.build("/storage/emulated/0")),
        breadcrumbs = emptyList(),
        items = emptyList(),
        isLoading = true,
        selectedItems = emptySet()
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
        currentLocation = ExplorerLocation.Directory(RawPath.build("/empty/folder")),
        breadcrumbs = emptyList(),
        items = emptyList(),
        isLoading = false,
        selectedItems = emptySet()
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
        currentLocation = ExplorerLocation.Directory(RawPath.build("/storage/emulated/0")),
        breadcrumbs = emptyList(),
        items = mockFileItems,
        isLoading = false,
        selectedItems = setOf(mockFileItems[0].lookup.path, mockFileItems[2].lookup.path)
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
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage/emulated/0/Pictures"))
        )
    )
    val mockState = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(RawPath.build("/storage/emulated/0/Pictures")),
        breadcrumbs = mockBreadcrumbs,
        items = MockDataProvider.createAllFileTypes(),
        isLoading = false,
        selectedItems = emptySet(),
        viewMode = ExplorerWorkspaceViewModel.ViewMode.GRID
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
        currentLocation = ExplorerLocation.Directory(RawPath.build("/storage/emulated/0/Downloads")),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = "Device".toCaString(),
                target = ExplorerNavigation.Target.Device
            ),
            ExplorerBreadcrumb(
                label = "Downloads".toCaString(),
                target = ExplorerNavigation.Target.Directory(RawPath.build("/storage/emulated/0/Downloads"))
            )
        ),
        items = mockFileItems,
        isLoading = false,
        selectedItems = setOf(mockFileItems[0].lookup.path, mockFileItems[2].lookup.path, mockFileItems[3].lookup.path),
        viewMode = ExplorerWorkspaceViewModel.ViewMode.GRID
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



