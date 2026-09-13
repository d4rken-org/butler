package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.common.R as CommonR

/**
 * Names a favorite entry. The folder itself is never touched, which is what the description line
 * spells out — this is not [RenameDialog].
 */
@Composable
fun FavoriteRenameDialog(
    path: APath<*>,
    currentLabel: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    val initialText = currentLabel ?: path.userReadableName.get(context)

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(initialText, TextRange(0, initialText.length)))
    }

    val handleSave = {
        onConfirm(textFieldValue.text.trim().takeIf { it.isNotEmpty() })
    }

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
                text = stringResource(R.string.explorer_favorites_rename_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    label = { Text(stringResource(R.string.explorer_favorites_rename_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { handleSave() }),
                    supportingText = {
                        Text(stringResource(R.string.explorer_favorites_rename_description))
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
        },
        neutralButton = if (currentLabel == null) {
            null
        } else {
            { TextButton(onClick = { onConfirm(null) }) { Text(stringResource(CommonR.string.general_reset_action)) } }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoriteRenameDialogPreview() {
    FavoriteRenameDialog(
        path = LocalPath.build("/storage/emulated/0/DCIM/Camera"),
        currentLabel = null,
        onDismiss = {},
        onConfirm = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoriteRenameDialogLabeledPreview() {
    FavoriteRenameDialog(
        path = LocalPath.build("/storage/emulated/0/DCIM/Camera"),
        currentLabel = "Camera roll",
        onDismiss = {},
        onConfirm = {},
    )
}
