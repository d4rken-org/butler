package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.PresentationMode
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

/**
 * Full-screen modal dialog for displaying picker workspaces
 */
@Composable
fun WorkspaceModalDialog(
    workspace: Workspace.Info,
    onDismissRequest: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            WorkspaceModalContent(
                workspace = workspace,
                design = WorkspaceDesign(),
            )
        }
    }
}

/**
 * Content for modal workspace - extracted for previewability
 */
@Composable
fun WorkspaceModalContent(
    workspace: Workspace.Info,
    design: WorkspaceDesign = WorkspaceDesign(),
) {
    // Render workspace based on type
    when (workspace.type) {
        Workspace.Type.EXPLORER -> {
            eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.SEARCHER -> {
            eu.darken.butler.searcher.ui.search.SearcherWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.EDITOR -> {
            eu.darken.butler.editor.ui.editor.EditorWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.TEMPLATES -> {
            eu.darken.butler.templates.ui.TemplatesWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
    }
}

@Preview2
@Composable
private fun WorkspaceModalContentPreview() {
    PreviewWrapper {
        // Preview with a mock workspace
        WorkspaceModalContent(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Select Folder".toCaString(),
                presentationMode = PresentationMode.MODAL,
            ),
        )
    }
}
