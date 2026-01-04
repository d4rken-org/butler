package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.visible
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.apps.ui.apps.AppsWorkspacePageHost
import eu.darken.butler.apps.ui.details.AppDetailsWorkspacePageHost
import eu.darken.butler.developer.ui.DeveloperWorkspacePageHost
import eu.darken.butler.editor.ui.editor.EditorWorkspacePageHost
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePageHost
import eu.darken.butler.saver.ui.saver.SaverWorkspacePageHost
import eu.darken.butler.sdmaid.ui.dashboard.SdMaidWorkspacePageHost
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePageHost
import eu.darken.butler.templates.ui.TemplatesWorkspacePageHost
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.states.WorkspaceErrorContent
import eu.darken.butler.workspace.ui.states.WorkspaceInitializingContent

@Composable
fun WorkspaceMapper(
    info: WorkspacePaneInfo,
    design: WorkspaceDesign,
    onShareError: (Throwable) -> Unit,
    onCloseWorkspace: () -> Unit,
) {
    val lifecycleState = info.lifecycleState

    Box(modifier = Modifier.fillMaxSize()) {
        // PageHost ALWAYS rendered - visible only when Ready
        // visible(false) = measured/laid out but not drawn = pre-warmed UI
        Box(
            modifier = Modifier
                .fillMaxSize()
                .visible(lifecycleState is Workspace.LifecycleState.Ready)
        ) {
            WorkspaceContent(info = info, design = design)
        }

        // Overlay content for Init/Error states
        when (lifecycleState) {
            is Workspace.LifecycleState.Initializing -> {
                WorkspaceInitializingContent(
                    modifier = Modifier.fillMaxSize(),
                    design = design,
                    currentWorkspaceId = info.id,
                )
            }
            is Workspace.LifecycleState.Error -> {
                WorkspaceErrorContent(
                    modifier = Modifier.fillMaxSize(),
                    design = design,
                    error = lifecycleState.error,
                    onShareError = { onShareError(lifecycleState.error) },
                    onCloseWorkspace = onCloseWorkspace,
                    currentWorkspaceId = info.id,
                )
            }
            is Workspace.LifecycleState.Ready -> {
                // Page is visible - no overlay needed
            }
        }
    }
}

@Composable
private fun WorkspaceContent(
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

        Workspace.Type.SDMAID -> SdMaidWorkspacePageHost(
            id = info.id,
            design = design,
        )

        Workspace.Type.DEVELOPER -> DeveloperWorkspacePageHost(
            id = info.id,
            design = design,
        )
    }
}