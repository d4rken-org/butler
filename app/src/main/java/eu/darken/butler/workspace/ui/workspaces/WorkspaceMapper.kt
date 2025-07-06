package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.editor.ui.EditorWorkspacePageHost
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePageHost
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePageHost
import eu.darken.butler.templates.ui.TemplatesWorkspacePageHost
import eu.darken.butler.templates.ui.WorkspaceTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.empty.EmptyWorkspaceContent

@Composable
fun WorkspaceMapper(
    info: Workspace.Info,
    design: WorkspaceDesign,
) {
    when (info.type) {
        Workspace.Type.TEMPLATES -> TemplatesWorkspacePageHost(
            id = info.id,
            design = design,
        )

        Workspace.Type.EXPLORER -> ExplorerWorkspacePageHost(
            id = info.id,
            design = design,
        )

        Workspace.Type.SEARCHER -> SearcherWorkspacePageHost(
            id = info.id,
            design = design,
        )

        Workspace.Type.EDITOR -> EditorWorkspacePageHost(
            id = info.id,
            design = design,
        )
    }
}