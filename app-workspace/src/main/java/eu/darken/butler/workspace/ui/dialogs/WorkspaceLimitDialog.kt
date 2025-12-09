package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R

@Composable
fun WorkspaceLimitDialog(
    limit: Int,
    onDismiss: () -> Unit,
    onUpgrade: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.workspace_limit_reached_title))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.workspace_limit_reached_message,
                    limit,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onUpgrade) {
                Text(text = stringResource(CommonR.string.general_upgrade_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CommonR.string.general_dismiss_action))
            }
        },
    )
}

@Preview2
@Composable
private fun WorkspaceLimitDialogPreview() {
    PreviewWrapper {
        WorkspaceLimitDialog(
            limit = 5,
            onDismiss = {},
            onUpgrade = {},
        )
    }
}
