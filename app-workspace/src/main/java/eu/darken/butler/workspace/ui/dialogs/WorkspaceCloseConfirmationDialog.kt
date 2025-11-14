package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.R as CommonR

@Composable
fun WorkspaceCloseConfirmationDialog(
    workspaceTitle: CaString,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(CommonR.string.general_workspace_close_confirmation_title))
        },
        text = {
            Text(
                text = stringResource(
                    CommonR.string.general_workspace_close_confirmation_message,
                    workspaceTitle.get(LocalContext.current)
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(CommonR.string.general_close_action))
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
@Composable
private fun WorkspaceCloseConfirmationDialogPreview() {
    PreviewWrapper {
        WorkspaceCloseConfirmationDialog(
            workspaceTitle = "My Documents".toCaString(),
            onDismiss = {},
            onConfirm = {},
        )
    }
}
