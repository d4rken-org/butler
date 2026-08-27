package eu.darken.butler.workspace.ui.manager

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.darken.butler.workspace.R

/**
 * Shown only when part of the selection cannot be paused, and then as a preview of what will happen
 * rather than as a permission slip - pausing is reversible and needs no guarding. A fully pausable
 * selection pauses straight away with no dialog at all.
 */
@Composable
fun PausePartialSelectionDialog(
    visible: Boolean,
    pausableCount: Int,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_manager_pause_partial_title)) },
        text = {
            Text(
                stringResource(
                    R.string.workspace_manager_pause_partial_message,
                    pausableCount,
                    selectedCount,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.workspace_manager_pause_partial_action, pausableCount))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.general_cancel_action))
            }
        }
    )
}
