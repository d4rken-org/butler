package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.runtime.Composable
import eu.darken.butler.apps.ui.apps.AppsWorkspacePageHost
import eu.darken.butler.apps.ui.details.AppDetailsWorkspacePageHost
import eu.darken.butler.debug.ui.DebugWorkspacePageHost
import eu.darken.butler.editor.ui.editor.EditorWorkspacePageHost
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePageHost
import eu.darken.butler.saver.ui.saver.SaverWorkspacePageHost
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePageHost
import eu.darken.butler.templates.ui.TemplatesWorkspacePageHost
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.classic.CreatingWorkspacePlaceholder

@Composable
fun WorkspaceMapper(
    info: WorkspacePaneInfo,
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

        Workspace.Type.APPS -> AppsWorkspacePageHost(
            id = info.id,
            design = design,
        )

        Workspace.Type.APP_DETAILS -> AppDetailsWorkspacePageHost(
            id = info.id,
            design = design,
        )

        Workspace.Type.SAVER -> SaverWorkspacePageHost(
            id = info.id,
            design = design,
        )

        Workspace.Type.DEBUG -> DebugWorkspacePageHost(
            id = info.id,
            design = design,
        )
    }
}