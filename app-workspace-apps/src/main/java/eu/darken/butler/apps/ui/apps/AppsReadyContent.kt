package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppsViewStyle
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogHost
import eu.darken.butler.apps.ui.apps.items.AppGridItem
import eu.darken.butler.apps.ui.apps.items.AppListItem
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp

@Composable
fun AppsReadyContent(
    modifier: Modifier = Modifier,
    state: AppsWorkspaceViewModel.State.Ready,
    topBarStackState: FloatingBarStackState,
    bottomBarStackState: FloatingBarStackState,
    navBarInset: Dp,
    onPageAction: (AppsPageAction) -> Unit,
) {
    val hasActions by remember(state.availableActions) {
        derivedStateOf { state.availableActions.isNotEmpty() }
    }

    val showInfoBar by remember(state.apps, state.selectionCount) {
        derivedStateOf { state.apps.isNotEmpty() || state.selectionCount > 0 }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { onPageAction(AppsPageAction.Apps.Refresh) },
        ) {
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

        // Info bar - vanishes on scroll (only in Ready state)
        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
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
                        onClearSelection = { onPageAction(AppsPageAction.Selection.Clear) },
                    )
                }
            },
            content = { _ -> },
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
                            onPageAction(AppsPageAction.ActionBarClick(action as AppsActionBarItem))
                        },
                    )
                }
            },
            content = { _ -> },
        )
    }

    // Dialog Host
    AppsDialogHost(
        dialogState = state.dialogState,
        onDismiss = { onPageAction(AppsPageAction.Dialog.Dismiss) },
        onAction = { onPageAction(AppsPageAction.ActionBarClick(it)) },
        onFilterApply = { onPageAction(AppsPageAction.Dialog.ApplyFilter(it)) },
        onSortApply = { onPageAction(AppsPageAction.Dialog.ApplySort(it)) },
        onConfirmEnable = { onPageAction(AppsPageAction.Dialog.ConfirmEnable(it)) },
        onConfirmDisable = { onPageAction(AppsPageAction.Dialog.ConfirmDisable(it)) },
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
