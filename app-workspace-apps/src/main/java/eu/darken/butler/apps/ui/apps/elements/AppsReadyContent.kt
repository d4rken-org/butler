package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.ui.apps.AppsPageAction
import eu.darken.butler.apps.ui.apps.AppsWorkspaceViewModel
import eu.darken.butler.apps.ui.apps.items.AppGridItem
import eu.darken.butler.apps.ui.apps.items.AppListItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import kotlin.math.roundToInt

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
    val pullToRefreshState = rememberPullToRefreshState()

    val listContentPadding = rememberFloatingBarContentPadding(
        topStackState = topBarStackState,
        bottomStackState = bottomBarStackState,
    )
    val gridContentPadding = rememberFloatingBarContentPadding(
        topStackState = topBarStackState,
        bottomStackState = bottomBarStackState,
        start = 8.dp,
        end = 8.dp,
    )

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = {
            // Ignore pulls during the initial load — that scan is already running.
            if (!state.isLoading) onPageAction(AppsPageAction.Apps.Refresh)
        },
        modifier = modifier,
        state = pullToRefreshState,
        indicator = {
            // Offset the indicator below the floating toolbar/chips (top of the list content).
            PullToRefreshDefaults.Indicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(x = 0, y = topBarStackState.contentPaddingPx.roundToInt()) },
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
                    contentPadding = listContentPadding,
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
                    contentPadding = gridContentPadding,
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
