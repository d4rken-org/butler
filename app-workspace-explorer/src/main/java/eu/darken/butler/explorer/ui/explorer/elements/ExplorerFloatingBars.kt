package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.favorites.FavoriteFeedback
import eu.darken.butler.explorer.ui.explorer.ExplorerBarKeys
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBarAction
import eu.darken.butler.workspace.ui.clipboard.bar.WorkspaceClipboardFloatingBar
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarScope
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.bar.OperationsBarAction
import eu.darken.butler.workspace.ui.operations.bar.WorkspaceOperationsFloatingBar

/**
 * The Explorer's top floating bars: toolbar (with breadcrumbs/picker chrome) and info bar.
 *
 * Must stay a [FloatingBarScope] extension with the nested [FloatingBarScope.FloatingBar] calls
 * intact — `collapsedFraction` resolves from the inner bar's `FloatingBarContentScope` receiver.
 */
@Composable
internal fun FloatingBarScope.ExplorerTopBars(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    state: ExplorerWorkspaceViewModel.State,
    vm: ExplorerWorkspaceViewModel?,
    showProgress: Boolean,
) {
    val isLoadingItems = state.items == null && state.error == null

    // Determine if info bar should be visible
    val showInfoBar = state.info != null ||
        state.selectionState.selectedItems.isNotEmpty() ||
        isLoadingItems ||
        showProgress

    FloatingBar(
        key = ExplorerBarKeys.TOOLBAR,
        visible = true,
        scrollBehavior = BarScrollBehavior.CollapseOnScroll,
        animation = BarAnimation.Slide(),
        estimatedHeight = 64.dp,
    ) {
        ExplorerToolbarCard(
            workspaceId = workspaceId,
            breadcrumbs = state.breadcrumbs,
            design = design,
            collapsedFraction = collapsedFraction,
            onBreadcrumbClick = { target -> vm?.navigate(target) },
            onNavigateToPath = { path -> vm?.navigateToPath(path) },
            onCommitEditedPath = { current, edited -> vm?.navigateToEditedPath(current, edited) },
            onSetAsHome = { target -> vm?.setAsDefaultStartLocation(target) },
            onCopyPath = { path -> vm?.copyPathToSystemClipboard(path) },
            safLocationManager = vm?.safLocationManager,
            pickerSelection = state.pickerConfig?.selection,
            selectionCount = state.selectionState.selectedItems.size,
            saveAsFilename = state.saveAsFilename,
            canConfirmSelection = state.canConfirmSelection,
            onSaveAsFilenameChange = { filename -> vm?.updateSaveAsFilename(filename) },
            onCancel = { vm?.cancelPicker() },
            onConfirm = { vm?.confirmPickerSelection() },
        )
    }

    // InfoBar - only shown when NOT on permission screen
    FloatingBar(
        key = ExplorerBarKeys.INFOBAR,
        visible = showInfoBar && !state.setupRequirements.needsAction,
        scrollBehavior = BarScrollBehavior.Static,
        animation = BarAnimation.Slide(),
        estimatedHeight = 32.dp,
    ) {
        ExplorerInfoBar(
            info = state.info,
            isLoading = isLoadingItems,
            progress = if (showProgress) state.progress else null,
            onCancel = { vm?.navigate(ExplorerNavigation.Cancel) },
            selectedCount = state.selectionState.selectedItems.size,
            selectedSize = state.selectionState.selectedSize,
            onClearSelection = { vm?.clearSelection() },
            onSelectFolders = { vm?.selectAllFolders() },
            onSelectFiles = { vm?.selectAllFiles() },
            onSelectAll = { vm?.selectAll() },
            canSelectMultiple = state.pickerConfig?.selection?.isMultiSelect != false,
            isTrashDisabled = !state.trashEnabled,
        )
    }
}

/**
 * The Explorer's bottom floating bars: operations, clipboard, favorites-feedback and actions.
 */
