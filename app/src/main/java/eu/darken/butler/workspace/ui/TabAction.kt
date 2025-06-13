package eu.darken.butler.workspace.ui

import eu.darken.butler.workspace.core.Workspace

sealed interface TabAction {
    data class Select(
        val id: Workspace.Id,
    ) : TabAction

    data class Create(
        val type: Workspace.Type = Workspace.Type.NEW,
        val arguments: Workspace.Arguments? = null,
        val replace: Workspace.Id? = null,
    ) : TabAction

    data class Close(
        val id: Workspace.Id,
    ) : TabAction
}