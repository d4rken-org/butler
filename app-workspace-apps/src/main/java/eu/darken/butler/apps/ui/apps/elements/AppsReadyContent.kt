package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.ui.apps.AppsPageAction
import eu.darken.butler.apps.ui.apps.AppsWorkspaceViewModel
import eu.darken.butler.apps.ui.apps.items.AppGridItem
import eu.darken.butler.apps.ui.apps.items.AppListItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.dragselect.gridDragSelect
import eu.darken.butler.common.compose.dragselect.listDragSelect
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.common.WorkspacePullToRefreshBox
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState

/**
 * The Apps page's scrolling content: pull-to-refresh plus the list and grid branches.
 *
 * Content padding is supplied as a measure-phase [androidx.compose.foundation.layout.PaddingValues]
 * so the per-frame bar collapse animation doesn't invalidate composition.
 */
@Composable
internal fun AppsReadyContent(
    modifier: Modifier = Modifier,
    state: AppsWorkspaceViewModel.State.Ready,
    listState: LazyListState,
    gridState: LazyGridState,
    topBarStackState: FloatingBarStackState,
    bottomBarStackState: FloatingBarStackState,
    onPageAction: (AppsPageAction) -> Unit,
) {
    val listContentPadding = rememberFloatingBarContentPadding(
        topStackState = topBarStackState,
        bottomStackState = bottomBarStackState,
        start = WorkspacePaddings.ContentHorizontal,
        end = WorkspacePaddings.ContentHorizontal,
    )
    val gridContentPadding = rememberFloatingBarContentPadding(
        topStackState = topBarStackState,
        bottomStackState = bottomBarStackState,
        start = WorkspacePaddings.GridHorizontal,
        end = WorkspacePaddings.GridHorizontal,
    )

    WorkspacePullToRefreshBox(
        modifier = modifier,
        // Size measurement reuses the pull-to-refresh indicator instead of adding a second affordance.
        isRefreshing = state.isRefreshing || state.isResolvingSizes,
        onRefresh = {
            // Ignore pulls during the initial load — that scan is already running.
            if (!state.isLoading) onPageAction(AppsPageAction.Apps.Refresh)
        },
        enabled = !state.isMultiSelectMode,
        topBarStackState = topBarStackState,
    ) {
        when (state.viewStyle) {
            is AppsViewStyle.List -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(topBarStackState.nestedScrollConnection)
                        .nestedScroll(bottomBarStackState.nestedScrollConnection)
                        .listDragSelect(
                            state = listState,
                            orderedKeys = { state.apps.map { app -> app.pkg.installId } },
                            currentSelection = { state.selectedAppIds },
                            onSelectionChange = { onPageAction(AppsPageAction.Selection.SetSelection(it)) },
                        ),
                    contentPadding = listContentPadding,
                    verticalArrangement = Arrangement.spacedBy(WorkspacePaddings.ListGapDense),
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
                                    // The long press belongs to the drag selection, which owns the
                                    // whole gesture; kept non-null so releasing it can't fall
                                    // through to onClick and so the press still gives haptic feedback.
                                    onLongClick = {},
                                    showSelection = state.isMultiSelectMode,
                                )
                            }
                        }
                    }
                }
            }

            is AppsViewStyle.Grid -> {
                val gridSize = state.viewStyle.size
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
                        .nestedScroll(bottomBarStackState.nestedScrollConnection)
                        .gridDragSelect(
                            state = gridState,
                            orderedKeys = { state.apps.map { app -> app.pkg.installId } },
                            currentSelection = { state.selectedAppIds },
                            onSelectionChange = { onPageAction(AppsPageAction.Selection.SetSelection(it)) },
                            contentPadding = gridContentPadding,
                        ),
                    contentPadding = gridContentPadding,
                    horizontalArrangement = Arrangement.spacedBy(WorkspacePaddings.GridGutter),
                    verticalArrangement = Arrangement.spacedBy(WorkspacePaddings.GridGutter),
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
                                    // The long press belongs to the drag selection, which owns the
                                    // whole gesture; kept non-null so releasing it can't fall
                                    // through to onClick and so the press still gives haptic feedback.
                                    onLongClick = {},
                                    showSelection = state.isMultiSelectMode,
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsReadyContentPreview() {
    PreviewWrapper {
        AppsReadyContent(
            state = AppsWorkspaceViewModel.State.Ready(
                apps = listOf(
                    AppsMockDataProvider.Presets.chromeItem,
                    AppsMockDataProvider.Presets.settingsItem,
                    AppsMockDataProvider.Presets.notesItem,
                    AppsMockDataProvider.Presets.disabledAppItem,
                ),
                isLoading = false,
            ),
            listState = rememberLazyListState(),
            gridState = rememberLazyGridState(),
            topBarStackState = rememberFloatingBarStackState(position = BarPosition.TOP),
            bottomBarStackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM),
            onPageAction = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsReadyContentGridPreview() {
    PreviewWrapper {
        AppsReadyContent(
            state = AppsWorkspaceViewModel.State.Ready(
                apps = listOf(
                    AppsMockDataProvider.Presets.chromeItem,
                    AppsMockDataProvider.Presets.settingsItem,
                    AppsMockDataProvider.Presets.notesItem,
                    AppsMockDataProvider.Presets.disabledAppItem,
                ),
                viewStyle = AppsViewStyle.Grid(),
                isLoading = false,
            ),
            listState = rememberLazyListState(),
            gridState = rememberLazyGridState(),
            topBarStackState = rememberFloatingBarStackState(position = BarPosition.TOP),
            bottomBarStackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM),
            onPageAction = {},
        )
    }
}
