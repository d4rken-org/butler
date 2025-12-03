package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.OpenInBrowser
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.toCaString
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import java.text.DateFormat
import java.util.Date

@Composable
fun FileOptionsBottomSheet(
    item: ExplorerItem.File,
    trashEnabled: Boolean,
    onDismiss: () -> Unit,
    onOpenInEditor: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        FileOptionsContent(
            item = item,
            trashEnabled = trashEnabled,
            onOpenInEditor = onOpenInEditor,
            onOpenWith = onOpenWith,
            onShare = onShare,
            onCopy = onCopy,
            onCut = onCut,
            onRename = onRename,
            onDelete = onDelete,
            onProperties = onProperties,
        )
    }
}

@Composable
private fun FileOptionsContent(
    item: ExplorerItem.File,
    trashEnabled: Boolean,
    onOpenInEditor: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Preview and file info section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Preview thumbnail
            Card(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(8.dp)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.lookup)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.TwoTone.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            // File information
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // File name
                Text(
                    text = item.lookup.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                // File details card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Type
                        FileInfoRow(
                            label = stringResource(R.string.explorer_file_info_type_label),
                            value = item.mimeType.toCaString().get(context)
                        )

                        // Size
                        item.lookup.size?.let { size ->
                            FileInfoRow(
                                label = stringResource(R.string.explorer_file_info_size_label),
                                value = formatFileSize(size)
                            )
                        }

                        // Modified date
                        item.lookup.modifiedAt?.let { modifiedAt ->
                            FileInfoRow(
                                label = stringResource(R.string.explorer_file_info_modified_label),
                                value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(modifiedAt.toEpochMilliseconds()))
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // Action options
        Text(
            text = stringResource(R.string.explorer_file_options_actions),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )

        // Determine if file is text-editable
        val isTextFile = remember(item.mimeType) {
            item.mimeType.isText
        }

        if (isTextFile) {
            FileActionRow(
                icon = Workspace.Type.EDITOR.icon,
                title = stringResource(R.string.explorer_file_action_open_in_editor),
                subtitle = stringResource(R.string.explorer_file_action_open_in_editor_subtitle),
                onClick = onOpenInEditor
            )
        }

        FileActionRow(
            icon = Icons.TwoTone.OpenInBrowser,
            title = stringResource(R.string.explorer_file_action_open_with),
            subtitle = stringResource(R.string.explorer_file_action_open_with_subtitle),
            onClick = onOpenWith
        )

        FileActionRow(
            icon = Icons.TwoTone.Share,
            title = stringResource(R.string.explorer_file_action_share),
            subtitle = stringResource(R.string.explorer_file_action_share_subtitle),
            onClick = onShare
        )

        HorizontalDivider()

        FileActionRow(
            icon = Icons.TwoTone.ContentCopy,
            title = stringResource(R.string.explorer_file_action_copy),
            subtitle = stringResource(R.string.explorer_file_action_copy_subtitle),
            onClick = onCopy
        )

        FileActionRow(
            icon = Icons.TwoTone.ContentCut,
            title = stringResource(R.string.explorer_file_action_cut),
            subtitle = stringResource(R.string.explorer_file_action_cut_subtitle),
            onClick = onCut
        )

        FileActionRow(
            icon = Icons.TwoTone.DriveFileRenameOutline,
            title = stringResource(R.string.explorer_file_action_rename),
            subtitle = stringResource(R.string.explorer_file_action_rename_subtitle),
            onClick = onRename
        )

        HorizontalDivider()

        FileActionRow(
            icon = Icons.TwoTone.Delete,
            title = stringResource(
                if (trashEnabled) R.string.explorer_file_action_move_to_trash
                else R.string.explorer_file_action_delete
            ),
            subtitle = stringResource(
                if (trashEnabled) R.string.explorer_file_action_move_to_trash_subtitle
                else R.string.explorer_file_action_delete_subtitle
            ),
            onClick = onDelete,
            isDestructive = !trashEnabled,
        )

        FileActionRow(
            icon = Icons.TwoTone.Info,
            title = stringResource(R.string.explorer_file_action_properties),
            subtitle = stringResource(R.string.explorer_file_action_properties_subtitle),
            onClick = onProperties
        )
    }
}

@Composable
private fun FileInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun FileActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview2
@Composable
private fun FileOptionsBottomSheetPreview() {
    PreviewWrapper {
        val mockItem = ExplorerItem.RegularFile(
            lookup = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Documents/test.txt"),
                fileType = FileType.FILE,
                size = 1024L * 50, // 50 KB
                modifiedAt = kotlin.time.Clock.System.now()
            ),
            mimeType = MimeInfo("text/plain")
        )

        FileOptionsBottomSheet(
            item = mockItem,
            trashEnabled = true,
            onDismiss = {},
            onOpenInEditor = {},
            onOpenWith = {},
            onShare = {},
            onCopy = {},
            onCut = {},
            onRename = {},
            onDelete = {},
            onProperties = {},
        )
    }
}
