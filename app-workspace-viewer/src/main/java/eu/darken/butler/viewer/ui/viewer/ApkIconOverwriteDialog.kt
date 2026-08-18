package eu.darken.butler.viewer.ui.viewer

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.viewer.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.common.R as CommonR

/** Asked before an icon export replaces a file that is already sitting at the chosen destination. */
@Composable
fun ApkIconOverwriteDialog(
    modifier: Modifier = Modifier,
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.viewer_apk_icon_overwrite_title)) },
        text = { Text(text = stringResource(R.string.viewer_apk_icon_overwrite_message, fileName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.viewer_apk_icon_overwrite_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkIconOverwriteDialogPreview() {
    ApkIconOverwriteDialog(
        fileName = "eu.darken.butler-icon.png",
        onConfirm = {},
        onDismiss = {},
    )
}
