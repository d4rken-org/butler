package eu.darken.butler.editor.ui.editor.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R

@Composable
fun GoToLineDialog(
    totalLines: Int,
    onGoToLine: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var lineNumber by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_dialog_go_to_line_title)) },
        text = {
            OutlinedTextField(
                value = lineNumber,
                onValueChange = { lineNumber = it.filter { char -> char.isDigit() } },
                label = { Text(stringResource(R.string.editor_dialog_go_to_line_label, totalLines)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    lineNumber.toIntOrNull()?.let { line ->
                        if (line in 1..totalLines) {
                            onGoToLine(line - 1) // Convert to 0-based index
                        }
                    }
                },
                enabled = lineNumber.toIntOrNull()?.let { it in 1..totalLines } == true
            ) {
                Text(stringResource(R.string.editor_dialog_action_go))
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
private fun GoToLineDialogPreview() {
    GoToLineDialog(
        totalLines = 1000,
        onGoToLine = {},
        onDismiss = {},
    )
}
