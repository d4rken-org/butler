package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.rows.FileItemRow
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                    onBreadcrumbClick = { target -> vm?.navigateToBreadcrumb(target) },
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
                            // Sort directories first, then files
                            val sortedItems = state.items.sortedWith(
                                compareBy<ExplorerPathItem> { !it.isDirectory }.thenBy { it.displayName }
                            )

                            items(sortedItems) { fileItem ->
                                FileItemRow(
                                    item = fileItem,
                                    isSelected = state.selectedItems.contains(fileItem.lookup.lookedUp),
                                    onToggleSelection = { vm?.toggleItemSelection(fileItem) },
                                    onClick = { vm?.navigate(fileItem) },
                                    showSelection = state.selectedItems.isNotEmpty()
                                )
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

@Preview2
@Composable
fun ExplorerWorkspacePagePreview() {
    val mockBreadcrumbs = listOf(
        ExplorerLocation.Breadcrumb(
            label = "Home".toCaString(),
            target = ExplorerLocation.Breadcrumb.Target.Home
        ),
        ExplorerLocation.Breadcrumb(
            label = "Device".toCaString(),
            target = ExplorerLocation.Breadcrumb.Target.Device
        ),
        ExplorerLocation.Breadcrumb(
            label = "storage".toCaString(),
            target = ExplorerLocation.Breadcrumb.Target.Directory(RawPath.build("/storage"))
        ),
        ExplorerLocation.Breadcrumb(
            label = "emulated".toCaString(),
            target = ExplorerLocation.Breadcrumb.Target.Directory(RawPath.build("/storage/emulated"))
        ),
        ExplorerLocation.Breadcrumb(
            label = "0".toCaString(),
            target = ExplorerLocation.Breadcrumb.Target.Directory(RawPath.build("/storage/emulated/0"))
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
        selectedItems = setOf(mockFileItems[0].lookup.lookedUp, mockFileItems[2].lookup.lookedUp)
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


