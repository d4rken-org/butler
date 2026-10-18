package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.BulletListItem
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.common.R as CommonR

@Composable
fun DisconnectLocationConfirmationDialog(
    items: List<ExplorerItem.Storage.Network>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val itemCount = items.size

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (itemCount == 1) {
                    stringResource(
                        R.string.explorer_network_disconnect_confirmation_title,
                        items.single().displayName.get(context),
                    )
                } else {
                    pluralStringResource(
                        R.plurals.explorer_network_disconnect_confirmation_title_multiple,
                        itemCount,
                        itemCount,
                    )
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.explorer_network_disconnect_confirmation_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (itemCount > 1) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        items.forEach { item ->
                            BulletListItem(
                                modifier = Modifier.padding(vertical = 2.dp),
                                text = item.displayName.get(context),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.explorer_network_disconnect_location_action))
            }
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
private fun DisconnectLocationConfirmationDialogPreview() {
    DisconnectLocationConfirmationDialog(
        items = listOf(
            MockDataProvider.createMockStorageNetwork(name = "Home NAS", rememberCredential = false),
        ),
        onDismiss = {},
        onConfirm = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DisconnectLocationConfirmationDialogMultiplePreview() {
    DisconnectLocationConfirmationDialog(
        items = listOf(
            MockDataProvider.createMockStorageNetwork(name = "Home NAS", rememberCredential = false),
            MockDataProvider.createMockStorageSftp(rememberCredential = false),
            MockDataProvider.createMockStorageWebDav(rememberCredential = false),
        ),
        onDismiss = {},
        onConfirm = {},
    )
}
