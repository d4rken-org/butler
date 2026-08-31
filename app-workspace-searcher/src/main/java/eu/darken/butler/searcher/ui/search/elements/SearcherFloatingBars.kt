package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.ui.search.SearcherBarKeys
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.util.SearchListItem
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.searcher.ui.search.util.toPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.clipboard.bar.WorkspaceClipboardFloatingBar
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarScope
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.bar.WorkspaceOperationsFloatingBar

/**
 * The Searcher's top floating bars: toolbar, search progress card and info bar.
 *
 * Must stay a [FloatingBarScope] extension with the nested [FloatingBarScope.FloatingBar] calls
 * intact — `collapsedFraction` resolves from the inner bar's `FloatingBarContentScope` receiver.
 */
@Composable
internal fun FloatingBarScope.SearcherTopBars(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    state: SearcherWorkspaceViewModel.State.Ready,
    onPageAction: (SearcherPageAction) -> Unit,
) {
    val showProgressCard = state.workspaceState.targetProgress.isNotEmpty() &&
        state.workspaceState.searchStatus != SearcherWorkspace.State.SearchStatus.IDLE

    val showInfoBar = state.selectionState.selectionCount > 0 || state.hasResults

    val foldersCount = remember(state) {
        state.listItems.count {
            it is SearchListItem.Result && it.searchItem is SearchItem.Directory
        }
    }

    val filesCount = remember(state) {
        state.listItems.count {
            it is SearchListItem.Result && it.searchItem is SearchItem.File
        }
    }

    val totalSize = remember(state) {
        state.listItems
            .filterIsInstance<SearchListItem.Result>()
            .sumOf { it.searchItem.size ?: 0L }
    }

    val selectedSize = remember(state) {
        state.selectionState.selectedResults
            .sumOf { it.size ?: 0L }
    }

    // Toolbar - closest to top edge, collapses on scroll
    FloatingBar(
        key = SearcherBarKeys.TOOLBAR,
        visible = true,
        scrollBehavior = BarScrollBehavior.CollapseOnScroll,
        animation = BarAnimation.Slide(),
    ) {
        SearchToolbarCard(
            workspaceId = workspaceId,
            state = state,
            design = design,
            collapsedFraction = collapsedFraction,
            onAction = onPageAction,
        )
    }

    // Progress card - vanishes on scroll
    FloatingBar(
        key = SearcherBarKeys.PROGRESS,
        visible = showProgressCard,
        scrollBehavior = BarScrollBehavior.VanishOnScroll,
        animation = BarAnimation.Slide(),
    ) {
        SearchProgressCard(
            targetProgress = state.workspaceState.targetProgress,
            overallProgress = state.workspaceState.progress,
            searchStatus = state.workspaceState.searchStatus,
            resultCount = state.workspaceState.results.size,
            limitReached = state.workspaceState.limitReached,
            onAccessErrorsClick = { onPageAction(SearcherPageAction.Overlays.ShowAccessErrors) },
            onCancel = { onPageAction(SearcherPageAction.Search.Cancel) },
            onClear = { onPageAction(SearcherPageAction.Search.ClearResults) },
            onErrorClick = { path, exception ->
                onPageAction(SearcherPageAction.Overlays.ShowTargetError(path, exception))
            },
        )
    }

    // Info bar - static (stays visible when results or selection)
    FloatingBar(
        key = SearcherBarKeys.INFOBAR,
        visible = showInfoBar,
        scrollBehavior = BarScrollBehavior.Static,
        animation = BarAnimation.Slide(),
    ) {
        SearcherInfoBar(
            foldersCount = foldersCount,
            filesCount = filesCount,
            totalSize = totalSize,
            selectedCount = state.selectionState.selectionCount,
            selectedSize = selectedSize,
            onSelectAllFolders = {
                onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.SelectAllFolders))
            },
            onSelectAllFiles = {
                onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.SelectAllFiles))
            },
            onSelectAll = {
                onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.SelectAll))
            },
            onClearSelection = { onPageAction(SearcherPageAction.Results.ExitSelectionMode) },
        )
    }
}

/**
 * The Searcher's bottom floating bars: operations, clipboard and actions.
 */
@Composable
internal fun FloatingBarScope.SearcherBottomBars(
    state: SearcherWorkspaceViewModel.State.Ready,
    operationsState: OperationsDisplayState,
    clipboardState: ClipboardDisplayState,
    onPageAction: (SearcherPageAction) -> Unit,
) {
    val showingHistory = !state.hasResults && state.searchHistory.isNotEmpty()
    val hasActions = state.selectionState.selectedResultIds.isNotEmpty() ||
        (!showingHistory && state.listItems.isNotEmpty())

    // Operations bar - furthest from bottom edge
    WorkspaceOperationsFloatingBar(
        key = SearcherBarKeys.OPERATIONS,
        operations = operationsState.operations,
        onAction = { onPageAction(it.toPageAction()) },
    )

    // Clipboard bar - middle
    WorkspaceClipboardFloatingBar(
        key = SearcherBarKeys.CLIPBOARD,
        workspaceType = Workspace.Type.SEARCHER,
        clipboardEntries = clipboardState.entries,
        onAction = { onPageAction(it.toPageAction()) },
    )

    // Action bar - closest to bottom edge, hides on scroll
    FloatingBar(
        key = SearcherBarKeys.ACTIONS,
        visible = hasActions,
        scrollBehavior = BarScrollBehavior.HideOnScroll,
        animation = BarAnimation.Slide(),
        revealOn = state.selectionState.selectedResultIds,
    ) {
        WorkspaceActionBar(
            actions = state.availableActions,
            onActionClick = { action ->
                when (action) {
                    is SearcherActionBarItem.DeselectAll -> onPageAction(SearcherPageAction.Results.ExitSelectionMode)
                    else -> onPageAction(SearcherPageAction.WorkspaceAction(action))
                }
            },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherTopBarsPreview() {
    PreviewWrapper {
        val stackState = rememberFloatingBarStackState(position = BarPosition.TOP)
        FloatingBarStack(
            state = stackState,
            position = BarPosition.TOP,
            bars = {
                SearcherTopBars(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(),
                    state = SearcherMockDataProvider.createMockResultsState(),
                    onPageAction = {},
                )
            },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherBottomBarsPreview() {
    PreviewWrapper {
        val stackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM)
        FloatingBarStack(
            state = stackState,
            position = BarPosition.BOTTOM,
            bars = {
                SearcherBottomBars(
                    state = SearcherMockDataProvider.createMockResultsState(),
                    operationsState = OperationsDisplayState(
                        operations = listOf(SearcherMockDataProvider.createMockRunningOperation()),
                    ),
                    clipboardState = ClipboardDisplayState(),
                    onPageAction = {},
                )
            },
        )
    }
}
