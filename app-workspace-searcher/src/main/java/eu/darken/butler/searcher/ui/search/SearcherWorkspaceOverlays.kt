package eu.darken.butler.searcher.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.openPrivacyPolicy
import eu.darken.butler.searcher.ui.search.dialogs.SearchErrorDialog
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogHost
import eu.darken.butler.searcher.ui.search.elements.AccessErrorsSheetContent
import eu.darken.butler.searcher.ui.search.elements.SearchResultItemDetails
import eu.darken.butler.searcher.ui.search.elements.TemplatesBottomSheetContent
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.workspace.ui.bottomsheet.SheetContentScroll
import eu.darken.butler.workspace.ui.error.ErrorShareConsentDialog
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Overlay slot of the searcher page.
 *
 * Shares the ViewModel with [SearcherWorkspacePageHost] — the share-intent collector and the
 * navigation handler stay there and must not be repeated here. The error handler lives here
 * instead, because it renders a dialog that has to be pane-bound.
 */
@Composable
fun SearcherWorkspaceOverlaysHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SearcherWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SearcherWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    val overlayState by vm.overlayState.collectAsState()
    val pendingErrorShare by vm.pendingErrorShare.collectAsState()

    SearcherWorkspaceOverlays(
        design = design,
        stateSource = vm.state,
        operationsStateSource = vm.operations,
        issueStateSource = vm.issueState,
        overlayState = overlayState,
        showErrorShareConsent = pendingErrorShare != null,
        onPageAction = vm::onPageAction,
    )

    // Last on purpose: layers stack in composition order, so an error raised while one of
    // this page's own dialogs is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}

