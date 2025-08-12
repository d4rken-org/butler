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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath

data class DeleteConfirmationResult(
    val items: Set<APath>,
)

@Composable
fun DeleteConfirmationDialog(
    items: Set<APath>,
    onDismiss: () -> Unit,
    onConfirm: (DeleteConfirmationResult) -> Unit,
) {
    val itemCount = items.size
    val itemsToShow = items.take(5)
    val hasMore = items.size > 5
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (itemCount == 1) "Delete item?" else "Delete $itemCount items?",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (itemCount == 1) {
                        "This item will be permanently deleted:"
                    } else {
                        "These items will be permanently deleted:"
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
                            text = "... and ${items.size - 5} more",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "This action cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(DeleteConfirmationResult(items))
                }
            ) {
                Text(
                    "Delete",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Preview2
@Composable
fun DeleteConfirmationDialogPreview() {
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
fun DeleteConfirmationDialogManyItemsPreview() {
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