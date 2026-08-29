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
            /**
             * How many workspaces in the closing subtree hold unsaved changes. [workspaceTitle]
             * names one of them, so anything above 1 has to be said out loud.
             */
            val unsavedCount: Int = 0,
            /**
             * Whether the workspace this confirmation is anchored to ([sourceWorkspaceId]) is one of
             * the workspaces the close removes. False means the anchor is an unrelated tab's.
             */
            val hostInClosingSubtree: Boolean,
        ) : ConfirmationData

        /**
         * Notification when workspace limit is reached for non-pro users.
         * User can choose to upgrade, dismiss, or close tabs to make room.
         *
         * [candidates] are the open tabs the dialog lists, oldest first, each carrying whether it may
         * be closed. Bound here rather than recomputed when the user taps: the dialog names specific
         * tabs, and re-picking at click time could close a different tab than the one they agreed to.
         * Empty when there is no blocked create to replay (batch creates), which reduces the dialog
         * to a plain notice.
         *
         * [canRecover] is false when the tabs are listed for information only - a session restore can
         * push the count so far past [limit] that closing everything closable still would not free a
         * slot, and offering the action would promise something the replay cannot deliver.
         */
        data class WorkspaceLimitReached(
            val currentCount: Int,
            val limit: Int,
            val candidates: List<WorkspaceLimitCandidate> = emptyList(),
            val canRecover: Boolean = false,
        ) : ConfirmationData {
            /**
             * How many tabs have to go before the blocked create fits. Normally 1; a restore
             * overshoot can require more.
             */
            val minToClose: Int get() = (currentCount - limit + 1).coerceAtLeast(1)
        }
    }
}