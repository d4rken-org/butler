package eu.darken.butler.main.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun ExitConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dontAskAgain: Boolean,
    onDontAskAgainChange: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.confirm_exit_dialog_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.confirm_exit_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDontAskAgainChange(!dontAskAgain) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = dontAskAgain,
                        onCheckedChange = onDontAskAgainChange,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .scale(0.8f)
                    )
                    Text(
                        text = stringResource(R.string.confirm_exit_dialog_dont_ask_again),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm_exit_dialog_exit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm_exit_dialog_cancel))
            }
        }
    )
}

@Preview2
@Composable
private fun ExitConfirmationDialogPreview() {
    PreviewWrapper {
        ExitConfirmationDialog(
            onDismiss = {},
            onConfirm = {},
            dontAskAgain = false,
            onDontAskAgainChange = {}
        )
    }
}

@Preview2
@Composable
private fun ExitConfirmationDialogWithDontAskAgainPreview() {
    PreviewWrapper {
        ExitConfirmationDialog(
            onDismiss = {},
            onConfirm = {},
            dontAskAgain = true,
            onDontAskAgainChange = {}
        )
    }
}