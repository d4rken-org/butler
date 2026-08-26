package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.R as CommonR

@Composable
fun WorkspaceCloseConfirmationDialog(
    workspaceTitle: CaString,
    hasUnsavedChanges: Boolean = false,
    /** Unsaved members the close would discard; [workspaceTitle] names only one of them. */
    unsavedCount: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (hasUnsavedChanges) {
                        CommonR.string.general_tab_close_unsaved_title
                    } else {
                        CommonR.string.general_tab_close_confirmation_title
                    }
                )
            )
        },
        text = {
            val name = workspaceTitle.get(LocalContext.current)
            Text(
                text = when {
                    // Closing a tab discards its whole modal stack, so naming one dirty member
                    // while several go down would understate what the user is agreeing to
                    hasUnsavedChanges && unsavedCount > 1 -> stringResource(
                        CommonR.string.general_tab_close_unsaved_message_multiple,
                        name,
                        unsavedCount - 1,
                    )
                    hasUnsavedChanges -> stringResource(
                        CommonR.string.general_tab_close_unsaved_message,
                        name,
                    )
                    else -> stringResource(
                        CommonR.string.general_tab_close_confirmation_message,
                        name,
                    )
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(
                        if (hasUnsavedChanges) {
                            CommonR.string.general_discard_action
                        } else {
                            CommonR.string.general_close_action
                        }
                    ),
                    color = if (hasUnsavedChanges) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceCloseConfirmationDialogPreview() {
    WorkspaceCloseConfirmationDialog(
        workspaceTitle = "My Documents".toCaString(),
        onDismiss = {},
        onConfirm = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceCloseConfirmationDialogUnsavedPreview() {
    WorkspaceCloseConfirmationDialog(
        workspaceTitle = "notes.txt".toCaString(),
        hasUnsavedChanges = true,
        unsavedCount = 1,
        onDismiss = {},
        onConfirm = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceCloseConfirmationDialogUnsavedMultiplePreview() {
    WorkspaceCloseConfirmationDialog(
        workspaceTitle = "notes.txt".toCaString(),
        hasUnsavedChanges = true,
        unsavedCount = 3,
        onDismiss = {},
        onConfirm = {},
    )
}
