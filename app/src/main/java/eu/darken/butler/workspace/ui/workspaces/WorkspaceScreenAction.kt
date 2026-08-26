package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.ManagerDialogAction

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

    /**
     * A manager dialog reported back. The dialog layer lives in `app-workspace` and cannot name
     * this type, so its own action channel stops at the module boundary and is bridged here.
     */
    data class HandleDialog(
        val action: ManagerDialogAction,
    ) : WorkspaceScreenAction

    /** A single item was dropped on an empty pane; open it there. */
    data class OpenDropInPane(
        val paneIndex: Int,
        val payload: WorkspaceDragPayload,
    ) : WorkspaceScreenAction
}