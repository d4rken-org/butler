package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.common.R as CommonR

@Composable
fun RemoveLocationConfirmationDialog(
    items: List<ExplorerItem.Storage.SAF>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val itemCount = items.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (itemCount == 1) {
                    stringResource(R.string.explorer_device_remove_confirmation_title)
                } else {
                    pluralStringResource(
                        R.plurals.explorer_device_remove_confirmation_title_multiple,
                        itemCount,
                        itemCount
                    )
                },
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.explorer_device_remove_confirmation_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (itemCount > 1) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        items.forEach { item ->
                            Text(
                                text = "• ${item.displayName.get(context)}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm
            ) {
                Text(
                    stringResource(R.string.explorer_device_action_remove_location),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        }
    )
}

@Preview2
@Composable
private fun RemoveLocationConfirmationDialogPreview() {
    PreviewWrapper {
        RemoveLocationConfirmationDialog(
            items = listOf(
                MockDataProvider.createMockStorageSAF(name = "SD Card"),
            ),
            onDismiss = {},
            onConfirm = {}
        )
    }
}

@Preview2
@Composable
private fun RemoveLocationConfirmationDialogMultiplePreview() {
    PreviewWrapper {
        RemoveLocationConfirmationDialog(
            items = listOf(
                MockDataProvider.createMockStorageSAF(name = "SD Card"),
                MockDataProvider.createMockStorageSAF(name = "USB Drive"),
                MockDataProvider.createMockStorageSAF(name = "Network Share"),
            ),
            onDismiss = {},
            onConfirm = {}
        )
    }
}
