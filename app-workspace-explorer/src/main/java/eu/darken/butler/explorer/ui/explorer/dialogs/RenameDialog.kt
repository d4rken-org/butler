package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.explorer.R
import eu.darken.butler.common.R as CommonR

data class RenameResult(
    val item: APath<*>,
    val newName: String,
)

@Composable
fun RenameDialog(
    item: APath<*>,
    currentName: String,
    onValidate: (String) -> FilenameValidator.ValidationResult = { FilenameValidator.ValidationResult.Valid },
    onDismiss: () -> Unit,
    onConfirm: (RenameResult) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    // Pre-select the filename without extension for easier renaming
    val initialSelection = if (currentName.contains('.')) {
        TextRange(0, currentName.lastIndexOf('.'))
    } else {
        TextRange(0, currentName.length)
    }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(currentName, initialSelection))
    }

    val validation = remember(textFieldValue.text) { onValidate(textFieldValue.text) }
    val isError = validation is FilenameValidator.ValidationResult.Invalid

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.explorer_dialog_rename_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
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
                            val chars = (validation as FilenameValidator.ValidationResult.Invalid).invalidChars.joinToString(" ")
                            Text(stringResource(CommonR.string.general_filename_validation_error, chars))
                        }
                    } else null,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newName = textFieldValue.text.trim()
                    if (newName.isNotBlank() && newName != currentName && !isError) {
                        onConfirm(RenameResult(item, newName))
                    }
                },
                enabled = textFieldValue.text.trim().isNotBlank() &&
                         textFieldValue.text.trim() != currentName &&
                         !isError
            ) {
                Text(stringResource(CommonR.string.general_rename_action))
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
@Composable
fun RenameDialogPreview() {
    PreviewWrapper {
        RenameDialog(
            item = eu.darken.butler.common.files.LocalPath.build("/test/file.txt"),
            currentName = "file.txt",
            onDismiss = {},
            onConfirm = {}
        )
    }
}