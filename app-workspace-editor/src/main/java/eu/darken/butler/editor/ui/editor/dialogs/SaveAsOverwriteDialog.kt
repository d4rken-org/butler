package eu.darken.butler.editor.ui.editor.dialogs

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog

@Composable
fun SaveAsOverwriteDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_dialog_save_as_overwrite_title)) },
        text = { Text(stringResource(R.string.editor_dialog_save_as_overwrite_message, fileName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.editor_dialog_save_as_overwrite_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_dialog_action_cancel))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaveAsOverwriteDialogPreview() {
    SaveAsOverwriteDialog(
        fileName = "notes.txt",
        onConfirm = {},
        onDismiss = {},
    )
}
