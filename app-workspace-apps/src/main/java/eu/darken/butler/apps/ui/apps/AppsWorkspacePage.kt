package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun AppsWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: AppsWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: AppsWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    AppsWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        onPageAction = vm::onPageAction,
    )
}

@Composable
private fun AppsWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<AppsWorkspaceViewModel.State>,
    onPageAction: (AppsPageAction) -> Unit = {},
) {
    val mainStateRaw by stateSource.collectAsState(
        initial = AppsWorkspaceViewModel.State.Initializing
    )

    // Only render when Ready - WorkspaceMapper handles Init/Error overlays
    val readyState = mainStateRaw as? AppsWorkspaceViewModel.State.Ready ?: return

    val topBarStackState = rememberFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        includeSystemBarInset = design.paneEdges.touchesTop,
    )

    val bottomBarStackState = rememberFloatingBarStackState(
        position = BarPosition.BOTTOM,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        includeSystemBarInset = design.paneEdges.touchesBottom,
    )

    val density = LocalDensity.current
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp

    AppsReadyContent(
        state = readyState,
        topBarStackState = topBarStackState,
        bottomBarStackState = bottomBarStackState,
        navBarInset = navBarInset,
        onPageAction = onPageAction,
    )
}

@Preview2
@Composable
private fun AppsWorkspacePagePreview() {
    val mockApps = listOf(
        AppsMockDataProvider.Presets.chromeItem,
        AppsMockDataProvider.Presets.settingsItem,
        AppsMockDataProvider.Presets.notesItem,
        AppsMockDataProvider.Presets.systemUiItem,
        AppsMockDataProvider.Presets.disabledAppItem,
    )

    val mockState = AppsWorkspaceViewModel.State.Ready(
        apps = mockApps,
        availableActions = listOf(
            AppsActionBarItem.Refresh,
            AppsActionBarItem.Sort,
            AppsActionBarItem.Filter,
        ),
        isLoading = false,
    )

    PreviewWrapper {
        AppsWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(mockState),
        )
    }
}

@Preview2
@Composable
private fun AppsWorkspacePageInitializingPreview() {
    PreviewWrapper {
        AppsWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(AppsWorkspaceViewModel.State.Initializing),
        )
    }
}

@Preview2
@Composable
private fun AppsWorkspacePageErrorPreview() {
    PreviewWrapper {
        AppsWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(AppsWorkspaceViewModel.State.Error(RuntimeException("Failed to load apps"))),
        )
    }
}

@Preview2
@Composable
private fun AppsWorkspacePageEmptyPreview() {
    val mockState = AppsWorkspaceViewModel.State.Ready(
        apps = emptyList(),
        availableActions = listOf(
            AppsActionBarItem.Refresh,
            AppsActionBarItem.Sort,
            AppsActionBarItem.Filter,
        ),
        isLoading = false,
    )

    PreviewWrapper {
        AppsWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(mockState),
        )
    }
}

@Preview2
@Composable
private fun AppsWorkspacePageWithSelectionPreview() {
    val mockApps = listOf(
        AppsMockDataProvider.Presets.chromeItem,
        AppsMockDataProvider.Presets.settingsItem,
        AppsMockDataProvider.Presets.notesItem,
        AppsMockDataProvider.Presets.disabledAppItem,
    )

    val mockState = AppsWorkspaceViewModel.State.Ready(
        apps = mockApps,
        selectedAppIds = setOf("com.android.chrome", "com.example.notes"),
        availableActions = listOf(
            AppsActionBarItem.DeselectAll,
            AppsActionBarItem.SelectAll,
            AppsActionBarItem.Disable(mockApps.take(2)),
            AppsActionBarItem.Uninstall(mockApps.take(2)),
            AppsActionBarItem.ClearCache(mockApps.take(2)),
        ),
        isLoading = false,
    )

    PreviewWrapper {
        AppsWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(mockState),
        )
    }
}
