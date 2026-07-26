package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.WorkspaceAction.Rename.Companion.MAX_CUSTOM_TITLE_LENGTH
import eu.darken.butler.common.R as CommonR

/**
 * Sets or clears the user-set name of a workspace. An empty field is a valid action: it clears the
 * name and restores the automatic one, which is why confirm is always enabled.
 *
 * The input cap mirrors the repo's for immediate feedback; the repo stays authoritative on
 * normalization (trimming, control characters, length).
 */
@Composable
fun WorkspaceRenameDialog(
    currentCustomTitle: String?,
    autoTitle: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    val initialText = currentCustomTitle ?: ""
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(initialText, TextRange(0, initialText.length)))
    }

    val handleConfirm = {
        onConfirm(textFieldValue.text.trim().takeIf { it.isNotEmpty() })
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.workspace_rename_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    value = textFieldValue,
                    onValueChange = { new ->
                        textFieldValue = if (new.text.length > MAX_CUSTOM_TITLE_LENGTH) {
                            new.copy(text = new.text.take(MAX_CUSTOM_TITLE_LENGTH))
                        } else {
                            new
                        }
                    },
                    label = { Text(stringResource(R.string.workspace_rename_name_label)) },
                    placeholder = { Text(autoTitle) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { handleConfirm() }),
                    supportingText = { Text(stringResource(R.string.workspace_rename_name_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = handleConfirm) {
                Text(stringResource(CommonR.string.general_rename_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRenameDialogPreview() {
    WorkspaceRenameDialog(
        currentCustomTitle = null,
        autoTitle = "/storage/emulated/0/Download",
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRenameDialogNamedPreview() {
    WorkspaceRenameDialog(
        currentCustomTitle = "Holiday photos",
        autoTitle = "/storage/emulated/0/DCIM/Camera",
        onConfirm = {},
        onDismiss = {},
    )
}
