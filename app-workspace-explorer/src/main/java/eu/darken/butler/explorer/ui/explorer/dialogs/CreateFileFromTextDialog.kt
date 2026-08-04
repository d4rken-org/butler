package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.common.R as CommonR

@Composable
fun CreateFileFromTextDialog(
    clip: ClipboardClip.Text,
    onValidate: (String) -> FilenameValidator.ValidationResult = { FilenameValidator.ValidationResult.Valid },
    onDismiss: () -> Unit,
    onConfirm: (ClipboardClip.Text, String) -> Unit,
) {
    val defaultFilename = clip.sourcePath?.name?.let { name ->
        val extension = name.substringAfterLast('.', "")
        if (extension.isNotEmpty()) "snippet.$extension" else "snippet.txt"
    } ?: "snippet.txt"

    var textFieldValue by remember {
        val nameWithoutExtension = defaultFilename.substringBeforeLast('.')
        mutableStateOf(
            TextFieldValue(
                text = defaultFilename,
                selection = TextRange(0, nameWithoutExtension.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    val validation = remember(textFieldValue.text) { onValidate(textFieldValue.text) }
    val isError = validation is FilenameValidator.ValidationResult.Invalid
    val trimmedName = remember(textFieldValue.text) { textFieldValue.text.trim() }

    // Only pull focus (and with it the keyboard) while this dialog is the layer the user is
    // actually talking to — otherwise it steals input from whatever is on top of it.
    val layerActive = LocalLayerActive.current
    LaunchedEffect(layerActive) {
        if (layerActive) focusRequester.requestFocus()
    }

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        includeImePadding = true,
        title = {
            Text(
                text = stringResource(R.string.explorer_dialog_create_text_file_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    label = { Text(stringResource(R.string.explorer_dialog_name_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        {
                            val chars = validation.invalidChars.joinToString(" ")
                            Text(stringResource(CommonR.string.general_filename_validation_error, chars))
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (trimmedName.isNotBlank() && !isError) {
                                onConfirm(clip, trimmedName)
                            }
                        }
                    ),
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.explorer_dialog_create_text_file_preview_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = clip.preview,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 80.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.explorer_dialog_create_text_file_size_label,
                            clip.content.length
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (trimmedName.isNotBlank() && !isError) {
                        onConfirm(clip, trimmedName)
                    }
                },
                enabled = trimmedName.isNotBlank() && !isError
            ) {
                Text(stringResource(CommonR.string.general_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CreateFileFromTextDialogPreview() {
    CreateFileFromTextDialog(
        clip = ClipboardClip.Text(
            origin = eu.darken.butler.workspace.core.Workspace.Id(),
            content = "Hello World!\nThis is a sample text snippet that will be saved to a file.",
        ),
        onDismiss = {},
        onConfirm = { _, _ -> }
    )
}
