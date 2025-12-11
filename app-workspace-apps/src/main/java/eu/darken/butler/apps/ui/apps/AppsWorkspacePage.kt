package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogHost
import eu.darken.butler.apps.ui.apps.items.AppListItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.scroll.getCurrentHeightDp
import eu.darken.butler.workspace.ui.scroll.rememberBottomBarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.rememberTopToolbarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.setHeight
import eu.darken.butler.workspace.ui.scroll.setHeights
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
    ErrorEventHandler(vm, vm.navController)

    AppsWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        workspaceButtonStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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

    // Scroll behavior for toolbar and action bar
    val topToolbarScrollBehavior = rememberTopToolbarScrollBehavior()
    val bottomBarScrollBehavior = rememberBottomBarScrollBehavior()
    val density = LocalDensity.current
    var toolbarInfoBarHeightPx by remember { mutableStateOf(0) }
    val toolbarInfoBarHeightDp = with(density) { toolbarInfoBarHeightPx.toDp() }

    // Set the bottom bar height for scroll behavior
    bottomBarScrollBehavior.state.setHeight(64.dp)

    // Configure top toolbar scroll heights after measurement
    topToolbarScrollBehavior.state.setHeights(
        expandedHeightDp = toolbarInfoBarHeightDp,
        collapsedHeightDp = 0.dp
    )

    // Auto-show action bar when entering selection mode
    LaunchedEffect(hasActions) {
        if (hasActions) {
            bottomBarScrollBehavior.state.animateToExpanded()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { vm?.onRefresh() },
        ) {
            // Scrollable content layer - with padding for pinned toolbar/infobar
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(topToolbarScrollBehavior.nestedScrollConnection)
                    .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = topToolbarScrollBehavior.state.getCurrentHeightDp(),
                    bottom = if (hasActions) 72.dp else 8.dp,
                ),
            ) {
                when {
                    state.isLoading && state.apps.isEmpty() -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 100.dp, bottom = 32.dp, start = 32.dp, end = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.apps_empty_loading))
                            }
                        }
                    }

                    state.apps.isEmpty() -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 100.dp, bottom = 32.dp, start = 32.dp, end = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(stringResource(R.string.apps_empty_no_apps))
                                Text(stringResource(R.string.apps_empty_no_apps_desc))
                            }
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

        // Pinned toolbar and info bar at top
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    translationY = topToolbarScrollBehavior.state.heightOffset
                    alpha = 1f - topToolbarScrollBehavior.state.collapsedFraction
                }
                .onGloballyPositioned { layoutCoordinates ->
                    toolbarInfoBarHeightPx = layoutCoordinates.size.height
                }
        ) {
            AppsToolbarCard(
                workspaceId = workspaceId,
                searchQuery = state.searchQuery,
                onSearchQueryChange = { vm?.onSearchQueryChanged(it) },
                design = design,
                workspaceButtonState = workspaceButtonState,
                workspaceActionHandler = workspaceActionHandler,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            AppsInfoBar(
                userAppsCount = state.userAppsCount,
                systemAppsCount = state.systemAppsCount,
                selectedCount = state.selectionCount,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
            )
        }

        // Floating Bottom ActionBar - Selection mode
        if (hasActions) {
            WorkspaceActionBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .graphicsLayer {
                        // Immediate snap behavior: fully visible or fully hidden
                        alpha = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 0f else 1f
                        translationY = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 64.dp.toPx() else 0f
                    },
                actions = state.availableActions,
                onActionClick = { action ->
                    when (val appsAction = action as AppsAction) {
                        is AppsAction.DeselectAll -> vm?.onClearSelection()
                        else -> vm?.onAction(appsAction)
                    }
                },
            )
        }
    }

    // Dialog Host
    AppsDialogHost(
        dialogState = state.dialogState,
        onDismiss = { vm?.dismissDialog() },
        onAction = { action -> vm?.onAction(action) },
        onFilterApply = { filter -> vm?.onFilterChanged(filter) },
        onSortApply = { sortSettings -> vm?.onSortSettingsChanged(sortSettings) },
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
