package eu.darken.butler.workspace.core

sealed interface WorkspaceAction {
    data class Select(
        val id: Workspace.Id,
    ) : WorkspaceAction

    data class SelectMultiple(
        val positions: Map<Int, Workspace.Id>,
    ) : WorkspaceAction

    data class Focus(
        val id: Workspace.Id,
    ) : WorkspaceAction

    data class ToggleSelection(
        val id: Workspace.Id,
        val position: Int? = null,
    ) : WorkspaceAction

    data class Create(
        val type: Workspace.Type = Workspace.Type.TEMPLATES,
        val arguments: Workspace.Arguments? = null,
        val replace: Workspace.Id? = null,
    ) : WorkspaceAction

    data class Close(
        val id: Workspace.Id,
    ) : WorkspaceAction

    data class Reorder(
        val workspaceIds: List<Workspace.Id>,
    ) : WorkspaceAction

    data object CloseAll : WorkspaceAction
}