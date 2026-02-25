package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.runtime.Composable
import eu.darken.butler.apps.ui.apps.AppsWorkspacePageHost
import eu.darken.butler.apps.ui.details.AppDetailsWorkspacePageHost
import eu.darken.butler.developer.ui.DeveloperWorkspacePageHost
import eu.darken.butler.editor.ui.editor.EditorWorkspacePageHost
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePageHost
import eu.darken.butler.saver.ui.saver.SaverWorkspacePageHost
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePageHost
import eu.darken.butler.templates.ui.TemplatesWorkspacePageHost
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun WorkspacePageHostDispatcher(
    id: Workspace.Id,
    type: Workspace.Type,
    design: WorkspaceDesign,
) {
    when (type) {
        Workspace.Type.TEMPLATES -> TemplatesWorkspacePageHost(id = id, design = design)
        Workspace.Type.EXPLORER -> ExplorerWorkspacePageHost(id = id, design = design)
        Workspace.Type.SEARCHER -> SearcherWorkspacePageHost(id = id, design = design)
        Workspace.Type.EDITOR -> EditorWorkspacePageHost(id = id, design = design)
        Workspace.Type.APPS -> AppsWorkspacePageHost(id = id, design = design)
        Workspace.Type.APP_DETAILS -> AppDetailsWorkspacePageHost(id = id, design = design)
        Workspace.Type.SAVER -> SaverWorkspacePageHost(id = id, design = design)
        Workspace.Type.DEVELOPER -> DeveloperWorkspacePageHost(id = id, design = design)
    }
}
