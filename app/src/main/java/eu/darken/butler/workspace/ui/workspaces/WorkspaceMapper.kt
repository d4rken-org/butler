package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.visible
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.states.WorkspaceDormantContent
import eu.darken.butler.workspace.ui.states.WorkspaceErrorContent
import eu.darken.butler.workspace.ui.states.WorkspaceInitializingContent

@Composable
fun WorkspaceMapper(
    info: WorkspacePaneInfo,
    design: WorkspaceDesign,
    onShareError: (Throwable) -> Unit,
    onCloseWorkspace: () -> Unit,
    onRestoreWorkspace: () -> Unit,
) {
    val lifecycleState = info.lifecycleState

    Box(modifier = Modifier.fillMaxSize()) {
        // PageHost ALWAYS rendered - visible only when Ready
        // visible(false) = measured/laid out but not drawn = pre-warmed UI
        // Not for dormant workspaces: there is no instance behind the id yet, so the typed page
        // host and its ViewModel would cast the stand-in (and pre-warming defeats the laziness).
        if (lifecycleState !is Workspace.LifecycleState.Dormant) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .visible(lifecycleState is Workspace.LifecycleState.Ready)
            ) {
                WorkspaceContent(info = info, design = design)
            }
        }

        // Overlay content for Init/Dormant/Error states
        when (lifecycleState) {
            is Workspace.LifecycleState.Initializing -> {
                WorkspaceInitializingContent(
                    modifier = Modifier.fillMaxSize(),
                    design = design,
                    currentWorkspaceId = info.id,
                )
            }
            is Workspace.LifecycleState.Dormant -> {
                WorkspaceDormantContent(
                    modifier = Modifier.fillMaxSize(),
                    design = design,
                    type = info.type,
                    error = lifecycleState.error,
                    onRestore = onRestoreWorkspace,
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
    WorkspacePageHostDispatcher(id = info.id, type = info.type, design = design)
}