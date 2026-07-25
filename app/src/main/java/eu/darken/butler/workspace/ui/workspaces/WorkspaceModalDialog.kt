package eu.darken.butler.workspace.ui.workspaces

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
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
        // A Dialog window is not created with the Activity's enableEdgeToEdge() setup. In particular
        // it lacks FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS, so the platform draws opaque system-bar scrims
        // and ignores transparent bar colors / light-icon requests, and it dims + insets its content
        // — leaving black strips behind the status and navigation bars. Reconcile the window with the
        // Activity so this full-screen modal is genuinely edge-to-edge.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            dialogWindow.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            // A full-screen modal shouldn't dim behind itself; the dim would show as strips at the bars.
            dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow.setDimAmount(0f)
            dialogWindow.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            dialogWindow.statusBarColor = AndroidColor.TRANSPARENT
            dialogWindow.navigationBarColor = AndroidColor.TRANSPARENT
        }

        // Match the bar icon appearance to the modal content (which paints `surface`), mirroring
        // ButlerTheme's SideEffect for the Activity window. Re-runs if the theme changes while open.
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            if (dialogWindow != null) {
                val insetsController = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
                insetsController.isAppearanceLightStatusBars = lightBars
                insetsController.isAppearanceLightNavigationBars = lightBars
            }
        }

        CompositionLocalProvider(
            LocalWorkspaceFocused provides true,
        ) {
            // The dialog window is transparent (see above), so the surface has to be painted across
            // the FULL area - the horizontal inset padding is applied inside it, otherwise the
            // inset-width strip next to a side navigation bar would show the workspace underneath.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .paneHorizontalInsetPadding(design.paneEdges),
                ) {
                    WorkspaceModalContent(
                        workspace = workspace,
                        design = design,
                    )
                }
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
