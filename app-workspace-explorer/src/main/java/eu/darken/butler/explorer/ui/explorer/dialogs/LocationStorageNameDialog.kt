package eu.darken.butler.explorer.ui.explorer.dialogs

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
import eu.darken.butler.explorer.R
import eu.darken.butler.common.R as CommonR

@Composable
fun LocationStorageNameDialog(
    currentName: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    // Pre-select all text if there's a current name
    val initialText = currentName ?: ""
    val initialSelection = TextRange(0, initialText.length)

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(initialText, initialSelection))
    }

    val handleSave = {
        val trimmedName = textFieldValue.text.trim().takeIf { it.isNotEmpty() }
        onConfirm(trimmedName)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.explorer_location_name_title),
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
                    label = { Text(stringResource(R.string.explorer_location_name_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { handleSave() }),
                    supportingText = {
                        Text(stringResource(R.string.explorer_location_name_hint))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = handleSave) {
                Text(stringResource(CommonR.string.general_save_action))
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
private fun LocationStorageNameDialogPreview() {
    LocationStorageNameDialog(
        currentName = null,
        onDismiss = {},
        onConfirm = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LocationStorageNameDialogRenamePreview() {
    LocationStorageNameDialog(
        currentName = "My SD Card",
        onDismiss = {},
        onConfirm = {}
    )
}
