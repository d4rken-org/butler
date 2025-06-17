package eu.darken.butler.workspace.ui

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.editor.ui.EditorWorkspaceTemplate
import eu.darken.butler.explorer.ui.ExplorerWorkspaceTemplate
import eu.darken.butler.searcher.ui.SearcherWorkspaceTemplate
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.templates.WorkspaceTemplate

sealed interface WorkspaceTab {
    val id: Workspace.Id
    val title: CaString
    val type: Workspace.Type

    data class Templates(
        override val id: Workspace.Id = Workspace.Id(),
        override val title: CaString = caString("New Workspace"),
        val templates: List<WorkspaceTemplate> = listOf(
            ExplorerWorkspaceTemplate(),
            SearcherWorkspaceTemplate(),
            EditorWorkspaceTemplate(),
        ),
    ) : WorkspaceTab {
        override val type: Workspace.Type = Workspace.Type.TEMPLATES
    }

    data class Explorer(
        override val id: Workspace.Id = Workspace.Id(),
        override val title: CaString = caString("Explorer"),
    ) : WorkspaceTab {
        override val type: Workspace.Type = Workspace.Type.EXPLORER
    }

    data class Searcher(
        override val id: Workspace.Id = Workspace.Id(),
        override val title: CaString = caString("Searcher"),
    ) : WorkspaceTab {
        override val type: Workspace.Type = Workspace.Type.SEARCHER
    }

    data class Editor(
        override val id: Workspace.Id = Workspace.Id(),
        override val title: CaString = caString("Editor"),
    ) : WorkspaceTab {
        override val type: Workspace.Type = Workspace.Type.EDITOR
    }
}