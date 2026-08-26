package eu.darken.butler.workspace.ui.dialogs

import eu.darken.butler.workspace.core.Workspace

/**
 * What a [ManagerDialog] reports back, whichever host composed it.
 *
 * Everything is keyed on the confirmation id rather than on a workspace: two confirmations can
 * share a host, so resolving by host would be able to resolve the wrong one.
 */
sealed interface ManagerDialogAction {

    /** Answers the confirmation - [confirmed] false is a cancel, which is also what a dismiss is. */
    data class Resolve(
        val confirmationId: String,
        val confirmed: Boolean,
    ) : ManagerDialogAction

    /**
     * Cancels the confirmation and puts [workspaceId] on screen, so the user can deal with the tab
     * the dialog was about. One action rather than two: the confirmation has to be gone before the
     * selection lands, or the dialog re-renders in the destination pane.
     *
     * [sourceWorkspaceId] is the pane the selection should place the tab near - the dialog's host
     * pane, or the focused workspace when the dialog had no pane host.
     */
    data class CancelAndGoToWorkspace(
        val confirmationId: String,
        val workspaceId: Workspace.Id,
        val sourceWorkspaceId: Workspace.Id?,
    ) : ManagerDialogAction
}
