package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.archive.ArchiveNotSeekableException
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.BrowsingAbortedException
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.dnd.ExplorerDropState
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.dnd.dropTargetHighlight
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.common.WorkspacePullToRefreshBox
import eu.darken.butler.workspace.ui.error.ErrorCard
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/**
 * The Explorer page's "ready" branch: pull-to-refresh list/grid content, navigation error card
 * and the bottom floating bar stack.
 */
@Composable
internal fun ExplorerReadyContent(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    state: ExplorerWorkspaceViewModel.State,
    vm: ExplorerWorkspaceViewModel?,
    listState: LazyListState,
    gridState: LazyGridState,
    topBarStackState: FloatingBarStackState,
    bottomBarStackState: FloatingBarStackState,
    operationsState: OperationsDisplayState,
    clipboardState: ClipboardDisplayState,
    isRefreshing: Boolean,
    pullToRefreshState: PullToRefreshState,
    onRefresh: () -> Unit,
    initialOperationsExpanded: Boolean,
    initialClipboardExpanded: Boolean,
    onShowOperationDetails: (Operation.Id) -> Unit,
    dragPayloadFactory: ((ExplorerItem) -> WorkspaceDragPayload?)? = null,
    dropState: ExplorerDropState? = null,
) {
    // Focus state from ViewModel
    val contentFocusedItem = state.focusedItemIndex?.let { state.items?.getOrNull(it) }

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

    // The page owns the drop target; this content only reports the geometry it hit-tests against.
    dropState?.topBarPaddingPx = topBarStackState.contentPaddingPx
    dropState?.bottomBarPaddingPx = bottomBarStackState.contentPaddingPx

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { dropState?.contentBounds = it.boundsInRoot() }
            .dropTargetHighlight(dropState?.isPaneHovered == true),
    ) {
        // Main content with pull-to-refresh
        WorkspacePullToRefreshBox(
            modifier = Modifier.fillMaxSize(),
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            enabled = !state.selectionState.isSelectionMode,
            topBarStackState = topBarStackState,
            state = pullToRefreshState,
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
                    contentPadding = listContentPadding,
                    dragPayloadFactory = dragPayloadFactory,
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
                    contentPadding = gridContentPadding,
                    dragPayloadFactory = dragPayloadFactory,
                )
            }
        }

        // Error card (floating below top bar stack). A cancelled load is answered by the aborted
        // dialog in the overlay slot instead, and a vanished target by the content-level
        // PathNotFoundState, so neither must raise a card of its own.
        state.error?.takeIf { it !is BrowsingAbortedException && it !is PathNotFoundException }?.let { error ->
            if (error is ArchiveNotSeekableException) {
                val archiveBusy by (vm?.archiveActionBusy ?: remember { MutableStateFlow(false) }).collectAsState()
                ArchiveAccessErrorCard(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(x = 0, y = topBarStackState.contentPaddingPx.roundToInt()) }
                        .padding(horizontal = WorkspacePaddings.BarHorizontal),
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
                        .offset { IntOffset(x = 0, y = topBarStackState.contentPaddingPx.roundToInt()) }
                        .padding(horizontal = WorkspacePaddings.BarHorizontal),
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
            workspaceId = Workspace.Id(),
            state = MockDataProvider.createReadyState(),
            vm = null,
            listState = rememberLazyListState(),
            gridState = rememberLazyGridState(),
            topBarStackState = rememberFloatingBarStackState(position = BarPosition.TOP),
            bottomBarStackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM),
            operationsState = OperationsDisplayState(),
            clipboardState = ClipboardDisplayState(),
            isRefreshing = false,
            pullToRefreshState = rememberPullToRefreshState(),
            onRefresh = {},
            initialOperationsExpanded = false,
            initialClipboardExpanded = false,
            onShowOperationDetails = {},
        )
    }
}
