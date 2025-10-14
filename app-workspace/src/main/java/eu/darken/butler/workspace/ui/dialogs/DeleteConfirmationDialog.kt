package eu.darken.butler.workspace.ui.dialogs

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.R
import eu.darken.butler.common.R as CommonR

@Composable
fun DeleteConfirmationDialog(
    items: Set<APath<*>>,
    onDismiss: () -> Unit,
    onConfirm: (items: Set<APath<*>>) -> Unit,
) {
    val itemCount = items.size
    val itemsToShow = items.toList().take(5)
    val hasMore = items.size > 5

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (itemCount == 1) {
                    stringResource(R.string.workspace_dialog_delete_title_single)
                } else {
                    pluralStringResource(R.plurals.workspace_dialog_delete_title_multiple, itemCount, itemCount)
                },
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (itemCount == 1) {
                        stringResource(R.string.workspace_dialog_delete_message_single)
                    } else {
                        stringResource(R.string.workspace_dialog_delete_message_multiple)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    itemsToShow.forEach { item ->
                        Text(
                            text = "• ${item.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }

                    if (hasMore) {
                        Text(
                            text = stringResource(R.string.workspace_dialog_delete_more_items, items.size - 5),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.workspace_dialog_delete_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(items) }
            ) {
                Text(
                    stringResource(CommonR.string.general_delete_action),
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
private fun DeleteConfirmationDialogPreview() {
    PreviewWrapper {
        DeleteConfirmationDialog(
            items = setOf(
                LocalPath.build("/test/file1.txt"),
                LocalPath.build("/test/file2.txt"),
                LocalPath.build("/test/folder1"),
            ),
            onDismiss = {},
            onConfirm = {}
        )
    }
}

@Preview2
@Composable
private fun DeleteConfirmationDialogManyItemsPreview() {
    PreviewWrapper {
        DeleteConfirmationDialog(
            items = (1..10).map {
                LocalPath.build("/test/file$it.txt")
            }.toSet(),
            onDismiss = {},
            onConfirm = {}
        )
    }
}
