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
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.OpenInBrowser
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import java.text.DateFormat
import java.util.Date

@Composable
fun FileOptionsBottomSheet(
    item: ExplorerItem.File,
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        FileOptionsContent(
            item = item,
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
    onOpenInEditor: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
) {
    LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header with file info
        FileInfoHeader(item = item)

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Action options
        Text(
            text = stringResource(R.string.explorer_file_options_actions),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Determine if file is text-editable
        val isTextFile = remember(item.mimeType) {
            isTextFile(item.mimeType)
        }

        if (isTextFile) {
            FileActionRow(
                icon = Icons.TwoTone.Edit,
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        FileActionRow(
            icon = Icons.TwoTone.Delete,
            title = stringResource(R.string.explorer_file_action_delete),
            subtitle = stringResource(R.string.explorer_file_action_delete_subtitle),
            onClick = onDelete,
            isDestructive = true
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
private fun FileInfoHeader(
    item: ExplorerItem.File
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.lookup.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = item.mimeType,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            item.lookup.size?.let { size ->
                Text(
                    text = formatFileSize(size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item.lookup.modifiedAt?.let { modifiedAt ->
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(modifiedAt.toEpochMilliseconds())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
            .padding(vertical = 8.dp, horizontal = 8.dp),
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

private fun isTextFile(mimeType: String): Boolean {
    return mimeType.startsWith("text/") || mimeType in setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-sh",
        "application/x-shellscript"
    )
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var size = bytes.toDouble()
    var unitIndex = 0

    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }

    return if (unitIndex == 0) {
        "$bytes ${units[unitIndex]}"
    } else {
        String.format("%.1f %s", size, units[unitIndex])
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
            mimeType = "text/plain"
        )

        FileOptionsBottomSheet(
            item = mockItem,
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