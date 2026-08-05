package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.common.R as CommonR

/**
 * Shown when the user cancelled a load that had nothing to fall back to, i.e. the tab has no
 * content to return to. Retry re-issues the aborted navigation, dismissing leaves the tab.
 */
@Composable
fun BrowsingAbortedDialog(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.explorer_aborted_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = { Text(stringResource(R.string.explorer_aborted_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(CommonR.string.general_retry_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_dismiss_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BrowsingAbortedDialogPreview() {
    BrowsingAbortedDialog(onRetry = {}, onDismiss = {})
}
