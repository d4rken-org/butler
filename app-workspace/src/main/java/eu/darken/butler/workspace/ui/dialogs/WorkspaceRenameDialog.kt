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
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.WorkspaceAction.Rename.Companion.MAX_CUSTOM_TITLE_LENGTH
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.common.R as CommonR

/**
 * Sets or clears the user-set name of a workspace. An empty field is a valid action: it clears the
 * name and restores the automatic one, which is why confirm is always enabled. "Clear" does the
 * same in one press and is offered whenever there is a custom name to clear.
 *
 * The input cap mirrors the repo's for immediate feedback; the repo stays authoritative on
 * normalization (trimming, control characters, length).
 *
 * One composable for every caller: the host follows from where it is composed. Reached from inside
 * a pane — the Templates tab's name row — it is pane-bound, so it leaves the other panes alone and
 * takes part in that pane's back, focus and accessibility containment. Reached from the tab rail or
 * the tab manager, which act on the whole screen, it is a window dialog.
 */
@Composable
fun WorkspaceRenameDialog(
    currentCustomTitle: String?,
    autoTitle: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Snapshotted at open time, exactly like the text field's own initial value: if the workspace is
    // renamed from elsewhere while this is open, the button and the field can never disagree.
    val canClear = remember { currentCustomTitle != null }

    val focusRequester = remember { FocusRequester() }

    val initialText = currentCustomTitle ?: ""
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRenameDialogLongNamePreview() {
    WorkspaceRenameDialog(
        currentCustomTitle = "Holiday photos from the summer trip",
        autoTitle = "New tab",
        onConfirm = {},
        onDismiss = {},
    )
}

/** The same dialog inside a pane: composing it under a [PaneLayerHost] is what makes it pane-bound. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundWorkspaceRenameDialogPreview() {
    PreviewWrapper {
        PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
            WorkspaceRenameDialog(
                currentCustomTitle = "Holiday photos",
                autoTitle = "New tab",
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundWorkspaceRenameDialogUnnamedPreview() {
    PreviewWrapper {
        PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
            WorkspaceRenameDialog(
                currentCustomTitle = null,
                autoTitle = "New tab",
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}
