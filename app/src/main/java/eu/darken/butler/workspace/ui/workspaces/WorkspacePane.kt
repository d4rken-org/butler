package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspaceOverlayContainer
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.ManagerDialogHost
import eu.darken.butler.workspace.ui.feedback.BannerState
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalPaneLayerRank
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank

/**
 * Everything that can occupy a single workspace pane, stacked bottom to top:
 *
 * 1. parent workspace content (incl. its banner)
 * 2. parent workspace overlays
 * 3. manager dialog for the parent workspace
 * 4. pane-local child modal content, if present
 * 5. child modal overlays
 * 6. manager dialog for the child
 *
 * @param paneFocused whether this pane is the focused one — true for either occupant, because a
 *        pane-local child modal's id can never become the globally focused workspace id.
 * @param onRequestPaneFocus must always request focus for the pane's *parent* workspace, including
 *        from the child modal's layers: a focus request for a pane-local modal is silently dropped,
 *        which would leave a different pane active while the user interacts with this one.
 */
@Composable
fun WorkspacePane(
    modifier: Modifier = Modifier,
    info: WorkspacePaneInfo,
    design: WorkspaceDesign,
    paneFocused: Boolean,
    workspaceFocused: Boolean,
    onRequestPaneFocus: () -> Unit,
    managerDialogStates: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    onDismissManagerDialog: (Workspace.Id) -> Unit,
    onConfirmManagerDialog: (ManagerDialog.WorkspaceTargeted) -> Unit,
    bannerStates: Map<Workspace.Id, BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
    onShareError: (Workspace.Id, Throwable) -> Unit,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onResumeWorkspace: (Workspace.Id) -> Unit,
    paneEdges: WorkspaceDesign.PaneEdges = WorkspaceDesign.PaneEdges.All,
    childModal: WorkspacePaneInfo? = null,
    childWorkspaceFocused: Boolean = false,
) {
    // Provided above the host so the host's own press observer can reach it: any press in the pane
    // must make it the focused pane, including presses that the content consumes.
    CompositionLocalProvider(LocalWorkspaceFocusRequest provides onRequestPaneFocus) {
        PaneLayerHost(
            // Full pane on purpose: its layers carry the scrims and pointer barriers, which must
            // keep covering the inset strip next to a side navigation bar or a cutout. The insets
            // are applied to the content and the modal surfaces inside instead.
            modifier = modifier,
            paneFocused = paneFocused,
            paneEdges = paneEdges,
        ) {
            CompositionLocalProvider(
                LocalWorkspaceFocused provides workspaceFocused,
            ) {
                WorkspaceLayers(
                    info = info,
                    design = design,
                    contentRank = PaneLayerRank.CONTENT,
                    overlayRank = PaneLayerRank.OVERLAY,
                    managerRank = PaneLayerRank.MANAGER,
                    contentIsModal = false,
                    managerDialogStates = managerDialogStates,
                    onDismissManagerDialog = onDismissManagerDialog,
                    onConfirmManagerDialog = onConfirmManagerDialog,
                    bannerStates = bannerStates,
                    onDismissBanner = onDismissBanner,
                    onShareError = onShareError,
                    onCloseWorkspace = onCloseWorkspace,
                    onResumeWorkspace = onResumeWorkspace,
                    paneEdges = paneEdges,
                )
            }

            childModal?.let { child ->
                key(child.id) {
                    CompositionLocalProvider(
                        LocalWorkspaceFocused provides childWorkspaceFocused,
                    ) {
                        WorkspaceLayers(
                            info = child,
                            design = design,
                            contentRank = PaneLayerRank.CHILD_CONTENT,
                            overlayRank = PaneLayerRank.CHILD_OVERLAY,
                            managerRank = PaneLayerRank.CHILD_MANAGER,
                            contentIsModal = true,
                            managerDialogStates = managerDialogStates,
                            onDismissManagerDialog = onDismissManagerDialog,
                            onConfirmManagerDialog = onConfirmManagerDialog,
                            bannerStates = bannerStates,
                            onDismissBanner = onDismissBanner,
                            onShareError = onShareError,
                            onCloseWorkspace = onCloseWorkspace,
                            onResumeWorkspace = onResumeWorkspace,
                            paneEdges = paneEdges,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.WorkspaceLayers(
    info: WorkspacePaneInfo,
    design: WorkspaceDesign,
    contentRank: Int,
    overlayRank: Int,
    managerRank: Int,
    contentIsModal: Boolean,
    managerDialogStates: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    onDismissManagerDialog: (Workspace.Id) -> Unit,
    onConfirmManagerDialog: (ManagerDialog.WorkspaceTargeted) -> Unit,
    bannerStates: Map<Workspace.Id, BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
    onShareError: (Workspace.Id, Throwable) -> Unit,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onResumeWorkspace: (Workspace.Id) -> Unit,
    paneEdges: WorkspaceDesign.PaneEdges,
) {
    PaneLayer(
        modifier = Modifier.fillMaxSize(),
        rank = contentRank,
        modal = contentIsModal,
    ) {
        WorkspaceOverlayContainer(
            // Page content and banner are what the user reads, so they get the horizontal insets
            modifier = Modifier.paneHorizontalInsetPadding(paneEdges),
            workspaceId = info.id,
            bannerStates = bannerStates,
            onDismissBanner = onDismissBanner,
            paneEdges = paneEdges,
        ) {
            WorkspaceMapper(
                info = info,
                design = design,
                onShareError = { error -> onShareError(info.id, error) },
                onCloseWorkspace = { onCloseWorkspace(info.id) },
                onResumeWorkspace = { onResumeWorkspace(info.id) },
            )
        }
    }

    // Overlays instantiate the page's ViewModel, so they must not be composed while the workspace
    // is anything but Ready: a Paused workspace has no instance behind its id yet and the typed
    // page host would cast the stand-in. Pre-warming them (as the content layer does) is pointless
    // — a non-Ready workspace has no dialogs to show.
    if (info.lifecycleState is Workspace.LifecycleState.Ready) {
        LocalWorkspacePageHosts.current[info.type]?.let { entry ->
            CompositionLocalProvider(LocalPaneLayerRank provides overlayRank) {
                entry.Overlays(id = info.id, design = design)
            }
        }
    }

    // Deliberately outside the lifecycle gate: a close confirmation for a paused workspace must
    // still appear.
    managerDialogStates[info.id]?.let { dialog ->
        PaneLayer(modifier = Modifier.fillMaxSize(), rank = managerRank) {
            ManagerDialogHost(
                dialog = dialog,
                onDismiss = { onDismissManagerDialog(it.targetWorkspaceId) },
                onConfirm = onConfirmManagerDialog,
            )
        }
    }
}
