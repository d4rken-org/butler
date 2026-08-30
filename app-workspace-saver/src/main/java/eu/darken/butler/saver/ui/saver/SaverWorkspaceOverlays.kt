package eu.darken.butler.saver.ui.saver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.error.ErrorShareConsentDialog
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

/**
 * Overlay slot of the saver page.
 *
 * Shares the ViewModel with [SaverWorkspacePageHost]; the navigation handler, the share-intent
 * collector and the auto-surface effect stay there. The error handler lives here instead, because
 * it renders a dialog that has to be pane-bound.
 *
 * The conflict sheet lives here for the same reason: from the page's content slot its rename dialog
 * would be confined to the content bounds and ranked below the sheet it belongs on top of.
 */
@Composable
fun SaverWorkspaceOverlaysHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SaverWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SaverWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    val conflictUiState by vm.conflictUiState.collectAsState()
    val pendingErrorShare by vm.pendingErrorShare.collectAsState()

    val paneInsets = design.paneInsets()

    val conflictIssue = conflictUiState.issue
    if (conflictIssue != null && conflictUiState.visible) {
        IssuesBottomSheet(
            issue = conflictIssue,
            onResolution = { resolution -> vm.resolveConflict(resolution) },
            onDismiss = { vm.dismissConflictSheet() },
            topInset = paneInsets.top,
            bottomInset = paneInsets.bottom,
        )
    }

    if (pendingErrorShare != null) {
        ErrorShareConsentDialog(
            onConfirm = { vm.confirmErrorShare() },
            onDismiss = { vm.dismissErrorShare() },
        )
    }

    // Last on purpose: layers stack in composition order, so an error raised while one of
    // this page's own dialogs is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}
