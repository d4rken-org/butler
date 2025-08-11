package eu.darken.butler.explorer.ui.explorer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.CreateNewFolder
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import eu.darken.butler.explorer.ui.explorer.rows.PathItemRow
import eu.darken.butler.explorer.ui.explorer.rows.ShortcutRow
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            ExplorerBottomBar(
                isSelectionMode = state.selectedItems.isNotEmpty(),
                selectedCount = state.selectedItems.size,
                onCreateFolderClick = { vm?.createNewFolder() },
                onCopyClick = { vm?.copySelectedItems() },
                onCutClick = { vm?.cutSelectedItems() },
                onDeleteClick = { vm?.deleteSelectedItems() },
                onShareClick = { vm?.shareSelectedItems() },
                onSortClick = { vm?.showSortOptions() },
                onFilterClick = { vm?.showFilterOptions() },
                onMoreClick = { vm?.showMoreOptions() },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Path display with spacer for floating button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                BreadcrumbBar(
                    breadcrumbs = state.breadcrumbs,
                    onBreadcrumbClick = { target -> vm?.navigate(target) },
                    onNavigateToPath = { path -> vm?.navigateToPathString(path) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                if (design.isSingle) {
                    WorkspaceButton(
                        modifier = Modifier,
                        state = workspaceButtonState,
                        onAction = onWorkspaceAction,
                        onNavToWorkspaceManager = onNavToWorkspaceManager,
                    )
                }
            }

            // File list
            if (state.isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
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
            } else {
                if (state.items.isEmpty()) {
                    EmptyFolderState(
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            items(state.items) { item ->
                                when (item) {
                                    is ExplorerItem.PathItem -> PathItemRow(
                                        item = item,
                                        isSelected = state.selectedItems.contains(item.lookup.path),
                                        onToggleSelection = { vm?.toggleItemSelection(item) },
                                        onClick = { 
                                            if (state.selectedItems.isNotEmpty()) {
                                                // In selection mode, clicks toggle selection
                                                vm?.toggleItemSelection(item)
                                            } else {
                                                // Not in selection mode, navigate
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

                        // Extended data loading indicator
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
    }
}

@Composable
fun ExplorerBottomBar(
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean,
    selectedCount: Int,
    onCreateFolderClick: () -> Unit,
    onCopyClick: () -> Unit,
    onCutClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
    onSortClick: () -> Unit,
    onFilterClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = true, // Always show the bottom bar
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        BottomAppBar(
            modifier = modifier.height(56.dp), // Compact height
            windowInsets = WindowInsets(0, 0, 0, 0),
            tonalElevation = 0.dp, // Remove elevation for cleaner look
            actions = {
                if (isSelectionMode) {
                    // Selection mode actions
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$selectedCount selected",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    IconButton(onClick = onCopyClick) {
                        Icon(
                            imageVector = Icons.TwoTone.ContentCopy,
                            contentDescription = "Copy",
                        )
                    }
                    IconButton(onClick = onCutClick) {
                        Icon(
                            imageVector = Icons.TwoTone.ContentCut,
                            contentDescription = "Cut",
                        )
                    }
                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            imageVector = Icons.TwoTone.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(onClick = onShareClick) {
                        Icon(
                            imageVector = Icons.TwoTone.Share,
                            contentDescription = "Share",
                        )
                    }
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            imageVector = Icons.TwoTone.MoreVert,
                            contentDescription = "More options",
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onCreateFolderClick) {
                            Icon(
                                imageVector = Icons.TwoTone.CreateNewFolder,
                                contentDescription = "Create new folder",
                            )
                        }
                        IconButton(onClick = onSortClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.TwoTone.Sort,
                                contentDescription = "Sort",
                            )
                        }
                        IconButton(onClick = onFilterClick) {
                            Icon(
                                imageVector = Icons.TwoTone.FilterList,
                                contentDescription = "Filter",
                            )
                        }
                        IconButton(onClick = onMoreClick) {
                            Icon(
                                imageVector = Icons.TwoTone.MoreVert,
                                contentDescription = "More options",
                            )
                        }
                    }
                }
            },
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
        currentLocation = ExplorerLocation.Directory(RawPath.build("/storage/emulated/0")),
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
fun ExplorerBottomBarNormalModePreview() {
    PreviewWrapper {
        ExplorerBottomBar(
            isSelectionMode = false,
            selectedCount = 0,
            onCreateFolderClick = {},
            onCopyClick = {},
            onCutClick = {},
            onDeleteClick = {},
            onShareClick = {},
            onSortClick = {},
            onFilterClick = {},
            onMoreClick = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerBottomBarSelectionModePreview() {
    PreviewWrapper {
        ExplorerBottomBar(
            isSelectionMode = true,
            selectedCount = 3,
            onCreateFolderClick = {},
            onCopyClick = {},
            onCutClick = {},
            onDeleteClick = {},
            onShareClick = {},
            onSortClick = {},
            onFilterClick = {},
            onMoreClick = {},
        )
    }
}


