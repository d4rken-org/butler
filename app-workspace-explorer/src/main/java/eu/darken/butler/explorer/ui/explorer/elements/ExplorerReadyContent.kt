package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.archive.ArchiveNotSeekableException
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.error.ErrorCard
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Explorer page's "ready" branch: pull-to-refresh list/grid content, navigation error card
 * and the bottom floating bar stack.
 */
@Composable
internal fun ExplorerReadyContent(
    modifier: Modifier = Modifier,
    state: ExplorerWorkspaceViewModel.State,
    vm: ExplorerWorkspaceViewModel?,
    listState: LazyListState,
    gridState: LazyGridState,
    topBarStackState: FloatingBarStackState,
    bottomBarStackState: FloatingBarStackState,
    operationsState: OperationsDisplayState,
    clipboardState: ClipboardDisplayState,
    showPullToRefreshIndicator: Boolean,
    pullToRefreshState: PullToRefreshState,
    onRefresh: () -> Unit,
    initialOperationsExpanded: Boolean,
    initialClipboardExpanded: Boolean,
    onShowOperationDetails: (Operation.Id) -> Unit,
) {
    val topContentPadding = topBarStackState.contentPaddingDp()

    // Focus state from ViewModel
    val contentFocusedItem = state.focusedItemIndex?.let { state.items?.getOrNull(it) }

    Box(modifier = modifier.fillMaxSize()) {
        // Main content with pull-to-refresh
        PullToRefreshBox(
            isRefreshing = showPullToRefreshIndicator,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = topContentPadding),
                    state = pullToRefreshState,
                    isRefreshing = showPullToRefreshIndicator,
                )
            },
        ) {
            when (state.viewStyle) {
                is ExplorerViewStyle.List -> ExplorerListContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(topBarStackState.nestedScrollConnection)
                        .nestedScroll(bottomBarStackState.nestedScrollConnection),
                    state = state,
                    vm = vm,
                    contentFocusedItem = contentFocusedItem,
                    listState = listState,
                    contentPadding = PaddingValues(
                        start = WorkspacePaddings.ContentHorizontal,
                        end = WorkspacePaddings.ContentHorizontal,
                        top = topContentPadding,
                        bottom = bottomBarStackState.contentPaddingDp(),
                    ),
                )

                is ExplorerViewStyle.Grid -> ExplorerGridContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(topBarStackState.nestedScrollConnection)
                        .nestedScroll(bottomBarStackState.nestedScrollConnection),
                    state = state,
                    vm = vm,
                    contentFocusedItem = contentFocusedItem,
                    gridState = gridState,
                    contentPadding = PaddingValues(
                        start = 2.dp,
                        end = 2.dp,
                        top = topContentPadding,
                        bottom = bottomBarStackState.contentPaddingDp(),
                    ),
                )
            }
        }

        // Error card (floating below top bar stack)
        state.error?.let { error ->
            if (error is ArchiveNotSeekableException) {
                val archiveBusy by (vm?.archiveActionBusy ?: remember { MutableStateFlow(false) }).collectAsState()
                ArchiveAccessErrorCard(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = topContentPadding)
                        .padding(horizontal = 16.dp),
                    archiveName = error.container.name,
                    busy = archiveBusy,
                    onExtract = { vm?.extractUnbrowsableArchive(error.container) },
                    onDownloadCopy = { vm?.downloadArchiveCopy(error.container) },
                    onRetry = { vm?.retryNavigation() },
                    onDismiss = { vm?.dismissNavigationError() },
                )
            } else {
                ErrorCard(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = topContentPadding)
                        .padding(horizontal = 16.dp),
                    title = stringResource(R.string.explorer_navigation_error_title),
                    error = error,
                    onShareError = { vm?.shareNavigationError() },
                    onRetry = { vm?.retryNavigation() },
                    onDismiss = { vm?.dismissNavigationError() },
                )
            }
        }

        // Bottom FloatingBarStack
        FloatingBarStack(
            state = bottomBarStackState,
            position = BarPosition.BOTTOM,
            modifier = Modifier.align(Alignment.BottomCenter),
            bars = {
                ExplorerBottomBars(
                    state = state,
                    vm = vm,
                    operationsState = operationsState,
                    clipboardState = clipboardState,
                    initialOperationsExpanded = initialOperationsExpanded,
                    initialClipboardExpanded = initialClipboardExpanded,
                    onShowOperationDetails = onShowOperationDetails,
                )
            },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerReadyContentPreview() {
    PreviewWrapper {
        ExplorerReadyContent(
            state = MockDataProvider.createReadyState(),
            vm = null,
            listState = rememberLazyListState(),
            gridState = rememberLazyGridState(),
            topBarStackState = rememberFloatingBarStackState(position = BarPosition.TOP),
            bottomBarStackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM),
            operationsState = OperationsDisplayState(),
            clipboardState = ClipboardDisplayState(),
            showPullToRefreshIndicator = false,
            pullToRefreshState = rememberPullToRefreshState(),
            onRefresh = {},
            initialOperationsExpanded = false,
            initialClipboardExpanded = false,
            onShowOperationDetails = {},
        )
    }
}
