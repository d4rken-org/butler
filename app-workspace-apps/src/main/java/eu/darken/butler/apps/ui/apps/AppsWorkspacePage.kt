package eu.darken.butler.apps.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogHost
import eu.darken.butler.apps.ui.apps.elements.AppsActionBarItem
import eu.darken.butler.apps.ui.apps.elements.AppsEmptyContent
import eu.darken.butler.apps.ui.apps.elements.AppsInfoBar
import eu.darken.butler.apps.ui.apps.elements.AppsLoadingContent
import eu.darken.butler.apps.ui.apps.elements.AppsToolbarCard
import eu.darken.butler.apps.ui.apps.items.AppGridItem
import eu.darken.butler.apps.ui.apps.items.AppListItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyGridState
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyListState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

@Composable
fun AppsWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<AppsWorkspaceViewModel.State>,
    onPageAction: (AppsPageAction) -> Unit = {},
) {
    // StateFlow check: use current value as initial for single-frame renderers (screenshot tests, previews)
    val mainStateRaw by stateSource.collectAsState(
        initial = (stateSource as? StateFlow)?.value ?: AppsWorkspaceViewModel.State.Initializing
    )

    // Only render when Ready - WorkspaceMapper handles Init/Error overlays
    val state = mainStateRaw as? AppsWorkspaceViewModel.State.Ready ?: return

    BackHandler(enabled = state.isMultiSelectMode) {
        onPageAction(AppsPageAction.Selection.Clear)
    }

    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
        estimatedContentPadding = 156.dp,
    )

    val bottomBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.BOTTOM,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        design = design,
        estimatedContentPadding = 80.dp,
    )

    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom
    val statusBarInset = paneInsets.top

    // Offset the pull-to-refresh indicator below the floating toolbar/chips (top of the list content).
    val topContentPadding = topBarStackState.contentPaddingDp()
    val pullToRefreshState = rememberPullToRefreshState()

    val hasActions by remember(state.availableActions) {
        derivedStateOf { state.availableActions.isNotEmpty() }
    }

    val showInfoBar by remember(state.apps, state.selectionCount) {
        derivedStateOf { state.apps.isNotEmpty() || state.selectionCount > 0 }
    }

    // List and grid keep separate slots, their indices are not interchangeable
    val listState = rememberWorkspaceLazyListState(workspaceId, slot = "apps#list")
    val gridState = rememberWorkspaceLazyGridState(workspaceId, slot = "apps#grid")

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = {
                // Ignore pulls during the initial load — that scan is already running.
                if (!state.isLoading) onPageAction(AppsPageAction.Apps.Refresh)
            },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = topContentPadding),
                    state = pullToRefreshState,
                    isRefreshing = state.isRefreshing,
                )
            },
        ) {
            when (state.viewStyle) {
                is AppsViewStyle.List -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(topBarStackState.nestedScrollConnection)
                            .nestedScroll(bottomBarStackState.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = topContentPadding,
                            bottom = bottomBarStackState.contentPaddingDp(),
                        ),
                    ) {
                        when {
                            state.isLoading && state.apps.isEmpty() -> {
                                item {
                                    AppsLoadingContent()
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
                                        isSelected = appItem.pkg.installId in state.selectedAppIds,
                                        onClick = { onPageAction(AppsPageAction.Apps.Click(appItem)) },
                                        onLongClick = { onPageAction(AppsPageAction.Apps.LongClick(appItem)) },
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
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(topBarStackState.nestedScrollConnection)
                            .nestedScroll(bottomBarStackState.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = topContentPadding,
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
                                    AppsLoadingContent()
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
                                        isSelected = appItem.pkg.installId in state.selectedAppIds,
                                        onClick = { onPageAction(AppsPageAction.Apps.Click(appItem)) },
                                        onLongClick = { onPageAction(AppsPageAction.Apps.LongClick(appItem)) },
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
                // Toolbar card - collapses on scroll
                FloatingBar(
                    key = "toolbar",
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    AppsToolbarCard(
                        workspaceId = workspaceId,
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = { onPageAction(AppsPageAction.Search.UpdateQuery(it)) },
                        filterConfig = state.filterConfig,
                        onFilterAdd = { onPageAction(AppsPageAction.Filter.OpenDialog) },
                        onFilterRemove = { tag, isExcluded ->
                            onPageAction(AppsPageAction.Filter.RemoveTag(tag, isExcluded))
                        },
                        design = design,
                        collapsedFraction = collapsedFraction,
                    )
                }

                // Info bar - vanishes on scroll
                FloatingBar(
                    key = "infobar",
                    visible = showInfoBar,
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    AppsInfoBar(
                        userAppsCount = state.userAppsCount,
                        systemAppsCount = state.systemAppsCount,
                        selectedCount = state.selectionCount,
                        onClearSelection = { onPageAction(AppsPageAction.Selection.Clear) },
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
                    key = "actions",
                    visible = hasActions,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    revealOn = state.selectedAppIds,
                ) {
                    WorkspaceActionBar(
                        actions = state.availableActions,
                        onActionClick = { action ->
                            onPageAction(AppsPageAction.ActionBarClick(action as AppsActionBarItem))
                        },
                    )
                }
            },
        )
    }

    // Dialog Host
    AppsDialogHost(
        dialogState = state.dialogState,
        filterConfig = state.filterConfig,
        onDismiss = { onPageAction(AppsPageAction.Dialog.Dismiss) },
        onAction = { onPageAction(AppsPageAction.ActionBarClick(it)) },
        onFilterApply = { onPageAction(AppsPageAction.Dialog.ApplyFilter(it)) },
        onSortApply = { onPageAction(AppsPageAction.Dialog.ApplySort(it)) },
        onConfirmEnable = { onPageAction(AppsPageAction.Dialog.ConfirmEnable(it)) },
        onConfirmDisable = { onPageAction(AppsPageAction.Dialog.ConfirmDisable(it)) },
        onConfirmUninstall = { onPageAction(AppsPageAction.Dialog.ConfirmUninstall(it)) },
        onConfirmClearCache = { onPageAction(AppsPageAction.Dialog.ConfirmClearCache(it)) },
        onConfirmClearData = { onPageAction(AppsPageAction.Dialog.ConfirmClearData(it)) },
        topInset = statusBarInset,
        bottomInset = navBarInset,
    )
}


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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsWorkspacePageEmptyPreview() {
    val mockState = AppsWorkspaceViewModel.State.Ready(
        apps = emptyList(),
        availableActions = listOf(
            AppsActionBarItem.Refresh,
            AppsActionBarItem.Sort,
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
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
        selectedAppIds = setOf(
            AppsMockDataProvider.Presets.chromeItem.pkg.installId,
            AppsMockDataProvider.Presets.notesItem.pkg.installId,
        ),
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
