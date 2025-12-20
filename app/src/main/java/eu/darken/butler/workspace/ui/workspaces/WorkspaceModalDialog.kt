package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.darken.butler.apps.ui.apps.AppsWorkspacePageHost
import eu.darken.butler.apps.ui.details.AppDetailsWorkspacePageHost
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.developer.ui.DeveloperWorkspacePageHost
import eu.darken.butler.editor.ui.editor.EditorWorkspacePageHost
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePageHost
import eu.darken.butler.saver.ui.saver.SaverWorkspacePageHost
import eu.darken.butler.sdmaid.ui.dashboard.SdMaidWorkspacePageHost
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePageHost
import eu.darken.butler.templates.ui.TemplatesWorkspacePageHost
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

/**
 * Full-screen Dialog overlay for displaying sub-workspaces that require full-screen presentation.
 *
 * This component handles two scenarios:
 * 1. **FULL_SCREEN modals** - Always render as Dialog (pickers, settings dialogs)
 * 2. **PANE_LOCAL modals on single-pane devices** - Render as Dialog on phones (fall back from pane-local)
 *
 * On multi-pane devices (tablets), PANE_LOCAL modals render as Box overlays within their parent's
 * pane and do NOT use this Dialog component.
 *
 * @param workspace The workspace to display
 * @param design The workspace design/layout configuration from the parent screen
 * @param onDismissRequest Called when the user dismisses the dialog
 */
@Composable
fun WorkspaceModalDialog(
    workspace: Workspace.Info,
    design: WorkspaceDesign,
    onDismissRequest: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        CompositionLocalProvider(
            LocalWorkspaceFocused provides true,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                WorkspaceModalContent(
                    workspace = workspace,
                    design = design,
                )
            }
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
            ExplorerWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.SEARCHER -> {
            SearcherWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.EDITOR -> {
            EditorWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.TEMPLATES -> {
            TemplatesWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.APPS -> {
            AppsWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.APP_DETAILS -> {
            AppDetailsWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.SAVER -> {
            SaverWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.SDMAID -> {
            SdMaidWorkspacePageHost(
                id = workspace.id,
                design = design,
            )
        }
        Workspace.Type.DEVELOPER -> {
            DeveloperWorkspacePageHost(
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
        // Preview with a mock sub-workspace (picker)
        WorkspaceModalContent(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Select Folder".toCaString(),
                callerWorkspaceId = Workspace.Id(), // Mock parent workspace
            ),
        )
    }
}
