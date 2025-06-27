package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.WorkspaceButtonSpacer
import eu.darken.butler.explorer.ui.explorer.rows.FileItemRow
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import kotlinx.coroutines.flow.flowOf

@Composable
fun ExplorerWorkspacePageHost(
    id: Workspace.Id,
    vm: ExplorerWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: ExplorerWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }
    state?.let { state ->
        ExplorerWorkspacePage(
            state = state,
            vm = vm
        )
    }
}

@Composable
fun ExplorerWorkspacePage(
    state: ExplorerWorkspaceViewModel.State,
    vm: ExplorerWorkspaceViewModel? = null
) {
    val fileItems by state.fileItemsFlow.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        // Path display with spacer for floating button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            PathCrumbBar(
                currentPath = state.currentPath.path,
                onPathChanged = { newPath ->
                    vm?.navigateToPath(RawPath.build(newPath))
                },
                onNavigateToPath = { targetPath ->
                    vm?.navigateToPath(RawPath.build(targetPath))
                },
                onNavigateToHome = {
                    vm?.navigateToPath(RawPath.build("/"))
                },
                onValidationError = { error ->
                    log("ExplorerWorkspacePage") { "Path validation error: $error" }
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp)
            )
            
            WorkspaceButtonSpacer()
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
            if (fileItems.isEmpty()) {
                EmptyFolderState(
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    // Sort directories first, then files
                    val sortedItems = fileItems.sortedWith(
                        compareBy<FileItem> { !it.isDirectory }.thenBy { it.displayName }
                    )

                    items(sortedItems) { fileItem ->
                        FileItemRow(
                            item = fileItem,
                            isSelected = state.selectedItems.contains(fileItem.lookup.path),
                            onToggleSelection = {
                                vm?.toggleItemSelection(fileItem.lookup.path)
                            },
                            onClick = {
                                if (fileItem.isDirectory) {
                                    vm?.navigateToPath(fileItem.lookup.lookedUp)
                                }
                            },
                            showSelection = state.selectedItems.isNotEmpty()
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ExplorerWorkspacePagePreview() {
    val mockState = ExplorerWorkspaceViewModel.State(
        id = Workspace.Id(),
        currentPath = RawPath.build("/storage/emulated/0"),
        fileItemsFlow = flowOf(MockDataProvider.createAllFileTypes()),
        isLoading = false,
        selectedItems = emptySet()
    )
    
    ExplorerWorkspacePage(
        state = mockState,
        vm = null
    )
}

@Preview(showBackground = true)
@Composable 
fun ExplorerWorkspacePageLoadingPreview() {
    val mockState = ExplorerWorkspaceViewModel.State(
        id = Workspace.Id(),
        currentPath = RawPath.build("/storage/emulated/0"),
        fileItemsFlow = flowOf(emptyList()),
        isLoading = true,
        selectedItems = emptySet()
    )
    
    ExplorerWorkspacePage(
        state = mockState,
        vm = null
    )
}

@Preview(showBackground = true)
@Composable
fun ExplorerWorkspacePageEmptyPreview() {
    val mockState = ExplorerWorkspaceViewModel.State(
        id = Workspace.Id(),
        currentPath = RawPath.build("/empty/folder"),
        fileItemsFlow = flowOf(emptyList()),
        isLoading = false,
        selectedItems = emptySet()
    )
    
    ExplorerWorkspacePage(
        state = mockState,
        vm = null
    )
}

@Preview(showBackground = true)
@Composable
fun ExplorerWorkspacePageWithSelectionPreview() {
    val mockFileItems = MockDataProvider.createAllFileTypes()
    val mockState = ExplorerWorkspaceViewModel.State(
        id = Workspace.Id(),
        currentPath = RawPath.build("/storage/emulated/0"),
        fileItemsFlow = flowOf(mockFileItems),
        isLoading = false,
        selectedItems = setOf(mockFileItems[0].lookup.path, mockFileItems[2].lookup.path)
    )
    
    ExplorerWorkspacePage(
        state = mockState,
        vm = null
    )
}


