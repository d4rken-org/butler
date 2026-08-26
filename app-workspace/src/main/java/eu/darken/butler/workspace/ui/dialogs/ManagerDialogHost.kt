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
    onAction: (ManagerDialogAction) -> Unit,
) {
    when (dialog) {
        null -> Unit
        is ManagerDialog.WorkspaceTargeted.BatchCreationConfirmation -> OpenInNewTabsConfirmationDialog(
            totalCount = dialog.totalCount,
            onDismiss = { onAction(ManagerDialogAction.Resolve(dialog.id, confirmed = false)) },
            onConfirm = { onAction(ManagerDialogAction.Resolve(dialog.id, confirmed = true)) },
        )
        is ManagerDialog.WorkspaceTargeted.CloseConfirmation -> WorkspaceCloseConfirmationDialog(
            workspaceTitle = dialog.workspaceTitle,
            hasUnsavedChanges = dialog.hasUnsavedChanges,
            unsavedCount = dialog.unsavedCount,
            onDismiss = { onAction(ManagerDialogAction.Resolve(dialog.id, confirmed = false)) },
            onConfirm = { onAction(ManagerDialogAction.Resolve(dialog.id, confirmed = true)) },
            // This pane belongs to a different tab than the one being closed, so the tab the dialog
            // names is elsewhere and needs a way to be reached.
            onGoToWorkspace = if (dialog.closingWorkspaceId != dialog.targetWorkspaceId) {
                {
                    onAction(
                        ManagerDialogAction.CancelAndGoToWorkspace(
                            confirmationId = dialog.id,
                            workspaceId = dialog.closingWorkspaceId,
                            sourceWorkspaceId = dialog.targetWorkspaceId,
                            hideManagerOverlay = false,
                        )
                    )
                }
            } else {
                null
            },
        )
    }
}
