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
import androidx.compose.runtime.key
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
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.ManagerDialogHost
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalPaneLayerRank
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank

/**
 * Full-screen Dialog overlay for sub-workspaces that ask for FULL_SCREEN presentation, plus
 * anything stacked on one of those. See [Workspace.ModalPresentationMode].
 *
 * PANE_LOCAL modals never reach this component at any pane count - they stack inside the pane their
 * caller occupies, which on a single-pane layout is that tab's pager page.
 *
 * @param workspace The workspace to display
 * @param design The workspace design/layout configuration from the parent screen
 * @param managerDialog The manager-level dialog anchored to [workspace], if any
 * @param onScreenAction Reports what [managerDialog] resolves to
 * @param onDismissRequest Called when the user dismisses the dialog
 * @param onShareError Shares the failure of a workspace that could not initialize
 * @param onCloseWorkspace Closes this workspace, offered by the error placeholder
 * @param onResumeWorkspace Resumes this workspace, offered by the paused placeholder
 */
@Composable
fun WorkspaceModalDialog(
    workspace: Workspace.Info,
    design: WorkspaceDesign,
    managerDialog: ManagerDialog.WorkspaceTargeted? = null,
    onScreenAction: (WorkspaceScreenAction) -> Unit = {},
    onDismissRequest: () -> Unit,
    onShareError: (Throwable) -> Unit = {},
    onCloseWorkspace: () -> Unit = {},
    onResumeWorkspace: () -> Unit = {},
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
            // the FULL area - the horizontal inset padding is applied to the content inside,
            // otherwise the inset-width strip next to a side navigation bar would show the
            // workspace underneath.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                WorkspaceModalContent(
                    workspace = workspace,
                    design = design,
                    managerDialog = managerDialog,
                    onScreenAction = onScreenAction,
                    onShareError = onShareError,
                    onCloseWorkspace = onCloseWorkspace,
                    onResumeWorkspace = onResumeWorkspace,
                )
            }
        }
    }
}

/**
 * Content for modal workspace - extracted for previewability
 *
 * A full-screen sub-workspace occupies its own window, so it needs its own layer stack: without one
 * its page host's [eu.darken.butler.workspace.ui.WorkspacePageHostEntry.Overlays] slot would never
 * be composed and the workspace would render without any of its dialogs.
 *
 * The page goes through [WorkspaceMapper] rather than straight to the page host, for the same reason
 * a pane does: a modal can be paused now (a tab is released together with its opted-in children), and
 * a paused id has no instance behind it - its typed ViewModel would wait on `retrieve()` forever.
 */
@Composable
fun WorkspaceModalContent(
    workspace: Workspace.Info,
    design: WorkspaceDesign = WorkspaceDesign(),
    managerDialog: ManagerDialog.WorkspaceTargeted? = null,
    onScreenAction: (WorkspaceScreenAction) -> Unit = {},
    onShareError: (Throwable) -> Unit = {},
    onCloseWorkspace: () -> Unit = {},
    onResumeWorkspace: () -> Unit = {},
) {
    // One workspace per subtree. This is rendered from a single call site while the workspace
    // flowing through it changes - unwinding a modal chain swaps the deepest modal for its parent -
    // so without an identity composition treats the whole chain as one continuous subtree. Two
    // things then carry over, and they carry over differently:
    //
    // - Page state: a page host reaches its page through one virtual call, so only workspaces of the
    //   SAME type land in the same slots. Differing types emit different slots and replace the page
    //   on their own.
    // - The layer stack: [PaneLayerHost] sits above the page, so a shared one outlives EVERY swap,
    //   including the type changes an unwind is made of. Back dispatch, focus containment and
    //   accessibility all read from it, and it should describe one workspace, not a chain.
    //
    // Deliberately inside the dialog rather than around it: keying the Dialog would tear down and
    // rebuild the window on every step of an unwind, re-running its edge-to-edge reconciliation and
    // handing back dispatch to a window that did not exist a frame earlier.
    key(workspace.id) {
        // Full size, like a pane host anywhere else: its layers carry the scrims of any dialog
        // inside, and those have to cover the whole window. Only the page content is inset.
        PaneLayerHost(
            modifier = Modifier.fillMaxSize(),
            paneFocused = true,
            paneEdges = design.paneEdges,
        ) {
            PaneLayer(modifier = Modifier.fillMaxSize(), modal = false) {
                Box(modifier = Modifier.paneHorizontalInsetPadding(design.paneEdges)) {
                    // The state placeholders offer a tab-manager button on single-pane layouts, which
                    // is meaningless inside a modal window: it would open the manager behind the
                    // dialog. Suppressed by taking the provider away instead of by faking
                    // design.isSingle, which would perturb layout and insets.
                    CompositionLocalProvider(LocalWorkspaceButtonProvider provides null) {
                        WorkspaceMapper(
                            info = workspace.asPaneInfo(),
                            design = design,
                            onShareError = onShareError,
                            onCloseWorkspace = onCloseWorkspace,
                            onResumeWorkspace = onResumeWorkspace,
                        )
                    }
                }
            }

            // Same gate as a pane: everything but Paused, so an error raised while the workspace is
            // still initializing still reaches the user through the handler in the overlay slot.
            if (workspace.lifecycleState !is Workspace.LifecycleState.Paused) {
                LocalWorkspacePageHosts.current[workspace.type]?.let { entry ->
                    CompositionLocalProvider(LocalPaneLayerRank provides PaneLayerRank.OVERLAY) {
                        entry.Overlays(id = workspace.id, design = design)
                    }
                }
            }

            // Depth zero: this window renders one leaf workspace, so there is a single tier of
            // layers and this is its top one. Outside the lifecycle gate like a pane's, a close
            // confirmation for a paused workspace must still be answerable.
            managerDialog?.let { dialog ->
                PaneLayer(modifier = Modifier.fillMaxSize(), rank = PaneLayerRank.managerAt(0)) {
                    ManagerDialogHost(
                        dialog = dialog,
                        onAction = { onScreenAction(WorkspaceScreenAction.HandleDialog(it)) },
                    )
                }
            }
        }
    }
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
                lifecycleState = Workspace.LifecycleState.Ready,
                callerWorkspaceId = Workspace.Id(), // Mock parent workspace
            ),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceModalContentPausedPreview() {
    CompositionLocalProvider(LocalWorkspacePageHosts provides emptyMap()) {
        WorkspaceModalContent(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.APP_DETAILS,
                title = "Butler".toCaString(),
                subtitle = "eu.darken.butler".toCaString(),
                lifecycleState = Workspace.LifecycleState.Paused(),
                callerWorkspaceId = Workspace.Id(),
            ),
        )
    }
}
