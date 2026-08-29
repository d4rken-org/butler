package eu.darken.butler.workspace.ui.dialogs

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceLimitCandidate

/**
 * Unified representation of manager-level dialogs.
 * Provides a single source of truth for dialog state with blocking semantics.
 */
sealed interface ManagerDialog {
    val id: String
    val isBlocking: Boolean

    /**
     * Dialogs no workspace hosts: they are composed at screen level, above every pane. Some of them
     * still name a workspace - what a dialog is about and where it renders are separate questions.
     */
    sealed interface Global : ManagerDialog {
        /**
         * Dialog shown when non-pro users reach the workspace limit.
         *
         * [candidates] are the open tabs the dialog offers to close to make room for the blocked
         * create, oldest first; empty when there is no create to replay. [canRecover] false means
         * they are shown for information only - closing all of them still would not free a slot.
         * [minToClose] is how many have to be selected before the confirm action does anything.
         */
        data class WorkspaceLimitReached(
            override val id: String,
            val currentCount: Int,
            val limit: Int,
            val candidates: List<WorkspaceLimitCandidate> = emptyList(),
            val canRecover: Boolean = false,
            val minToClose: Int = 1,
        ) : Global {
            override val isBlocking: Boolean = true
        }

        /**
         * Confirmation for closing a tab that nothing on screen can host the question for.
         *
         * [selectionSourceWorkspaceId] is the pane the jump to [closingWorkspaceId] acts FROM, not
         * where that tab lands: it protects that pane from eviction and orders the search for an
         * empty one. Null when nothing on screen can play that part.
         */
        data class CloseConfirmation(
            override val id: String,
            val closingWorkspaceId: Workspace.Id,
            val workspaceTitle: CaString,
            val hasUnsavedChanges: Boolean = false,
            /** Unsaved members in the closing subtree; [workspaceTitle] names only one of them. */
            val unsavedCount: Int = 0,
            val selectionSourceWorkspaceId: Workspace.Id?,
        ) : Global {
            override val isBlocking: Boolean = true
        }
    }

    /**
     * Dialogs that target a specific workspace.
     * In multi-pane layouts, these appear overlaid on the specific workspace pane.
     */
    sealed interface WorkspaceTargeted : ManagerDialog {
        /** The workspace whose pane hosts this dialog, which is not necessarily what it acts on. */
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
         *
         * [closingWorkspaceId] is the tab this asks about; it differs from [targetWorkspaceId]
         * whenever the close was invoked from somewhere other than that tab's own pane.
         */
        data class CloseConfirmation(
            override val id: String,
            override val targetWorkspaceId: Workspace.Id,
            val closingWorkspaceId: Workspace.Id,
            val workspaceTitle: CaString,
            val hasUnsavedChanges: Boolean = false,
            /** Unsaved members in the closing subtree; [workspaceTitle] names only one of them. */
            val unsavedCount: Int = 0,
        ) : WorkspaceTargeted {
            override val isBlocking: Boolean = true
        }
    }
}
