package eu.darken.butler.workspace.core

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
}