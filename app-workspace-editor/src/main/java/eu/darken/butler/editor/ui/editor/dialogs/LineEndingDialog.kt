package eu.darken.butler.editor.ui.editor.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.LineEnding

@Composable
fun LineEndingDialog(
    currentLineEnding: LineEnding,
    onSelect: (LineEnding) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_line_ending_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.editor_line_ending_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                listOf(LineEnding.LF, LineEnding.CRLF).forEach { ending ->
                    val selected = ending == currentLineEnding
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, onClick = { onSelect(ending) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = when (ending) {
                                LineEnding.LF -> stringResource(R.string.editor_line_ending_lf_label)
                                else -> stringResource(R.string.editor_line_ending_crlf_label)
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
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
private fun LineEndingDialogPreview() {
    LineEndingDialog(
        currentLineEnding = LineEnding.CRLF,
        onSelect = {},
        onDismiss = {},
    )
}
