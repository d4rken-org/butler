package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.common.R as CommonR

@Composable
fun DropConfirmationDialog(
    payload: WorkspaceDragPayload,
    destination: APath<*>,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
) {
    val itemCount = payload.items.size

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.explorer_drop_dialog_title, itemCount, itemCount)) },
        text = {
            Text(
                pluralStringResource(
                    if (payload.allowMove) {
                        R.plurals.explorer_drop_dialog_message
                    } else {
                        R.plurals.explorer_drop_dialog_message_copy_only
                    },
                    itemCount,
                    itemCount,
                    destination.name,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text(stringResource(CommonR.string.general_copy_action))
            }
        },
        neutralButton = if (payload.allowMove) {
            {
                TextButton(onClick = onMove) {
                    Text(stringResource(CommonR.string.general_move_action))
                }
            }
        } else {
            null
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
private fun DropConfirmationDialogPreview() {
    DropConfirmationDialog(
        payload = previewPayload(allowMove = true),
        destination = LocalPath.build("/storage/emulated/0/Download"),
        onDismiss = {},
        onCopy = {},
        onMove = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DropConfirmationDialogCopyOnlyPreview() {
    DropConfirmationDialog(
        payload = previewPayload(allowMove = false),
        destination = LocalPath.build("/storage/emulated/0/Download"),
        onDismiss = {},
        onCopy = {},
        onMove = {},
    )
}

private fun previewPayload(allowMove: Boolean) = WorkspaceDragPayload(
    sourceWorkspaceId = Workspace.Id(),
    items = listOf(
        WorkspaceDragPayload.Item(
            path = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg"),
            kind = WorkspaceDragPayload.Kind.FILE_OTHER,
        ),
        WorkspaceDragPayload.Item(
            path = LocalPath.build("/storage/emulated/0/DCIM/raw"),
            kind = WorkspaceDragPayload.Kind.DIRECTORY,
        ),
    ),
    allowMove = allowMove,
)
