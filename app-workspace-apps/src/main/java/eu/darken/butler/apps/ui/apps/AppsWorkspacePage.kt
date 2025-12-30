package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppsViewStyle
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogHost
import eu.darken.butler.apps.ui.apps.items.AppGridItem
import eu.darken.butler.apps.ui.apps.items.AppListItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
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
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm, workspaceButtonVm)

    AppsWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        workspaceButtonStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
    )
}

@Composable
private fun AppsWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<AppsWorkspaceViewModel.State>,
    workspaceButtonStateSource: Flow<WorkspaceButtonViewModel.State?>,
    vm: AppsWorkspaceViewModel? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
) {
    val state by stateSource.collectAsState(
        initial = AppsWorkspaceViewModel.State()
    )
    val workspaceButtonState by workspaceButtonStateSource.collectAsState(null)

    val hasActions by remember {
        derivedStateOf { state.availableActions.isNotEmpty() }
    }

    val showInfoBar by remember {
        derivedStateOf { state.apps.isNotEmpty() || state.selectionCount > 0 }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { vm?.onRefresh() },
        ) {
            // Scrollable content layer - with padding for pinned toolbar/infobar
            when (state.viewStyle) {
                is AppsViewStyle.List -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(topBarStackState.nestedScrollConnection)
                            .nestedScroll(bottomBarStackState.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = topBarStackState.contentPaddingDp(),
                            bottom = bottomBarStackState.contentPaddingDp(),
                        ),
                    ) {
                        when {
                            state.isLoading && state.apps.isEmpty() -> {
                                item {
                                    AppsEmptyLoadingContent()
                                }
                            }

                            state.apps.isEmpty() -> {
                                item {
                                    AppsEmptyContent()
                                }
                            }

                            else -> {
                                items(
                                    items = state.apps,
                                    key = { it.pkg.installId }
                                ) { appItem ->
                                    AppListItem(
                                        item = appItem,
                                        isSelected = appItem.packageName in state.selectedAppIds,
                                        onClick = {
                                            if (state.isMultiSelectMode) {
                                                vm?.onAppLongClick(appItem)
                                            } else {
                                                vm?.showAppDetails(appItem)
                                            }
                                        },
                                        onLongClick = { vm?.onAppLongClick(appItem) },
                                        showSelection = state.isMultiSelectMode,
                                    )
                                }
                            }
                        }
                    }
                }

                is AppsViewStyle.Grid -> {
                    val gridSize = (state.viewStyle as AppsViewStyle.Grid).size
                    val minSize = when (gridSize) {
                        AppsViewStyle.Grid.GridSize.SMALL -> 90.dp
                        AppsViewStyle.Grid.GridSize.MEDIUM -> 120.dp
                        AppsViewStyle.Grid.GridSize.LARGE -> 160.dp
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = minSize),
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(topBarStackState.nestedScrollConnection)
                            .nestedScroll(bottomBarStackState.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = topBarStackState.contentPaddingDp(),
                            bottom = bottomBarStackState.contentPaddingDp(),
                            start = 8.dp,
                            end = 8.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when {
                            state.isLoading && state.apps.isEmpty() -> {
                                item {
                                    AppsEmptyLoadingContent()
                                }
                            }

                            state.apps.isEmpty() -> {
                                item {
                                    AppsEmptyContent()
                                }
                            }

                            else -> {
                                items(
                                    items = state.apps,
                                    key = { it.pkg.installId }
                                ) { appItem ->
                                    AppGridItem(
                                        item = appItem,
                                        isSelected = appItem.packageName in state.selectedAppIds,
                                        onClick = {
                                            if (state.isMultiSelectMode) {
                                                vm?.onAppLongClick(appItem)
                                            } else {
                                                vm?.showAppDetails(appItem)
                                            }
                                        },
                                        onLongClick = { vm?.onAppLongClick(appItem) },
                                        showSelection = state.isMultiSelectMode,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top floating bars
        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                // Toolbar - collapses on scroll
                FloatingBar(
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    AppsToolbarCard(
                        workspaceId = workspaceId,
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = { vm?.onSearchQueryChanged(it) },
                        design = design,
                        workspaceButtonState = workspaceButtonState,
                        workspaceActionHandler = workspaceActionHandler,
                        collapsedFraction = collapsedFraction,
                    )
                }

                // Info bar - vanishes on scroll
                FloatingBar(
                    visible = showInfoBar,
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    AppsInfoBar(
                        userAppsCount = state.userAppsCount,
                        systemAppsCount = state.systemAppsCount,
                        selectedCount = state.selectionCount,
                        onClearSelection = { vm?.onClearSelection() },
                    )
                }
            },
        )

        // Bottom floating bars
        FloatingBarStack(
            state = bottomBarStackState,
            position = BarPosition.BOTTOM,
            modifier = Modifier.align(Alignment.BottomCenter),
            bars = {
                FloatingBar(
                    visible = hasActions,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    WorkspaceActionBar(
                        actions = state.availableActions,
                        onActionClick = { action ->
                            when (val appsAction = action as AppsAction) {
                                is AppsAction.DeselectAll -> vm?.onClearSelection()
                                else -> vm?.onAction(appsAction)
                            }
                        },
                    )
                }
            },
        )
    }

    // Dialog Host
    AppsDialogHost(
        dialogState = state.dialogState,
        onDismiss = { vm?.dismissDialog() },
        onAction = { action -> vm?.onAction(action) },
        onFilterApply = { filter -> vm?.onFilterChanged(filter) },
        onSortApply = { sortSettings -> vm?.onSortSettingsChanged(sortSettings) },
        onConfirmEnable = { apps -> vm?.performEnableApps(apps) },
        onConfirmDisable = { apps -> vm?.performDisableApps(apps) },
        bottomInset = navBarInset,
    )
}

@Composable
private fun AppsEmptyLoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 100.dp, bottom = 32.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.apps_empty_loading))
    }
}

@Composable
private fun AppsEmptyContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 100.dp, bottom = 32.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.apps_empty_no_apps))
        Text(stringResource(R.string.apps_empty_no_apps_desc))
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

    val mockState = AppsWorkspaceViewModel.State(
        appsState = AppsMockDataProvider.createMockAppsState(apps = mockApps),
        availableActions = listOf(
            AppsAction.Refresh,
            AppsAction.Sort,
            AppsAction.Filter,
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
private fun AppsWorkspacePageLoadingPreview() {
    val mockState = AppsWorkspaceViewModel.State(
        isLoading = true,
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
private fun AppsWorkspacePageEmptyPreview() {
    val mockState = AppsWorkspaceViewModel.State(
        appsState = AppsMockDataProvider.createMockAppsState(apps = emptyList()),
        availableActions = listOf(
            AppsAction.Refresh,
            AppsAction.Sort,
            AppsAction.Filter,
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

    val mockState = AppsWorkspaceViewModel.State(
        appsState = AppsMockDataProvider.createMockAppsState(
            apps = mockApps,
            selectedIds = setOf("com.android.chrome", "com.example.notes"),
        ),
        availableActions = listOf(
            AppsAction.DeselectAll,
            AppsAction.SelectAll,
            AppsAction.Disable(mockApps.take(2)),
            AppsAction.Uninstall(mockApps.take(2)),
            AppsAction.ClearCache(mockApps.take(2)),
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
