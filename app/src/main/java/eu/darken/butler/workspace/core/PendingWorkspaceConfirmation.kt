package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.CaString

// Generic confirmation system
data class PendingWorkspaceConfirmation(
    val id: String,
    val sourceWorkspaceId: Workspace.Id?,
    val data: ConfirmationData,
) {
    sealed interface ConfirmationData {
        /**
         * Confirmation for creating multiple workspaces at once
         */
        data class BatchWorkspaceCreation(
            val totalCount: Int,
            val skippedCount: Int = 0,
        ) : ConfirmationData

        /**
         * Confirmation for closing a workspace
         */
        data class WorkspaceCloseConfirmation(
            val workspaceId: Workspace.Id,
            val workspaceTitle: CaString,
        ) : ConfirmationData

        /**
         * Notification when workspace limit is reached for non-pro users.
         * User can choose to upgrade or dismiss.
         */
        data class WorkspaceLimitReached(
            val currentCount: Int,
            val limit: Int,
        ) : ConfirmationData
    }
}