package eu.darken.butler.explorer.ui.explorer.dialogs

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

@Composable
fun CompressOverwriteDialog(
    archiveName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_compress_dialog_overwrite_title)) },
        text = { Text(stringResource(R.string.explorer_compress_dialog_overwrite_message, archiveName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.explorer_compress_dialog_overwrite_action))
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
private fun CompressOverwriteDialogPreview() {
    CompressOverwriteDialog(
        archiveName = "backup.zip",
        onDismiss = {},
        onConfirm = {},
    )
}
