package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
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
            Box(modifier = Modifier.fillMaxSize()) {
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
    WorkspacePageHostDispatcher(id = workspace.id, type = workspace.type, design = design)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceModalContentPreview() {
    // No page host map available in previews; provide an empty one so the dispatcher renders its
    // error fallback instead of tripping the now-required LocalWorkspacePageHosts default.
    CompositionLocalProvider(LocalWorkspacePageHosts provides emptyMap()) {
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
