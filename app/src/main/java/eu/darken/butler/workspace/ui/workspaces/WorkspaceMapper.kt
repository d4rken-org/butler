package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.runtime.Composable
import eu.darken.butler.editor.ui.EditorWorkspacePageHost
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePageHost
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePageHost
import eu.darken.butler.templates.ui.TemplatesWorkspacePageHost
import eu.darken.butler.templates.ui.WorkspaceTab
import eu.darken.butler.workspace.core.Workspace

@Composable
 fun WorkspaceMapper(
    tab: WorkspaceTab,
) {
    when (tab.type) {
        Workspace.Type.TEMPLATES -> TemplatesWorkspacePageHost(
            id = tab.id,
        )
        Workspace.Type.EXPLORER -> ExplorerWorkspacePageHost(
            id = tab.id,
        )
        Workspace.Type.SEARCHER -> SearcherWorkspacePageHost(
            id = tab.id,
        )
        Workspace.Type.EDITOR -> EditorWorkspacePageHost(
            id = tab.id,
        )
    }
}