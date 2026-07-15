package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.explorer.R

/** Formats offered for creating a new archive. */
private val COMPRESS_FORMATS = listOf(ArchiveFormat.ZIP, ArchiveFormat.TAR_GZ)

@Composable
fun CompressOptionsDialog(
    suggestedName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, format: ArchiveFormat) -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_compress_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.explorer_compress_dialog_name_label)) },
                )
                Text(
                    text = stringResource(R.string.explorer_compress_dialog_format_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                COMPRESS_FORMATS.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = format == option, onClick = { format = option }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == option, onClick = { format = option })
                        Text(text = option.displayExtension)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), format) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.explorer_compress_dialog_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CompressOptionsDialogPreview() {
    CompressOptionsDialog(
        suggestedName = "Documents",
        onDismiss = {},
        onConfirm = { _, _ -> },
    )
}
