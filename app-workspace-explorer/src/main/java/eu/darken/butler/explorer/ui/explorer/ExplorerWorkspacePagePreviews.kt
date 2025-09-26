package eu.darken.butler.explorer.ui.explorer

import androidx.compose.runtime.Composable
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import kotlinx.coroutines.flow.flowOf

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
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
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
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
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
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
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
            selectedItems = setOf(mockFileItems[0].path.path, mockFileItems[2].path.path),
            selectableItems = setOf(mockFileItems[0].path.path, mockFileItems[2].path.path),
        ),
        availableActions = listOf(
            ExplorerAction.Common.Sort(),
            ExplorerAction.Common.Filter(isEnabled = false),
        ),
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
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
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
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
            selectedItems = setOf(mockFileItems[0].path.path, mockFileItems[2].path.path),
            selectableItems = setOf(
                mockFileItems[0].path.path,
                mockFileItems[2].path.path,
                mockFileItems[3].path.path
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
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
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
            selectedItems = setOf(mockFileItems[0].path.path, mockFileItems[2].path.path),
            selectableItems = setOf(mockFileItems[0].path.path, mockFileItems[2].path.path),
        ),
        isLoading = false,
    )

    PreviewWrapper {
        ExplorerWorkspacePage(
            mainStateSource = flowOf(mockState),
            clipboardStateSource = flowOf(mockClipboardEntries),
            operationsStateSource = flowOf(mockOperations),
            workspaceStateSource = flowOf(null),
            vm = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerWorkspacePageWithExpandedBarsPreview() {
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
            selectedItems = setOf(mockFileItems[0].path.path, mockFileItems[2].path.path),
            selectableItems = setOf(mockFileItems[0].path.path, mockFileItems[2].path.path),
        ),
        isLoading = false,
    )

    PreviewWrapper {
        ExplorerWorkspacePage(
            mainStateSource = flowOf(mockState),
            clipboardStateSource = flowOf(mockClipboardEntries),
            operationsStateSource = flowOf(mockOperations),
            workspaceStateSource = flowOf(WorkspaceButtonViewModel.State()),
            vm = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
            initialOperationsExpanded = true,
            initialClipboardExpanded = true,
        )
    }
}