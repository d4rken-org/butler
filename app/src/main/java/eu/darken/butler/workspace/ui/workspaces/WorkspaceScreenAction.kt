package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.workspace.core.Workspace

sealed interface WorkspaceScreenAction {
    data class Select(
        val id: Workspace.Id,
    ) : WorkspaceScreenAction

    data class SelectMultiple(
        val positions: Map<Int, Workspace.Id>,
    ) : WorkspaceScreenAction

    data class Focus(
        val id: Workspace.Id,
    ) : WorkspaceScreenAction

    data class ToggleSelection(
        val id: Workspace.Id,
        val position: Int? = null,
    ) : WorkspaceScreenAction
    
    data class SetPaneCount(
        val count: Int,
    ) : WorkspaceScreenAction
}