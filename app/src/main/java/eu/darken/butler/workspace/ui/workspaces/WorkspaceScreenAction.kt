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

    /** Sets or clears ([customTitle] == null) the user-set name of a workspace. */
    data class Rename(
        val id: Workspace.Id,
        val customTitle: String?,
    ) : WorkspaceScreenAction

    /** Resumes a paused workspace, bringing back its live instance. Does not change focus. */
    data class ResumeWorkspace(
        val id: Workspace.Id,
    ) : WorkspaceScreenAction

    data object CreateOnDemand : WorkspaceScreenAction

    data class CreateForPane(
        val paneIndex: Int,
    ) : WorkspaceScreenAction
}