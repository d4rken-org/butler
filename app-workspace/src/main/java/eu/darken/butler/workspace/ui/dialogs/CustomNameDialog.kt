package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.ui.dialogs.AdaptiveAlertDialog
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.common.R as CommonR

/**
 * Sets or clears a user-set name for something that also has an automatic one ([autoName], shown as
 * the placeholder). An empty field is a valid action: it clears the name and restores the automatic
 * one, which is why confirm is always enabled. "Clear" does the same in one press and is offered
 * whenever there is a name to clear.
 *
 * [maxLength] only exists to give immediate feedback while typing; the store the name goes to stays
 * authoritative on normalization (trimming, control characters, length). It counts code points, so
 * the field cannot cut a surrogate pair in half.
 */
@Composable
fun CustomNameDialog(
    currentName: String?,
    autoName: String,
    dialogTitle: String,
    fieldLabel: String,
    fieldHint: String,
    maxLength: Int,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Snapshotted at open time, exactly like the text field's own initial value: if the name is
    // changed from elsewhere while this is open, the button and the field can never disagree.
    val canClear = remember { currentName != null }

    val focusRequester = remember { FocusRequester() }

    val initialText = currentName ?: ""
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(initialText, TextRange(0, initialText.length)))
    }

    val handleConfirm = {
        onConfirm(textFieldValue.text.trim().takeIf { it.isNotEmpty() })
    }

    // Only pull focus while this is the layer the user is talking to. Outside a pane there is no
    // layer stack and this is always true, so the window variant behaves as before.
    val layerActive = LocalLayerActive.current
    LaunchedEffect(layerActive) {
        if (layerActive) focusRequester.requestFocus()
    }

    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        includeImePadding = true,
        title = {
            Text(
                text = dialogTitle,
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
                        textFieldValue = if (new.text.codePointCount(0, new.text.length) > maxLength) {
                            new.copy(text = new.text.substring(0, new.text.offsetByCodePoints(0, maxLength)))
                        } else {
                            new
                        }
                    },
                    label = { Text(fieldLabel) },
                    placeholder = { Text(autoName) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { handleConfirm() }),
                    supportingText = { Text(fieldHint) },
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
        neutralButton = if (canClear) {
            {
                TextButton(onClick = { onConfirm(null) }) {
                    Text(stringResource(CommonR.string.general_clear_action))
                }
            }
        } else {
            null
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CustomNameDialogPreview() {
    CustomNameDialog(
        currentName = "Holiday photos",
        autoName = "New tab",
        dialogTitle = "Name this tab",
        fieldLabel = "Custom name",
        fieldHint = "Leave empty to use the automatic name",
        maxLength = 128,
        onConfirm = {},
        onDismiss = {},
    )
}

/** Inside a pane: composing it under a [PaneLayerHost] is what makes it pane-bound. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundCustomNameDialogPreview() {
    PreviewWrapper {
        PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
            CustomNameDialog(
                currentName = null,
                autoName = "New tab",
                dialogTitle = "Name this tab",
                fieldLabel = "Custom name",
                fieldHint = "Leave empty to use the automatic name",
                maxLength = 128,
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}
