package eu.darken.butler.explorer.ui.explorer.issues

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
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.explorer.R

@Composable
fun PathIssueRenameDialog(
    currentName: String,
    initialValue: String? = null,
    onValidate: (String) -> FilenameValidator.ValidationResult = { FilenameValidator.ValidationResult.Valid },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    dialogTitle: String = stringResource(R.string.explorer_issue_common_rename),
) {
    var newName by remember { mutableStateOf(initialValue ?: currentName) }
    val validation = remember(newName) { onValidate(newName) }
    val isError = validation is FilenameValidator.ValidationResult.Invalid
    val trimmedName = remember(newName) { newName.trim() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.explorer_rename_new_name)) },
                singleLine = true,
                isError = isError,
                supportingText = if (isError) {
                    {
                        val chars = (validation as FilenameValidator.ValidationResult.Invalid).invalidChars.joinToString(" ")
                        Text(stringResource(eu.darken.butler.common.R.string.general_filename_validation_error, chars))
                    }
                } else null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (trimmedName.isNotBlank() && !isError) {
                        onConfirm(trimmedName)
                    }
                },
                enabled = trimmedName.isNotBlank() && trimmedName != currentName && !isError,
            ) {
                Text(stringResource(eu.darken.butler.common.R.string.general_rename_action))
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
@Composable
private fun PathIssueRenameDialogPreview() {
    PreviewWrapper {
        PathIssueRenameDialog(
            currentName = "document.pdf",
            onConfirm = {},
            onDismiss = {},
        )
    }
}