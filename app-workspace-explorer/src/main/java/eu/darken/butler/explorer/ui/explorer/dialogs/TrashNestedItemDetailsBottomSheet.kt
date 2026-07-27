package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import java.text.DateFormat
import java.util.Date

@Composable
fun TrashNestedItemDetailsBottomSheet(
    item: ExplorerItem.Trash.Nested,
    onAction: (ExplorerActionBarItem) -> Unit,
    onCopyToClipboard: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        modifier = modifier,
    ) {
        TrashNestedItemOptionsContent(
            item = item,
            onAction = onAction,
            onCopyToClipboard = onCopyToClipboard,
        )
    }
}

@Composable
private fun TrashNestedItemOptionsContent(
    item: ExplorerItem.Trash.Nested,
    onAction: (ExplorerActionBarItem) -> Unit,
    onCopyToClipboard: (String) -> Unit,
) {
    val context = LocalContext.current
    val lookup = item.lookup

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 8.dp,
                bottom = 32.dp
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Item header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedAsyncImage(
                model = lookup,
                contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.displayName.get(context),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = item.relativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Details card
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            InfoCard {
                // Original restore path
                val restorePath = item.originalRestoredPath.path
                InfoRow(
                    label = stringResource(R.string.explorer_info_path_label),
                    value = restorePath,
                    isCopyable = true,
                    onCopy = { onCopyToClipboard(restorePath) }
                )

                // Type
                val typeValue = when (lookup.fileType) {
                    FileType.FILE -> context.getString(R.string.explorer_info_type_file)
                    FileType.DIRECTORY -> context.getString(R.string.explorer_info_type_directory)
                    else -> context.getString(R.string.explorer_info_unknown)
                }
                InfoRow(
                    label = stringResource(R.string.explorer_info_type_label),
                    value = typeValue
                )

                // Size
                lookup.size?.let { size ->
                    InfoRow(
                        label = stringResource(R.string.explorer_info_size_label),
                        value = formatFileSize(size)
                    )
                }

                // Modified date
                lookup.modifiedAt?.let { modifiedAt ->
                    InfoRow(
                        label = stringResource(R.string.explorer_info_modified_label),
                        value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(modifiedAt.toEpochMilliseconds()))
                    )
                }

                // Parent deleted at
                InfoRow(
                    label = stringResource(R.string.explorer_trash_info_deleted_label),
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(item.parentRef.deletedAt.toEpochMilliseconds()))
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Restore option
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onAction(
                        ExplorerActionBarItem.TrashNested.Restore(
                            items = listOf(item),
                            icon = Icons.TwoTone.Restore,
                            labelRes = R.string.explorer_trash_restore_action,
                        )
                    )
                }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.TwoTone.Restore,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = stringResource(R.string.explorer_trash_restore_action),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.explorer_trash_nested_restore_action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Delete permanently option
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onAction(
                        ExplorerActionBarItem.TrashNested.DeletePermanently(
                            items = listOf(item),
                            icon = Icons.TwoTone.DeleteForever,
                            labelRes = R.string.explorer_trash_delete_permanently_action,
                        )
                    )
                }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.TwoTone.DeleteForever,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.error,
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = stringResource(R.string.explorer_trash_delete_permanently_action),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.explorer_trash_delete_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemOptionsBottomSheetPreview() {
    TrashNestedItemOptionsContent(
        item = MockDataProvider.createMockTrashNestedItem(),
        onAction = {},
        onCopyToClipboard = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemOptionsBottomSheetDirectoryPreview() {
    TrashNestedItemOptionsContent(
        item = MockDataProvider.createMockTrashNestedDirectory(),
        onAction = {},
        onCopyToClipboard = {},
    )
}
