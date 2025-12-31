package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.states.WorkspaceErrorContent
import eu.darken.butler.workspace.ui.states.WorkspaceInitializingContent
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
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm, workspaceButtonVm)

    AppsWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        workspaceButtonStateSource = workspaceButtonVm.state,
        onPageAction = vm::onPageAction,
        workspaceActionHandler = workspaceButtonVm,
    )
}

@Composable
private fun AppsWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<AppsWorkspaceViewModel.State>,
    workspaceButtonStateSource: Flow<WorkspaceButtonViewModel.State?>,
    onPageAction: (AppsPageAction) -> Unit = {},
    workspaceActionHandler: WorkspaceActionHandler? = null,
) {
    val mainStateRaw by stateSource.collectAsState(
        initial = AppsWorkspaceViewModel.State.Initializing
    )
    val workspaceButtonState by workspaceButtonStateSource.collectAsState(null)

    // Use defaults when not ready - enables clean non-null access for toolbar
    val readyState = (mainStateRaw as? AppsWorkspaceViewModel.State.Ready)
        ?: AppsWorkspaceViewModel.State.Ready()

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

    // Calculate top content padding for initializing/error states
    val topContentPadding = topBarStackState.contentPaddingDp()

    Box(modifier = Modifier.fillMaxSize()) {
        // Content area - switches based on state
        when (val currentState = mainStateRaw) {
            is AppsWorkspaceViewModel.State.Error -> {
                WorkspaceErrorContent(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topContentPadding),
                    error = currentState.error,
                    onShareError = { onPageAction(AppsPageAction.Workspace.ShareError) },
                    onCloseWorkspace = { onPageAction(AppsPageAction.Workspace.Close) },
                )
            }

            is AppsWorkspaceViewModel.State.Initializing -> {
                WorkspaceInitializingContent(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topContentPadding),
                )
            }

            is AppsWorkspaceViewModel.State.Ready -> {
                AppsReadyContent(
                    state = currentState,
                    topBarStackState = topBarStackState,
                    bottomBarStackState = bottomBarStackState,
                    navBarInset = navBarInset,
                    onPageAction = onPageAction,
                )
            }
        }

        // Toolbar - always visible on top
        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                FloatingBar(
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    AppsToolbarCard(
                        workspaceId = workspaceId,
                        searchQuery = readyState.searchQuery,
                        onSearchQueryChange = { onPageAction(AppsPageAction.Search.UpdateQuery(it)) },
                        design = design,
                        workspaceButtonState = workspaceButtonState,
                        workspaceActionHandler = workspaceActionHandler,
                        collapsedFraction = collapsedFraction,
                    )
                }
            },
        )
    }
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
            workspaceButtonStateSource = flowOf(null),
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
            workspaceButtonStateSource = flowOf(null),
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
            workspaceButtonStateSource = flowOf(null),
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
            workspaceButtonStateSource = flowOf(null),
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
            workspaceButtonStateSource = flowOf(null),
        )
    }
}
