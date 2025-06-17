package eu.darken.butler.workspace.ui

import eu.darken.butler.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace

data class WorkspaceTab(
    val type: Workspace.Type,
    val id: Workspace.Id,
    val title: CaString,
) {
    companion object {
        val TEMPLATES = WorkspaceTab(
            type = Workspace.Type.TEMPLATES,
            id = Workspace.Id(),
            title = R.string.workspace_templates_title.toCaString(),
        )
        val EXPLORER = WorkspaceTab(
            type = Workspace.Type.EXPLORER,
            id = Workspace.Id(),
            title = R.string.explorer_title.toCaString(),
        )
        val SEARCHER = WorkspaceTab(
            type = Workspace.Type.SEARCHER,
            id = Workspace.Id(),
            title = R.string.searcher_title.toCaString(),
        )
        val EDITOR = WorkspaceTab(
            type = Workspace.Type.EDITOR,
            id = Workspace.Id(),
            title = R.string.editor_title.toCaString(),
        )
    }
}