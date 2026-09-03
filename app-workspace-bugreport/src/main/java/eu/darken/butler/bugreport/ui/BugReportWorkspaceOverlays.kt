package eu.darken.butler.bugreport.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.bugreport.R
import eu.darken.butler.bugreport.ui.BugReportWorkspaceViewModel.ActiveDialog
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.openPrivacyPolicy
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.launch

private val TAG = logTag("BugReport", "Workspace", "Overlays")

/**
 * Overlay slot of the bug report page.
 *
 * Shares the ViewModel with [BugReportWorkspacePageHost]. The error handler lives here because it
 * renders a dialog that has to be pane-bound.
 */
@Composable
fun BugReportWorkspaceOverlaysHost(
    id: Workspace.Id,
    vm: BugReportWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: BugReportWorkspaceViewModel.Factory -> factory.create(id = id) },
    ),
) {
    val overlayState by vm.overlayState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BugReportWorkspaceOverlays(
        overlayState = overlayState,
        onShareConsent = { reportId ->
            vm.dismissShareConsent()
            scope.launch {
                try {
                    val intent = vm.buildShareIntent(reportId)
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.bugreport_share_chooser_title))
                    )
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Share failed: ${e.asLog()}" }
                }
            }
        },
        onDismissShareConsent = { vm.dismissShareConsent() },
        onKeepRecording = { vm.dismissShortRecordingWarning() },
        onStopRecordingAnyway = {
            vm.dismissShortRecordingWarning()
            vm.forceStopRecording()
        },
        onConfirmDeleteAll = { vm.deleteAll() },
        onDismissDeleteAll = { vm.dismissDeleteAllConfirmation() },
        onConfirmDelete = { vm.confirmDelete() },
        onDismissDelete = { vm.dismissDeleteConfirmation() },
        onPrivacyPolicy = { openPrivacyPolicy(context) },
    )

    // Last on purpose: layers stack in composition order, so an error raised while one of
    // this page's own dialogs is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}

@Composable
fun BugReportWorkspaceOverlays(
    overlayState: BugReportWorkspaceViewModel.OverlayState,
    onShareConsent: (String) -> Unit = {},
    onDismissShareConsent: () -> Unit = {},
    onKeepRecording: () -> Unit = {},
    onStopRecordingAnyway: () -> Unit = {},
    onConfirmDeleteAll: () -> Unit = {},
    onDismissDeleteAll: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onPrivacyPolicy: () -> Unit = {},
) {
    when (val dialog = overlayState.activeDialog) {
        is ActiveDialog.ShareConsent -> ShareConsentDialog(
            onConfirm = { onShareConsent(dialog.reportId) },
            onDismiss = onDismissShareConsent,
            onPrivacyPolicy = onPrivacyPolicy,
        )

        ActiveDialog.ShortRecordingWarning -> ShortRecordingWarningDialog(
            onKeepRecording = onKeepRecording,
            onStopAnyway = onStopRecordingAnyway,
        )

        ActiveDialog.DeleteAllConfirmation -> DeleteAllConfirmationDialog(
            onConfirm = onConfirmDeleteAll,
            onDismiss = onDismissDeleteAll,
        )

        is ActiveDialog.DeleteConfirmation -> DeleteReportConfirmationDialog(
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDelete,
        )

        null -> Unit
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BugReportWorkspaceOverlaysShareConsentPreview() {
    BugReportWorkspaceOverlays(
        overlayState = BugReportWorkspaceViewModel.OverlayState(ActiveDialog.ShareConsent("report-1")),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BugReportWorkspaceOverlaysShortRecordingPreview() {
    BugReportWorkspaceOverlays(
        overlayState = BugReportWorkspaceViewModel.OverlayState(ActiveDialog.ShortRecordingWarning),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BugReportWorkspaceOverlaysDeleteAllPreview() {
    BugReportWorkspaceOverlays(
        overlayState = BugReportWorkspaceViewModel.OverlayState(ActiveDialog.DeleteAllConfirmation),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BugReportWorkspaceOverlaysDeletePreview() {
    BugReportWorkspaceOverlays(
        overlayState = BugReportWorkspaceViewModel.OverlayState(ActiveDialog.DeleteConfirmation("report-1")),
    )
}
