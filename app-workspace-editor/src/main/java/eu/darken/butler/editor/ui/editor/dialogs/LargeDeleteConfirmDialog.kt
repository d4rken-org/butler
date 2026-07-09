package eu.darken.butler.editor.ui.editor.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.editor.R

@Composable
fun LargeDeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_dialog_large_delete_confirm_title)) },
        text = { Text(stringResource(R.string.editor_dialog_large_delete_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.editor_dialog_large_delete_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_dialog_action_cancel))
            }
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LargeDeleteConfirmDialogPreview() {
    LargeDeleteConfirmDialog(
        onConfirm = {},
        onDismiss = {},
    )
}