@Composable
internal fun FloatingBarScope.ExplorerBottomBars(
    state: ExplorerWorkspaceViewModel.State,
    vm: ExplorerWorkspaceViewModel?,
    operationsState: OperationsDisplayState,
    clipboardState: ClipboardDisplayState,
    initialOperationsExpanded: Boolean,
    initialClipboardExpanded: Boolean,
    onShowOperationDetails: (Operation.Id) -> Unit,
) {
    val hasActions = state.availableActions.isNotEmpty()

    // Cache the last non-null favorite feedback so the bar has content to animate out when the
    // underlying state transitions back to null. FloatingBar keeps its content composed during
    // the slide-out, so the lambda must always have data.
    var lastFavoriteFeedback by remember { mutableStateOf<FavoriteFeedback?>(null) }
    state.favoriteFeedback?.let { lastFavoriteFeedback = it }
    val showFavoritesFeedbackBar = state.favoriteFeedback != null && state.pickerConfig == null

    WorkspaceOperationsFloatingBar(
        key = ExplorerBarKeys.OPERATIONS,
        operations = operationsState.operations,
        initialExpanded = initialOperationsExpanded,
        onAction = { action ->
            when (action) {
                is OperationsBarAction.RequestCancel -> vm?.requestCancelOperation(action.id)
                is OperationsBarAction.Dismiss -> vm?.dismissOperation(action.id)
                is OperationsBarAction.ShowConflict -> vm?.showConflictSheet(action.id)
                is OperationsBarAction.ShowDetails -> onShowOperationDetails(action.id)
                OperationsBarAction.ClearCompleted -> vm?.clearCompletedOperations()
            }
        },
    )

    WorkspaceClipboardFloatingBar(
        key = ExplorerBarKeys.CLIPBOARD,
        workspaceType = Workspace.Type.EXPLORER,
        clipboardEntries = clipboardState.entries,
        initialExpanded = initialClipboardExpanded,
        onAction = { action ->
            when (action) {
                is ClipboardBarAction.Paste -> vm?.pasteClipboard(action.clip)
                is ClipboardBarAction.Remove -> vm?.removeClipboardEntry(action.clip)
                is ClipboardBarAction.ShowInfo -> vm?.showClipboardInfo(action.clip)
                ClipboardBarAction.ClearAll -> vm?.clearAllClipboard()
            }
        },
    )

    FloatingBar(
        key = ExplorerBarKeys.FAVORITES_FEEDBACK,
        visible = showFavoritesFeedbackBar,
        scrollBehavior = BarScrollBehavior.Static,
        animation = BarAnimation.Slide(),
    ) {
        lastFavoriteFeedback?.let { feedback ->
            FavoritesFeedbackBar(
                feedback = feedback,
                onAction = { vm?.onFavoriteFeedbackAction() },
            )
        }
    }

    FloatingBar(
        key = ExplorerBarKeys.ACTIONS,
        visible = hasActions,
        scrollBehavior = BarScrollBehavior.HideOnScroll,
        animation = BarAnimation.Slide(),
        revealOn = state.selectionState.selectedItems,
    ) {
        WorkspaceActionBar(
            actions = state.availableActions,
            onActionClick = { action -> vm?.executeAction(action) },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerTopBarsPreview() {
    PreviewWrapper {
        val stackState = rememberFloatingBarStackState(position = BarPosition.TOP)
        FloatingBarStack(
            state = stackState,
            position = BarPosition.TOP,
            bars = {
                ExplorerTopBars(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(),
                    state = MockDataProvider.createReadyState(),
                    vm = null,
                    showProgress = false,
                )
            },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerBottomBarsPreview() {
    PreviewWrapper {
        val stackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM)
        FloatingBarStack(
            state = stackState,
            position = BarPosition.BOTTOM,
            bars = {
                ExplorerBottomBars(
                    state = MockDataProvider.createStateWithSelection(),
                    vm = null,
                    operationsState = MockDataProvider.createMockOperationsState(runningCount = 1, completedCount = 1),
                    clipboardState = MockDataProvider.createMockClipboardState(copyCount = 1, cutCount = 1),
                    initialOperationsExpanded = false,
                    initialClipboardExpanded = false,
                    onShowOperationDetails = {},
                )
            },
        )
    }
}
