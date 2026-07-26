package eu.darken.butler.workspace.ui.issues

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.workspace.ui.modal.LocalLayerActive

@Composable
fun PathIssueRenameDialog(
    currentName: String,
    initialValue: String? = null,
    onValidate: (String) -> FilenameValidator.ValidationResult = { FilenameValidator.ValidationResult.Valid },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    dialogTitle: String = stringResource(R.string.workspace_issue_common_rename),
) {
    var newName by remember { mutableStateOf(initialValue ?: currentName) }
    val validation = remember(newName) { onValidate(newName) }
    val isError = validation is FilenameValidator.ValidationResult.Invalid
    val trimmedName = remember(newName) { newName.trim() }

    val focusRequester = remember { FocusRequester() }

    // Only pull focus (and with it the keyboard) while this dialog is the layer the user is
    // actually talking to — otherwise it steals input from the sheet underneath it.
    val layerActive = LocalLayerActive.current
    LaunchedEffect(layerActive) {
        if (layerActive) focusRequester.requestFocus()
    }

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        includeImePadding = true,
        title = { Text(dialogTitle) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.workspace_issue_rename_new_name)) },
                modifier = Modifier.focusRequester(focusRequester),
                singleLine = true,
                isError = isError,
                supportingText = if (isError) {
                    {
                        val chars = validation.invalidChars.joinToString(" ")
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PathIssueRenameDialogPreview() {
    PathIssueRenameDialog(
        currentName = "document.pdf",
        onConfirm = {},
        onDismiss = {},
    )
}
