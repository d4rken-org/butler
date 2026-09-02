package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.BulletListItem
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.partitionByTrashSupport
import eu.darken.butler.common.R as CommonR

@Composable
fun DeleteConfirmationDialog(
    items: Set<APath<*>>,
    trashEnabled: Boolean = false,
    initialPermanentDelete: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (items: Set<APath<*>>, forcePermDelete: Boolean) -> Unit,
) {
    // Keyed so a replacement request can't inherit the previous choice, saveable so unchecking a
    // seeded `true` survives activity recreation
    var permDelete by rememberSaveable(items, initialPermanentDelete) { mutableStateOf(initialPermanentDelete) }

    val partition = partitionByTrashSupport(items)
    val canTrashAny = partition.trashable.isNotEmpty()
    val hasPartialTrashSupport = canTrashAny && partition.untrashable.isNotEmpty()

    val effectiveTrashEnabled = trashEnabled && canTrashAny && !permDelete
    val itemCount = items.size
    val itemsToShow = items.toList().take(5)
    val hasMore = items.size > 5

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (effectiveTrashEnabled) {
                    if (itemCount == 1) {
                        stringResource(R.string.workspace_dialog_trash_title_single)
                    } else {
                        pluralStringResource(R.plurals.workspace_dialog_trash_title_multiple, itemCount, itemCount)
                    }
                } else {
                    if (itemCount == 1) {
                        stringResource(R.string.workspace_dialog_delete_title_single)
                    } else {
                        pluralStringResource(R.plurals.workspace_dialog_delete_title_multiple, itemCount, itemCount)
                    }
                },
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (effectiveTrashEnabled) {
                        if (itemCount == 1) {
                            stringResource(R.string.workspace_dialog_trash_message_single)
                        } else {
                            stringResource(R.string.workspace_dialog_trash_message_multiple)
                        }
                    } else {
                        if (itemCount == 1) {
                            stringResource(R.string.workspace_dialog_delete_message_single)
                        } else {
                            stringResource(R.string.workspace_dialog_delete_message_multiple)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    itemsToShow.forEach { item ->
                        BulletListItem(
                            modifier = Modifier.padding(vertical = 2.dp),
                            text = item.name,
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
                    text = if (effectiveTrashEnabled) {
                        stringResource(R.string.workspace_dialog_trash_hint)
                    } else {
                        stringResource(R.string.workspace_dialog_delete_warning)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (effectiveTrashEnabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontWeight = if (effectiveTrashEnabled) FontWeight.Normal else FontWeight.Medium
                )

                if (effectiveTrashEnabled && hasPartialTrashSupport) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.workspace_dialog_delete_partial_trash_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (trashEnabled && canTrashAny) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = permDelete,
                                onValueChange = { permDelete = it },
                                role = Role.Checkbox,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = permDelete,
                            onCheckedChange = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.workspace_dialog_delete_permanently_toggle),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // When nothing can be trashed the dialog already promised a permanent delete
                onClick = { onConfirm(items, permDelete || !canTrashAny) }
            ) {
                Text(
                    text = if (effectiveTrashEnabled) {
                        stringResource(R.string.workspace_dialog_move_to_trash_action)
                    } else {
                        stringResource(R.string.workspace_dialog_delete_permanently_action)
                    },
                    color = if (effectiveTrashEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeleteConfirmationDialogPreview() {
    DeleteConfirmationDialog(
        items = setOf(
            LocalPath.build("/test/file1.txt"),
            LocalPath.build("/test/file2.txt"),
            LocalPath.build("/test/folder1"),
        ),
        trashEnabled = false,
        onDismiss = {},
        onConfirm = { _, _ -> },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeleteConfirmationDialogTrashPreview() {
    DeleteConfirmationDialog(
        items = setOf(
            LocalPath.build("/test/file1.txt"),
            LocalPath.build("/test/file2.txt"),
            LocalPath.build("/test/folder1"),
        ),
        trashEnabled = true,
        onDismiss = {},
        onConfirm = { _, _ -> },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeleteConfirmationDialogForcePermDeletePreview() {
    DeleteConfirmationDialog(
        items = setOf(
            LocalPath.build("/test/file1.txt"),
            LocalPath.build("/test/file2.txt"),
            LocalPath.build("/test/folder1"),
        ),
        trashEnabled = true,
        initialPermanentDelete = true,
        onDismiss = {},
        onConfirm = { _, _ -> },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeleteConfirmationDialogTrashUnsupportedPreview() {
    DeleteConfirmationDialog(
        items = setOf(
            SAFPath.build("content://com.android.externalstorage.documents/tree/primary", "file1.txt"),
            SAFPath.build("content://com.android.externalstorage.documents/tree/primary", "file2.txt"),
        ),
        trashEnabled = true,
        onDismiss = {},
        onConfirm = { _, _ -> },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeleteConfirmationDialogPartialTrashPreview() {
    DeleteConfirmationDialog(
        items = setOf(
            LocalPath.build("/test/file1.txt"),
            SAFPath.build("content://com.android.externalstorage.documents/tree/primary", "file2.txt"),
        ),
        trashEnabled = true,
        onDismiss = {},
        onConfirm = { _, _ -> },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeleteConfirmationDialogLongNamesPreview() {
    DeleteConfirmationDialog(
        items = setOf(
            LocalPath.build("/test/termux-app_v0.118.3+github-debug_universal.apk"),
            LocalPath.build("/test/AVeryLongNameWithoutAnySeparatorsAtAllThatCannotBeWrappedNicely"),
        ),
        trashEnabled = true,
        onDismiss = {},
        onConfirm = { _, _ -> },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeleteConfirmationDialogManyItemsPreview() {
    DeleteConfirmationDialog(
        items = (1..10).map {
            LocalPath.build("/test/file$it.txt")
        }.toSet(),
        trashEnabled = false,
        onDismiss = {},
        onConfirm = { _, _ -> },
    )
}
