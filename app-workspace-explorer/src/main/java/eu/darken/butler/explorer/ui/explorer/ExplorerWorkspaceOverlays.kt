package eu.darken.butler.explorer.ui.explorer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.storage.saf.StorageProviderSuggestion
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.BrowsingAbortedException
import eu.darken.butler.explorer.ui.explorer.dialogs.AddDeviceStorageSheet
import eu.darken.butler.explorer.ui.explorer.dialogs.BrowsingAbortedDialog
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogHost
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationHost
import eu.darken.butler.workspace.ui.error.ErrorShareConsentDialog
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState

/**
 * Overlay slot of the explorer page.
 *
 * Shares the ViewModel with [ExplorerWorkspacePageHost] — the SAF picker launcher, the share and
 * toast event collectors and the navigation handler all stay there and must not be repeated here.
 *
 * The error handler is the exception: it renders a dialog, so it has to live in this slot to be
 * pane-bound. It is collected here and nowhere else.
 */
@Composable
fun ExplorerWorkspaceOverlaysHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: ExplorerWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: ExplorerWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    val state by vm.state.collectAsState(initial = null)
    val operationsState by vm.operations.collectAsState(initial = null)
    val issueState by vm.issueState.collectAsState()
    val showIssueSheet by vm.showIssueSheet.collectAsState()
    val showAddStorageSheet by vm.showAddStorageSheet.collectAsState()
    val storageSuggestions by vm.storageSuggestions.collectAsState()
    val operationDialogState by vm.operationDialogState.collectAsState()
    val cancelConfirmation by vm.cancelOperationConfirmation.collectAsState()
    val pendingErrorShare by vm.pendingErrorShare.collectAsState()

    ExplorerWorkspaceOverlays(
        design = design,
        dialogState = state?.dialogState ?: ExplorerDialogState.None,
        trashEnabled = state?.trashEnabled == true,
        fileOpenActionsEnabled = state?.fileOpenActionsEnabled != false,
        operationsState = operationsState,
        operationDialogState = operationDialogState,
        cancelConfirmationFor = cancelConfirmation,
        issue = issueState.takeIf { showIssueSheet },
        showAddStorageSheet = showAddStorageSheet,
        storageSuggestions = storageSuggestions,
        navigationError = state?.error,
        showErrorShareConsent = pendingErrorShare != null,
        vm = vm,
    )

    // Last on purpose: layers stack in composition order, so an error raised while one of
    // this page's own dialogs is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}

@Composable
fun ExplorerWorkspaceOverlays(
    design: WorkspaceDesign = WorkspaceDesign(),
    dialogState: ExplorerDialogState = ExplorerDialogState.None,
    trashEnabled: Boolean = false,
    fileOpenActionsEnabled: Boolean = true,
    // Null while the operations flow has not emitted; the cancel confirmation needs to tell that
    // apart from an empty list.
    operationsState: OperationsDisplayState? = OperationsDisplayState(),
    operationDialogState: OperationDialogState = OperationDialogState.None,
    cancelConfirmationFor: Operation.Id? = null,
    issue: Issue? = null,
    showAddStorageSheet: Boolean = false,
    storageSuggestions: List<StorageProviderSuggestion> = emptyList(),
    navigationError: Throwable? = null,
    showErrorShareConsent: Boolean = false,
    vm: ExplorerWorkspaceViewModel? = null,
) {
    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom
    val statusBarInset = paneInsets.top

    ExplorerDialogHost(
        dialogState = dialogState,
        trashEnabled = trashEnabled,
        fileOpenActionsEnabled = fileOpenActionsEnabled,
        vm = vm,
        topInset = statusBarInset,
        bottomInset = navBarInset,
    )

    OperationDialogHost(
        dialogState = operationDialogState,
        operations = operationsState?.operations.orEmpty(),
        onDismissDialog = { vm?.dismissOperationDialog() },
        onCancelOperation = { operationId -> vm?.requestCancelOperation(operationId) },
        onShareError = { vm?.shareError(it) },
        onHandleIssue = { operationId -> vm?.showConflictSheet(operationId) },
        topInset = statusBarInset,
        bottomInset = navBarInset,
    )

    // Conflict resolution. showIssueSheet is durable VM state so a notification-driven open
    // survives recomposition / late collector subscription.
    issue?.let {
        IssuesBottomSheet(
            issue = it,
            onResolution = { resolution -> vm?.resolveConflict(resolution) },
            onDismiss = { vm?.dismissConflictSheet() },
            topInset = statusBarInset,
            bottomInset = navBarInset,
        )
    }

    if (showAddStorageSheet) {
        AddDeviceStorageSheet(
            onDismiss = { vm?.dismissAddStorageSheet() },
            onContinue = { vm?.addSAFLocation() },
            suggestions = storageSuggestions,
            onSuggestion = { vm?.addSuggestedSAFLocation(it) },
            topInset = statusBarInset,
            bottomInset = navBarInset,
        )
    }

    // A cancelled load with nothing to fall back to: the page shows no error card for it, this
    // dialog is the whole answer to it.
    (navigationError as? BrowsingAbortedException)?.let { aborted ->
        BrowsingAbortedDialog(
            onRetry = { vm?.navigate(aborted.target) },
            onDismiss = { vm?.dismissNavigationError() },
        )
    }

    if (showErrorShareConsent) {
        ErrorShareConsentDialog(
            onConfirm = { vm?.confirmErrorShare() },
            onDismiss = { vm?.dismissErrorShare() },
        )
    }

    CancelOperationConfirmationHost(
        pendingId = cancelConfirmationFor,
        operations = operationsState?.operations,
        onDismiss = { vm?.dismissCancelOperationConfirmation() },
        onConfirm = { operationId ->
            vm?.cancelOperation(operationId)
            vm?.dismissCancelOperationConfirmation()
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspaceOverlaysCreateItemPreview() {
    ExplorerWorkspaceOverlays(dialogState = ExplorerDialogState.CreateItem)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspaceOverlaysRenamePreview() {
    ExplorerWorkspaceOverlays(
        dialogState = ExplorerDialogState.Rename(LocalPath.build("/storage/emulated/0/DCIM", "Photos")),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspaceOverlaysBrowsingAbortedPreview() {
    ExplorerWorkspaceOverlays(
        navigationError = BrowsingAbortedException(ExplorerNavigation.Target.Home),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspaceOverlaysErrorShareConsentPreview() {
    ExplorerWorkspaceOverlays(showErrorShareConsent = true)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerWorkspaceOverlaysCancelOperationPreview() {
    val operation = MockDataProvider.createMockRunningOperation()
    ExplorerWorkspaceOverlays(
        operationsState = OperationsDisplayState(operations = listOf(operation)),
        cancelConfirmationFor = operation.id,
    )
}
