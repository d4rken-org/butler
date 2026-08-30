package eu.darken.butler.workspace.ui.error

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ReportProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.ui.dialogs.AdaptiveAlertDialog
import eu.darken.butler.common.R as CommonR

/**
 * Asks before an error report leaves the app, naming what the report can contain. Adaptive because
 * the same consent is raised from workspace pages (pane-bound) and from the workspaces screen, which
 * has no pane.
 */
@Composable
fun ErrorShareConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.TwoTone.ReportProblem, contentDescription = null) },
        title = { Text(stringResource(CommonR.string.general_error_report_consent_title)) },
        text = { Text(stringResource(CommonR.string.general_error_report_consent_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(CommonR.string.general_error_report_consent_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ErrorShareConsentDialogPreview() {
    ErrorShareConsentDialog(
        onConfirm = {},
        onDismiss = {},
    )
}
