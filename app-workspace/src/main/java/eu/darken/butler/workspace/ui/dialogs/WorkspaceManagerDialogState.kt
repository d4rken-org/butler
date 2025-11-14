package eu.darken.butler.workspace.ui.dialogs

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace

/**
 * Dialog state for workspace manager-controlled dialogs that target specific workspaces.
 * These dialogs appear overlaid on the specific workspace pane that triggered them,
 * enabling proper multi-pane support without blocking unrelated workspaces.
 */
sealed interface WorkspaceManagerDialogState {
    /**
     * No dialog is currently shown.
     */
    data object None : WorkspaceManagerDialogState

    /**
     * Base interface for dialogs that target a specific workspace.
     * The targetWorkspaceId determines which pane the dialog appears over in multi-pane layouts.
     */
    sealed interface Targeted : WorkspaceManagerDialogState {
        val targetWorkspaceId: Workspace.Id
    }

    /**
     * Confirmation dialog for opening multiple items in new tabs.
     * Shown when the user attempts to open more than a threshold number of workspaces at once.
     *
     * @param confirmationId Unique ID linking this dialog to the pending confirmation in WorkspaceRepo
     * @param targetWorkspaceId Workspace pane where the dialog should appear
     * @param totalCount Total number of items that will be opened
     */
    data class OpenInNewTabsConfirmation(
        val confirmationId: String,
        override val targetWorkspaceId: Workspace.Id,
        val totalCount: Int,
    ) : Targeted

    /**
     * Confirmation dialog for closing a workspace.
     * Shown when the user attempts to close a workspace via back button navigation.
     *
     * @param confirmationId Unique ID linking this dialog to the pending confirmation in WorkspaceRepo
     * @param targetWorkspaceId Workspace pane where the dialog should appear (same as workspace being closed)
     * @param workspaceTitle Title of the workspace being closed
     */
    data class WorkspaceCloseConfirmation(
        val confirmationId: String,
        override val targetWorkspaceId: Workspace.Id,
        val workspaceTitle: CaString,
    ) : Targeted
}
