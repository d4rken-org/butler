package eu.darken.butler.workspace.ui.dialogs

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace

/**
 * Unified representation of manager-level dialogs.
 * Provides a single source of truth for dialog state with blocking semantics.
 */
sealed interface ManagerDialog {
    val id: String
    val isBlocking: Boolean

    /**
     * Global dialogs that are not targeted to a specific workspace.
     */
    sealed interface Global : ManagerDialog {
        /**
         * Dialog shown when non-pro users reach the workspace limit.
         *
         * [closableTitle] names the tab the dialog offers to close to make room for the blocked
         * create; null when no tab may be closed for it and the action is not offered.
         */
        data class WorkspaceLimitReached(
            override val id: String,
            val currentCount: Int,
            val limit: Int,
            val closableTitle: CaString? = null,
        ) : Global {
            override val isBlocking: Boolean = true
        }
    }

    /**
     * Dialogs that target a specific workspace.
     * In multi-pane layouts, these appear overlaid on the specific workspace pane.
     */
    sealed interface WorkspaceTargeted : ManagerDialog {
        val targetWorkspaceId: Workspace.Id

        /**
         * Confirmation dialog for opening multiple items in new tabs.
         */
        data class BatchCreationConfirmation(
            override val id: String,
            override val targetWorkspaceId: Workspace.Id,
            val totalCount: Int,
        ) : WorkspaceTargeted {
            override val isBlocking: Boolean = true
        }

        /**
         * Confirmation dialog for closing a workspace.
         */
        data class CloseConfirmation(
            override val id: String,
            override val targetWorkspaceId: Workspace.Id,
            val workspaceTitle: CaString,
            val hasUnsavedChanges: Boolean = false,
        ) : WorkspaceTargeted {
            override val isBlocking: Boolean = true
        }
    }
}
