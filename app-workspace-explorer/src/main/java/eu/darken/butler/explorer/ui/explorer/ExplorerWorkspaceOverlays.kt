package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.explorer.ui.explorer.dialogs.AddDeviceStorageSheet
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogHost
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationDialog
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState

/**
 * Overlay slot of the explorer page.
 *
 * Shares the ViewModel with [ExplorerWorkspacePageHost] — the SAF picker launcher, the share and
 * toast event collectors and the error/navigation handlers all stay there and must not be repeated
 * here.
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
    val operationDialogState by vm.operationDialogState.collectAsState()
    val cancelConfirmation by vm.cancelOperationConfirmation.collectAsState()

    ExplorerWorkspaceOverlays(
        design = design,
        dialogState = state?.dialogState ?: ExplorerDialogState.None,
        trashEnabled = state?.trashEnabled == true,
        operationsState = operationsState ?: OperationsDisplayState(),
        operationDialogState = operationDialogState,
        cancelConfirmationFor = cancelConfirmation,
        issue = issueState.takeIf { showIssueSheet },
        showAddStorageSheet = showAddStorageSheet,
        vm = vm,
    )
}

@Composable
fun ExplorerWorkspaceOverlays(
    design: WorkspaceDesign = WorkspaceDesign(),
    dialogState: ExplorerDialogState = ExplorerDialogState.None,
    trashEnabled: Boolean = false,
    operationsState: OperationsDisplayState = OperationsDisplayState(),
    operationDialogState: OperationDialogState = OperationDialogState.None,
    cancelConfirmationFor: Operation.Id? = null,
    issue: Issue? = null,
    showAddStorageSheet: Boolean = false,
    vm: ExplorerWorkspaceViewModel? = null,
) {
    val density = LocalDensity.current
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else {
        0.dp
    }

    ExplorerDialogHost(
        dialogState = dialogState,
        trashEnabled = trashEnabled,
        vm = vm,
        bottomInset = navBarInset,
    )

    OperationDialogHost(
        dialogState = operationDialogState,
        operations = operationsState.operations,
        onDismissDialog = { vm?.dismissOperationDialog() },
        onCancelOperation = { operationId -> vm?.requestCancelOperation(operationId) },
        onShareError = { vm?.shareError(it) },
        onHandleIssue = { operationId -> vm?.showConflictSheet(operationId) },
        bottomInset = navBarInset,
    )

    // Conflict resolution. showIssueSheet is durable VM state so a notification-driven open
    // survives recomposition / late collector subscription.
    issue?.let {
        IssuesBottomSheet(
            issue = it,
            onResolution = { resolution -> vm?.resolveConflict(resolution) },
            onDismiss = { vm?.dismissConflictSheet() },
            bottomInset = navBarInset,
        )
    }

    if (showAddStorageSheet) {
        AddDeviceStorageSheet(
            onDismiss = { vm?.dismissAddStorageSheet() },
            onContinue = { vm?.addSAFLocation() },
            bottomInset = navBarInset,
        )
    }

    cancelConfirmationFor?.let { operationId ->
        CancelOperationConfirmationDialog(
            onDismiss = { vm?.dismissCancelOperationConfirmation() },
            onConfirm = {
                vm?.cancelOperation(operationId)
                vm?.dismissCancelOperationConfirmation()
            },
        )
    }
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
private fun ExplorerWorkspaceOverlaysCancelOperationPreview() {
    ExplorerWorkspaceOverlays(cancelConfirmationFor = Operation.Id())
}
