package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.runtime.Composable

/**
 * Renders the manager-controlled dialog for one workspace, if there is one.
 *
 * Composed by the pane layer host as its own layer above the workspace content, and deliberately
 * not gated on the workspace's lifecycle state — a close confirmation for a paused workspace must
 * still be reachable.
 */
@Composable
fun ManagerDialogHost(
    dialog: ManagerDialog.WorkspaceTargeted?,
    onDismiss: (ManagerDialog.WorkspaceTargeted) -> Unit,
    onConfirm: (ManagerDialog.WorkspaceTargeted) -> Unit,
) {
    when (dialog) {
        null -> Unit
        is ManagerDialog.WorkspaceTargeted.BatchCreationConfirmation -> OpenInNewTabsConfirmationDialog(
            totalCount = dialog.totalCount,
            onDismiss = { onDismiss(dialog) },
            onConfirm = { onConfirm(dialog) },
        )
        is ManagerDialog.WorkspaceTargeted.CloseConfirmation -> WorkspaceCloseConfirmationDialog(
            workspaceTitle = dialog.workspaceTitle,
            hasUnsavedChanges = dialog.hasUnsavedChanges,
            unsavedCount = dialog.unsavedCount,
            onDismiss = { onDismiss(dialog) },
            onConfirm = { onConfirm(dialog) },
        )
    }
}
