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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.QuestionMark
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import java.text.DateFormat
import java.util.Date
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Composable
fun TrashItemDetailsBottomSheet(
    item: ExplorerItem.Trash.Root,
    onAction: (ExplorerActionBarItem) -> Unit,
    onCopyToClipboard: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        bottomInset = bottomInset,
        modifier = modifier,
    ) {
        TrashItemOptionsContent(
            item = item,
            onAction = onAction,
            onCopyToClipboard = onCopyToClipboard,
        )
    }
}

@Composable
private fun TrashItemOptionsContent(
    item: ExplorerItem.Trash.Root,
    onAction: (ExplorerActionBarItem) -> Unit,
    onCopyToClipboard: (String) -> Unit,
) {
    val context = LocalContext.current
    val lookup = item.originalLookup

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
            if (item.trashLookup?.lookedUp != null) {
                TintedAsyncImage(
                    model = item.trashLookup,
                    contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.TwoTone.QuestionMark,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

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
                    text = item.subtitle.get(context),
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
                // Deleted at - trash specific
                InfoRow(
                    label = stringResource(R.string.explorer_trash_info_deleted_label),
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(item.deletedAt.toEpochMilliseconds()))
                )

                // Original path
                InfoRow(
                    label = stringResource(R.string.explorer_info_path_label),
                    value = lookup.path,
                    isCopyable = true,
                    onCopy = { onCopyToClipboard(lookup.path) }
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

                // Created date
                lookup.createdAt?.let { createdAt ->
                    InfoRow(
                        label = stringResource(R.string.explorer_info_created_label),
                        value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(createdAt.toEpochMilliseconds()))
                    )
                }

                // Permissions
                lookup.permissions?.let { permissions ->
                    InfoRow(
                        label = stringResource(R.string.explorer_info_permissions_label),
                        value = permissions.toReadableString()
                    )
                }

                // Owner
                lookup.ownership?.let { ownership ->
                    InfoRow(
                        label = stringResource(R.string.explorer_info_owner_label),
                        value = ownership.userName ?: ownership.userId.toString()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Options
        if (item.isAvailable) {
            // Restore option - only available if item is still available
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                    onAction(
                        ExplorerActionBarItem.Trash.Restore(
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
                        text = stringResource(R.string.explorer_trash_restore_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Show message that item cannot be restored (storage unavailable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = stringResource(R.string.explorer_trash_restore_action),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Text(
                        text = stringResource(R.string.explorer_trash_item_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Delete permanently option - always available
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onAction(
                        ExplorerActionBarItem.Trash.DeletePermanently(
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
@Composable
private fun TrashItemOptionsBottomSheetPreview() {
    PreviewWrapper {
        val mockPath = LocalPath.build("/storage/emulated/0/Documents/test.txt")
        val mockItem = ExplorerItem.Trash.Root(
            itemId = Uuid.random(),
            deletedAt = Clock.System.now(),
            originalLookup = LocalPathLookup(
                lookedUp = mockPath,
                fileType = FileType.FILE,
                size = 1024L * 50,
                modifiedAt = Clock.System.now(),
            ),
            trashLookup = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/.Trash/test.txt"),
                fileType = FileType.FILE,
                size = 1024L * 50,
                modifiedAt = Clock.System.now(),
            ),
        )

        TrashItemOptionsContent(
            item = mockItem,
            onAction = {},
            onCopyToClipboard = {},
        )
    }
}