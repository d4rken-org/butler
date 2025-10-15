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

    data object AllClosed : WorkspaceEvent

    /**
     * Emitted when a picker workspace has selection confirmed
     *
     * @param pickerWorkspaceId Workspace that generated the result (the picker)
     * @param callerWorkspaceId Workspace that expects the result (null if not specified)
     * @param selectedPaths Selected file/folder paths
     */
    data class PickerResult(
        val pickerWorkspaceId: Workspace.Id,
        val callerWorkspaceId: Workspace.Id?,
        val selectedPaths: List<APath<*>>,
    ) : WorkspaceEvent
}