@Composable
fun SearcherWorkspaceOverlays(
    design: WorkspaceDesign = WorkspaceDesign(),
    stateSource: Flow<SearcherWorkspaceViewModel.State>,
    operationsStateSource: Flow<OperationsDisplayState?> = flowOf(null),
    issueStateSource: Flow<Issue?> = flowOf(null),
    overlayState: SearcherWorkspaceViewModel.OverlayState = SearcherWorkspaceViewModel.OverlayState(),
    showErrorShareConsent: Boolean = false,
    onPageAction: (SearcherPageAction) -> Unit = {},
) {
    // StateFlow check: use current value as initial for single-frame renderers (screenshot tests, previews)
    val mainState by stateSource.collectAsState(
        initial = (stateSource as? StateFlow)?.value ?: SearcherWorkspaceViewModel.State.Initializing
    )
    val operationsStateRaw by operationsStateSource.collectAsState(initial = null)
    val operationsState = operationsStateRaw ?: OperationsDisplayState()
    val issueState by issueStateSource.collectAsState(initial = (issueStateSource as? StateFlow)?.value)

    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom
    val statusBarInset = paneInsets.top

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val currentState = mainState as? SearcherWorkspaceViewModel.State.Ready

    // Error dialog for individual search target failures
    overlayState.targetError?.let { targetError ->
        SearchErrorDialog(
            path = targetError.path,
            exception = targetError.error,
            onShareError = {
                onPageAction(SearcherPageAction.Error.Share(targetError.error, targetError.path))
                onPageAction(SearcherPageAction.Overlays.DismissTargetError)
            },
            onDismiss = { onPageAction(SearcherPageAction.Overlays.DismissTargetError) },
        )
    }

    // Templates bottom sheet. The content bounds and scrolls itself — its gradient is overlaid at
    // the fixed bottom of that bounded box, so the sheet must not take the scrolling over.
    PaneScopedBottomSheet(
        visible = overlayState.showTemplatesSheet,
        onDismiss = { onPageAction(SearcherPageAction.Overlays.DismissTemplates) },
        topInset = statusBarInset,
        contentScroll = SheetContentScroll.ContentOwned,
    ) {
        TemplatesBottomSheetContent(
            bottomPadding = navBarInset,
            onTemplateClick = { template ->
                onPageAction(SearcherPageAction.Overlays.DismissTemplates)
                onPageAction(SearcherPageAction.Templates.Apply(template))
            },
        )
    }

    if (currentState != null) {
        // Access-errors detail sheet, opened from the progress card's inaccessible-items line.
        // Auto-dismiss when the errors clear (e.g. the post-setup rerun succeeded) so it never
        // shows a stale empty list.
        val accessErrorCount = currentState.workspaceState.targetProgress.sumOf { it.accessErrorCount }
        LaunchedEffect(accessErrorCount) {
            if (accessErrorCount == 0) onPageAction(SearcherPageAction.Overlays.DismissAccessErrors)
        }
        PaneScopedBottomSheet(
            visible = overlayState.showAccessErrorsSheet,
            onDismiss = { onPageAction(SearcherPageAction.Overlays.DismissAccessErrors) },
            topInset = statusBarInset,
            // Bounded and scrolled by the content itself
            contentScroll = SheetContentScroll.ContentOwned,
        ) {
            AccessErrorsSheetContent(
                targetProgress = currentState.workspaceState.targetProgress,
                accessErrorRequirements = currentState.workspaceState.accessErrorRequirements,
                bottomPadding = navBarInset,
                onUnlockAccess = {
                    onPageAction(SearcherPageAction.Overlays.DismissAccessErrors)
                    onPageAction(SearcherPageAction.Setup.Open(currentState.workspaceState.accessErrorRequirements))
                },
            )
        }

        // Item details bottom sheet
        currentState.quickActionsResult?.let { result ->
            SearchResultItemDetails(
                result = result,
                trashEnabled = currentState.trashEnabled,
                onAction = { action ->
                    onPageAction(SearcherPageAction.WorkspaceAction(action))
                    onPageAction(SearcherPageAction.Results.HideQuickActions)
                },
                onLongPress = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onPageAction(SearcherPageAction.Results.EnterSelectionMode(it))
                    onPageAction(SearcherPageAction.Results.HideQuickActions)
                },
                onDismiss = { onPageAction(SearcherPageAction.Results.HideQuickActions) },
                topInset = statusBarInset,
                bottomInset = navBarInset,
            )
        }
    }

    // Issue/conflict resolution bottom sheet
    issueState?.let { issue ->
        IssuesBottomSheet(
            issue = issue,
            onResolution = { resolution -> onPageAction(SearcherPageAction.Issues.Resolve(resolution)) },
            onDismiss = { /* Issue will auto-clear when resolved or cancelled */ },
            topInset = statusBarInset,
            bottomInset = navBarInset,
        )
    }

    if (currentState != null) {
        // Dialog host (handles all dialogs and bottom sheets)
        SearcherDialogHost(
            dialogState = currentState.dialogState,
            trashEnabled = currentState.trashEnabled,
            onDismiss = { onPageAction(SearcherPageAction.Dialogs.Dismiss) },
            onDeleteConfirmed = { items, forcePermDelete ->
                onPageAction(SearcherPageAction.Dialogs.DeleteConfirmed(items, forcePermDelete))
            },
            onCopyToClipboard = { text -> onPageAction(SearcherPageAction.Clipboard.CopyText(text)) },
            onNavigateToClipboardSource = { clip -> onPageAction(SearcherPageAction.Clipboard.NavigateToSource(clip)) },
            onRemoveClipboardEntry = { clip -> onPageAction(SearcherPageAction.Clipboard.RemoveEntry(clip)) },
            onSortOptionsConfirmed = { onPageAction(SearcherPageAction.Dialogs.SortOptionsConfirmed(it)) },
            onClearHistoryConfirmed = { onPageAction(SearcherPageAction.Dialogs.ClearHistoryConfirmed) },
            onConditionApply = { existing, new ->
                existing?.let { onPageAction(SearcherPageAction.Filter.RemoveCondition(it)) }
                onPageAction(SearcherPageAction.Filter.AddCondition(new))
            },
            topInset = statusBarInset,
            bottomInset = navBarInset,
        )
    }

    // Operation dialog host
    OperationDialogHost(
        dialogState = overlayState.operationDialogState,
        operations = operationsState.operations,
        onDismissDialog = { onPageAction(SearcherPageAction.Overlays.DismissOperationDetails) },
        onCancelOperation = { operationId ->
            onPageAction(SearcherPageAction.Overlays.DismissOperationDetails)
            onPageAction(SearcherPageAction.Operations.Cancel(operationId))
        },
        onShareError = { onPageAction(SearcherPageAction.Operations.ShareError(it)) },
        onHandleIssue = { operationId ->
            onPageAction(SearcherPageAction.Operations.ShowConflict(operationId))
        },
        onShowInHistory = { operationId ->
            onPageAction(SearcherPageAction.Operations.ShowInHistory(operationId))
        },
        historyEnabled = operationsState.historyEnabled,
        topInset = statusBarInset,
        bottomInset = navBarInset,
    )

    if (showErrorShareConsent) {
        val context = LocalContext.current
        ErrorShareConsentDialog(
            onConfirm = { onPageAction(SearcherPageAction.Error.ConfirmShare) },
            onDismiss = { onPageAction(SearcherPageAction.Error.DismissShare) },
            onPrivacyPolicy = { openPrivacyPolicy(context) },
        )
    }

    CancelOperationConfirmationHost(
        pendingId = overlayState.cancelOperationConfirmationFor,
        // Deliberately the raw value: null here means "not loaded yet", which the confirmation has
        // to tell apart from an operation that is genuinely gone.
        operations = operationsStateRaw?.operations,
        onDismiss = { onPageAction(SearcherPageAction.Overlays.DismissCancelOperation) },
        onConfirm = { operationId ->
            onPageAction(SearcherPageAction.Operations.Cancel(operationId))
            onPageAction(SearcherPageAction.Overlays.DismissCancelOperation)
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherWorkspaceOverlaysTemplatesPreview() {
    SearcherWorkspaceOverlays(
        stateSource = flowOf(SearcherMockDataProvider.createMockEmptyState()),
        overlayState = SearcherWorkspaceViewModel.OverlayState(showTemplatesSheet = true),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherWorkspaceOverlaysCancelOperationPreview() {
    val operation = SearcherMockDataProvider.createMockRunningOperation()
    SearcherWorkspaceOverlays(
        stateSource = flowOf(SearcherMockDataProvider.createMockEmptyState()),
        operationsStateSource = flowOf(OperationsDisplayState(operations = listOf(operation))),
        overlayState = SearcherWorkspaceViewModel.OverlayState(cancelOperationConfirmationFor = operation.id),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherWorkspaceOverlaysErrorShareConsentPreview() {
    SearcherWorkspaceOverlays(
        stateSource = flowOf(SearcherMockDataProvider.createMockEmptyState()),
        showErrorShareConsent = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherWorkspaceOverlaysTargetErrorPreview() {
    SearcherWorkspaceOverlays(
        stateSource = flowOf(SearcherMockDataProvider.createMockEmptyState()),
        overlayState = SearcherWorkspaceViewModel.OverlayState(
            targetError = SearcherWorkspaceViewModel.TargetError(
                path = "/storage/emulated/0/Android/data",
                error = IllegalStateException("Permission denied"),
            ),
        ),
    )
}
