package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import eu.darken.butler.workspace.ui.modal.LocalWorkspaceIsPaneModal
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler

/**
 * Everything that can occupy a single workspace pane, stacked bottom to top.
 *
 * The pane's own workspace forms tier 0 — content (incl. its banner), overlays, manager dialog —
 * and every entry of [childModals] adds one tier on top of it, nearest-tab-first. The stack is
 * therefore as deep as the modal chain, not capped at one child.
 *
 * @param paneFocused whether this pane is the focused one — true for any occupant. A pane-local
 *        modal's id can itself be the globally focused one (`createAndFocus`, tab-manager
 *        selection), so callers resolve focus through the chain's owning tab, not this pane's id.
 * @param clickToFocus whether an unfocused pane has to be clicked once before it reacts to
 *        anything: the user's "Click to focus" setting, forwarded to [PaneLayerHost].
 * @param backActive whether system Back may be dispatched into this pane. Defaults to
 *        [paneFocused]; a pager-driven layout narrows it, because its focused pane can be scrolled
 *        off screen while focus stays put.
 * @param activeWorkspaceId the workspace the user is actually talking to: the deepest occupant of
 *        a focused pane, or null while the pane is unfocused. Deliberately *not* compared against
 *        the globally focused id — a picker opened via `launchPicker` never becomes the focused
 *        workspace, so that comparison would mark the covered launcher focused and the visible
 *        picker unfocused. Local activeness follows stack position; global focus only picks the
 *        pane and the branch.
 * @param onRequestPaneFocus must always request focus for the pane's *parent* workspace, including
 *        from a child modal's layers: a focus request for a pane-local modal is silently dropped,
 *        which would leave a different pane active while the user interacts with this one.
 */
@Composable
fun WorkspacePane(
    modifier: Modifier = Modifier,
    info: WorkspacePaneInfo,
    design: WorkspaceDesign,
    paneFocused: Boolean,
    clickToFocus: Boolean = true,
    backActive: Boolean = paneFocused,
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
    childModals: List<WorkspacePaneInfo> = emptyList(),
    activeWorkspaceId: Workspace.Id? = null,
) {
    // Provided above the host so the host's own press observer can reach it: any press in the pane
    // must make it the focused pane, including presses that the content consumes. While the pane is
    // not the focused one the host consumes presses itself, so the first click into an unfocused
    // pane focuses it without activating content.
    CompositionLocalProvider(LocalWorkspaceFocusRequest provides onRequestPaneFocus) {
        PaneLayerHost(
            // Full pane on purpose: its layers carry the scrims and pointer barriers, which must
            // keep covering the inset strip next to a side navigation bar or a cutout. The insets
            // are applied to the content and the modal surfaces inside instead.
            modifier = modifier,
            paneFocused = paneFocused,
            clickToFocus = clickToFocus,
            backActive = backActive,
            paneEdges = paneEdges,
        ) {
            CompositionLocalProvider(
                LocalWorkspaceFocused provides (activeWorkspaceId == info.id),
            ) {
                WorkspaceLayers(
                    info = info,
                    design = design,
                    depth = 0,
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

            childModals.forEachIndexed { index, child ->
                key(child.id) {
                    CompositionLocalProvider(
                        LocalWorkspaceFocused provides (activeWorkspaceId == child.id),
                    ) {
                        WorkspaceLayers(
                            info = child,
                            design = design,
                            depth = index + 1,
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
    depth: Int,
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
    // Provided around all three sections: a page can only learn "am I a pane-local modal" from its
    // own state flow, which is empty on the first frames. Anything that must be right immediately
    // (back handling above all) reads this instead.
    CompositionLocalProvider(LocalWorkspaceIsPaneModal provides contentIsModal) {
        PaneLayer(
            modifier = Modifier.fillMaxSize(),
            rank = PaneLayerRank.contentAt(depth),
            modal = contentIsModal,
        ) {
            // Composed BEFORE the page, so any handler the page installs outranks this one in
            // BackHandler's LIFO order. It exists because "the page always handles back" does not
            // hold at every moment: App Details registers nothing until its state emits, the
            // Explorer nothing until its picker state exists, the Viewer reads its caller as null
            // on the first frames, and a paused workspace composes no typed page host at all.
            // Emits no layout, so it does not disturb the surface below.
            WorkspaceBackHandler(enabled = contentIsModal) { onCloseWorkspace(info.id) }

            Box(
                modifier = if (contentIsModal) {
                    Modifier
                        .fillMaxSize()
                        // A stacked child covers its parent, so it owes the parent an opaque
                        // surface across the FULL pane - inset strips next to a cutout or side
                        // navigation bar included - and a hit-test boundary, or a transparent
                        // region (initializing/paused placeholder, letterboxed image) would let
                        // taps land on the workspace underneath.
                        .background(MaterialTheme.colorScheme.surface)
                        // Hit-testable but consuming nothing: the covered layer is a sibling and
                        // therefore stops receiving events, while the pager is an ancestor and
                        // keeps seeing the down and the horizontal motion. Consuming the down
                        // would also block it, and swiping between tabs would die here.
                        .pointerInput(Unit) {}
                } else {
                    Modifier.fillMaxSize()
                },
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
        }

        // Overlays instantiate the page's ViewModel, so they must not be composed while the workspace
        // is Paused: there is no live instance behind the id and the typed page host would cast the
        // stand-in. Every other state composes them, matching the content layer — the error handler
        // lives in this slot, so gating on Ready would swallow anything raised during initialization.
        if (info.lifecycleState !is Workspace.LifecycleState.Paused) {
            LocalWorkspacePageHosts.current[info.type]?.let { entry ->
                CompositionLocalProvider(LocalPaneLayerRank provides PaneLayerRank.overlayAt(depth)) {
                    entry.Overlays(id = info.id, design = design)
                }
            }
        }

        // Deliberately outside the lifecycle gate: a close confirmation for a paused workspace must
        // still appear.
        managerDialogStates[info.id]?.let { dialog ->
            PaneLayer(modifier = Modifier.fillMaxSize(), rank = PaneLayerRank.managerAt(depth)) {
                ManagerDialogHost(
                    dialog = dialog,
                    onDismiss = { onDismissManagerDialog(it.targetWorkspaceId) },
                    onConfirm = onConfirmManagerDialog,
                )
            }
        }
    }
}
