package eu.darken.butler.workspace.core

import eu.darken.butler.common.files.APath

sealed interface WorkspaceEvent {
    data class Created(
        val workspaceId: Workspace.Id,
        val replacedId: Workspace.Id? = null,
    ) : WorkspaceEvent

    data class Closed(
        val workspaceId: Workspace.Id,
    ) : WorkspaceEvent

    data class Reordered(
        val workspaceIds: List<Workspace.Id>,
    ) : WorkspaceEvent

    data class SelectionRequested(
        val workspaceId: Workspace.Id,
    ) : WorkspaceEvent

    data object AllClosed : WorkspaceEvent

    /**
     * Base interface for workspace result events.
     * Used for workspaces that return results to caller workspaces (e.g., pickers, dialogs).
     */
    sealed interface ResultEvent : WorkspaceEvent {
        val workspaceId: Workspace.Id
        val callerWorkspaceId: Workspace.Id?
    }

    /**
     * Emitted when a picker workspace has selection confirmed
     *
     * @param workspaceId Workspace that generated the result (the picker)
     * @param callerWorkspaceId Workspace that expects the result (null if not specified)
     * @param selectedPaths Selected file/folder paths
     */
    data class PickerResult(
        override val workspaceId: Workspace.Id,
        override val callerWorkspaceId: Workspace.Id?,
        val selectedPaths: List<APath<*>>,
    ) : ResultEvent

    /**
     * Emitted when a result-returning workspace is cancelled without providing a result
     *
     * @param workspaceId Workspace that was cancelled
     * @param callerWorkspaceId Workspace that was expecting a result (null if not specified)
     */
    data class ResultCancelled(
        override val workspaceId: Workspace.Id,
        override val callerWorkspaceId: Workspace.Id?,
    ) : ResultEvent
}