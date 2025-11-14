package eu.darken.butler.workspace.ui.manager

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.darken.butler.workspace.R

@Composable
fun CloseAllWorkspacesDialog(
    visible: Boolean,
    workspaceCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_manager_close_all_title)) },
        text = {
            val workspaceString = if (workspaceCount == 1) {
                stringResource(R.string.workspace_manager_close_all_message_singular)
            } else {
                stringResource(R.string.workspace_manager_close_all_message_plural)
            }
            Text(
                stringResource(
                    R.string.workspace_manager_close_all_message,
                    workspaceCount,
                    workspaceString
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.workspace_manager_close_all_action),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.general_cancel_action))
            }
        }
    )
}
