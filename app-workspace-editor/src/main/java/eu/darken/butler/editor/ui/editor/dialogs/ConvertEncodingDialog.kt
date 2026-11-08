package eu.darken.butler.editor.ui.editor.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.EditorCharsets
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog

/** Picks the charset to re-encode the file into; the current one isn't offered. */
@Composable
fun ConvertEncodingDialog(
    modifier: Modifier = Modifier,
    currentEncoding: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = EditorCharsets.resolve(currentEncoding)
    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_convert_encoding_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.editor_convert_encoding_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                EditorCharsets.allowlist.filter { it != current }.forEach { charset ->
                    val name = charset.name()
                    Text(
                        text = name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(name) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
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
private fun ConvertEncodingDialogPreview() {
    ConvertEncodingDialog(
        currentEncoding = "GB18030",
        onSelect = {},
        onDismiss = {},
    )
}
