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
            val hasUnsavedChanges: Boolean = false,
        ) : ConfirmationData

        /**
         * Notification when workspace limit is reached for non-pro users.
         * User can choose to upgrade, dismiss, or close the oldest closable tab to make room.
         *
         * [closableId] and [closableTitle] are both set or both null. The id is bound here rather
         * than recomputed when the user taps: the dialog names a tab, and re-picking the oldest one
         * at click time could close a different tab than the one the user agreed to.
         */
        data class WorkspaceLimitReached(
            val currentCount: Int,
            val limit: Int,
            val closableId: Workspace.Id? = null,
            val closableTitle: CaString? = null,
        ) : ConfirmationData
    }
}