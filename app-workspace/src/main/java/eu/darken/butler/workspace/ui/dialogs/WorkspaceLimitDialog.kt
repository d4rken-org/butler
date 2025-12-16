package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerIcon
import eu.darken.butler.common.compose.ButlerIconVariant
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.common.R as CommonR

@Composable
fun WorkspaceLimitDialog(
    limit: Int,
    onDismiss: () -> Unit,
    onUpgrade: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ButlerIcon(
                modifier = Modifier.size(48.dp),
                variant = ButlerIconVariant.SAD,
            )
        },
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
