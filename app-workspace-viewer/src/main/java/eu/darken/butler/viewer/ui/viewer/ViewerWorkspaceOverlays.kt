package eu.darken.butler.viewer.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.openPrivacyPolicy
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.DeleteConfirmationDialog
import eu.darken.butler.workspace.ui.error.ErrorShareConsentDialog
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

/**
 * Overlay slot of the viewer page.
 *
 * Shares the ViewModel with [ViewerWorkspacePageHost]; the share-intent collector stays there. The
 * error handler lives here instead, because it renders a dialog that has to be pane-bound.
 */
@Composable
fun ViewerWorkspaceOverlaysHost(
    id: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    vm: ViewerWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: ViewerWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    val state by vm.state.collectAsState(initial = null)
    val iconPreview by vm.iconPreview.collectAsState()
    val pendingOverwrite by vm.pendingIconOverwrite.collectAsState()
    val deleteRequest by vm.deleteRequest.collectAsState()
    val trashEnabled by vm.trashEnabled.collectAsState()
    val issue by vm.issueState.collectAsState()
    val pendingErrorShare by vm.pendingErrorShare.collectAsState()

    val apkContent = (state as? ViewerWorkspaceViewModel.State.Ready)?.content as? ViewerContent.Apk

    // The preview holds a full-size bitmap, and this ViewModel outlives its composables - without
    // this the bitmap would stay resident after the tab is switched away or closed.
    DisposableEffect(vm) {
        onDispose { vm.dismissIconPreview() }
    }

    iconPreview?.let { preview ->
        ApkIconPreviewDialog(
            state = preview,
            appLabel = apkContent?.apkInfo?.let { it.label ?: it.id.name } ?: "",
            onDismiss = { vm.dismissIconPreview() },
            onSave = { vm.saveIcon() },
        )
    }

    pendingOverwrite?.let { pending ->
        ApkIconOverwriteDialog(
            fileName = pending.target.name,
            onConfirm = { vm.confirmIconOverwrite() },
            onDismiss = { vm.dismissIconOverwrite() },
        )
    }

    deleteRequest?.let { targets ->
        DeleteConfirmationDialog(
            items = targets,
            trashEnabled = trashEnabled,
            onDismiss = { vm.dismissDelete() },
            onConfirm = { _, forcePermDelete -> vm.confirmDelete(forcePermDelete) },
        )
    }

    // A delete can stop mid-way for a permission escalation or a full trash; without this the
    // operation would sit Waiting forever with nothing on screen to answer it.
    issue?.let {
        val paneInsets = design.paneInsets()
        IssuesBottomSheet(
            issue = it,
            onResolution = { resolution -> vm.resolveIssue(resolution) },
            onDismiss = { /* Clears itself once the operation is resolved or cancelled */ },
            topInset = paneInsets.top,
            bottomInset = paneInsets.bottom,
        )
    }

    if (pendingErrorShare != null) {
        val context = LocalContext.current
        ErrorShareConsentDialog(
            onConfirm = { vm.confirmErrorShare() },
            onDismiss = { vm.dismissErrorShare() },
            onPrivacyPolicy = { openPrivacyPolicy(context) },
        )
    }

    // Emitted last so an error lands on top of whatever dialog is already open.
    ErrorEventHandler(vm)
}
