package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.R

/**
 * Confirms a batch close. [isSelection] switches the wording between closing every open tab and
 * closing the subset picked in the tab manager; [workspaceCount] and [hasUnsavedChanges] are the
 * caller's to scope to whichever set it is about to close.
 */
@Composable
fun CloseWorkspacesDialog(
    visible: Boolean,
    workspaceCount: Int,
    hasUnsavedChanges: Boolean = false,
    isSelection: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isSelection) {
                        R.string.workspace_manager_close_selected_title
                    } else {
                        R.string.workspace_manager_close_all_title
                    }
                )
            )
        },
        text = {
            Column {
                val workspaceString = if (workspaceCount == 1) {
                    stringResource(R.string.workspace_manager_close_all_message_singular)
                } else {
                    stringResource(R.string.workspace_manager_close_all_message_plural)
                }
                Text(
                    stringResource(
                        if (isSelection) {
                            R.string.workspace_manager_close_selected_message
                        } else {
                            R.string.workspace_manager_close_all_message
                        },
                        workspaceCount,
                        workspaceString
                    )
                )
                if (hasUnsavedChanges) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            if (isSelection) {
                                R.string.workspace_manager_close_selected_unsaved_warning
                            } else {
                                R.string.workspace_manager_close_all_unsaved_warning
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(
                        when {
                            isSelection && hasUnsavedChanges -> R.string.workspace_manager_close_selected_unsaved_action
                            isSelection -> R.string.workspace_manager_close_selected_action
                            hasUnsavedChanges -> R.string.workspace_manager_close_all_unsaved_action
                            else -> R.string.workspace_manager_close_all_action
                        }
                    ),
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
