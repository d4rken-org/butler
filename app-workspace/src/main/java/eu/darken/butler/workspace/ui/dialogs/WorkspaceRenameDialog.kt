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
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
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
) = RenameDialogScaffold(
    currentCustomTitle = currentCustomTitle,
    autoTitle = autoTitle,
    onConfirm = onConfirm,
    onDismiss = onDismiss,
) { title, text, confirmButton, dismissButton ->
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

/**
 * [WorkspaceRenameDialog] for use from inside a workspace pane.
 *
 * Renaming reached from a pane — the Templates tab's name row — belongs to that pane, so it must
 * not dim the whole window and must take part in the pane's back, focus and accessibility
 * containment. The screen-level callers (the tab rail, the tab manager) keep the window variant:
 * those genuinely act on the whole screen.
 */
@Composable
fun PaneBoundWorkspaceRenameDialog(
    currentCustomTitle: String?,
    autoTitle: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) = RenameDialogScaffold(
    currentCustomTitle = currentCustomTitle,
    autoTitle = autoTitle,
    onConfirm = onConfirm,
    onDismiss = onDismiss,
) { title, text, confirmButton, dismissButton ->
    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        includeImePadding = true,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

/**
 * The shared body of both variants: input state, the length cap and the confirm semantics live here
 * exactly once, and [shell] only decides whether it is drawn in a window or inside a pane.
 */
@Composable
private fun RenameDialogScaffold(
    currentCustomTitle: String?,
    autoTitle: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    shell: @Composable (
        title: @Composable () -> Unit,
        text: @Composable () -> Unit,
        confirmButton: @Composable () -> Unit,
        dismissButton: @Composable () -> Unit,
    ) -> Unit,
) {
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

    shell(
        {
            Text(
                text = stringResource(R.string.workspace_rename_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        {
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
        {
            TextButton(onClick = handleConfirm) {
                Text(stringResource(CommonR.string.general_rename_action))
            }
        },
        {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundWorkspaceRenameDialogPreview() {
    PaneBoundWorkspaceRenameDialog(
        currentCustomTitle = "Holiday photos",
        autoTitle = "New tab",
        onConfirm = {},
        onDismiss = {},
    )
}
