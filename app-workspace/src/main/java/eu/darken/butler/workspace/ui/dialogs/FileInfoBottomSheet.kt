package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.workspace.R
import java.text.DateFormat
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Data class encapsulating all file/directory information for display.
 *
 * Provides a clean API for FileInfoBottomSheet by grouping all metadata fields.
 * Workspaces provide what they have - missing fields won't be displayed.
 */
data class FileInfo(
    val lookup: APathLookup<*>,
    val ownership: Ownership? = null,
    val permissions: Permissions? = null,
    val createdAt: Instant? = null,
    val mimeInfo: MimeInfo? = null,
    val childCount: Int? = null,
)

/**
 * Shared bottom sheet for displaying file/directory information.
 *
 * Shows basic info from APathLookup (available to all workspaces) and optionally
 * displays extended info like ownership, permissions, and creation time if provided.
 */
@Composable
fun FileInfoBottomSheet(
    fileInfo: FileInfo,
    onDismiss: () -> Unit,
    onCopyToClipboard: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInPreview = LocalInspectionMode.current

    if (isInPreview) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            FileInfoContent(
                lookup = fileInfo.lookup,
                onCopyToClipboard = onCopyToClipboard,
                ownership = fileInfo.ownership,
                permissions = fileInfo.permissions,
                createdAt = fileInfo.createdAt,
                mimeInfo = fileInfo.mimeInfo,
                childCount = fileInfo.childCount,
            )
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier,
        ) {
            FileInfoContent(
                lookup = fileInfo.lookup,
                onCopyToClipboard = onCopyToClipboard,
                ownership = fileInfo.ownership,
                permissions = fileInfo.permissions,
                createdAt = fileInfo.createdAt,
                mimeInfo = fileInfo.mimeInfo,
                childCount = fileInfo.childCount,
            )
        }
    }
}

@Composable
private fun FileInfoContent(
    lookup: APathLookup<*>,
    onCopyToClipboard: (String) -> Unit,
    ownership: Ownership?,
    permissions: Permissions?,
    createdAt: Instant?,
    mimeInfo: MimeInfo?,
    childCount: Int?,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title
        Text(
            text = stringResource(R.string.workspace_file_info_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        // Main info card
        InfoCard {
            // Name - always shown
            InfoRow(
                label = stringResource(R.string.workspace_file_info_name_label),
                value = lookup.name
            )

            // Path - always shown, copyable
            InfoRow(
                label = stringResource(R.string.workspace_file_info_path_label),
                value = lookup.path,
                isCopyable = true,
                onCopy = { onCopyToClipboard(lookup.path) }
            )

            // Type - shown with MIME info if available, otherwise generic type
            val typeValue = when {
                mimeInfo != null -> mimeInfo.rawType
                lookup.fileType == FileType.FILE -> context.getString(R.string.workspace_file_info_type_file)
                lookup.fileType == FileType.DIRECTORY -> context.getString(R.string.workspace_file_info_type_directory)
                lookup.fileType == FileType.SYMBOLIC_LINK -> context.getString(R.string.workspace_file_info_type_symlink)
                lookup.fileType == FileType.UNKNOWN -> context.getString(R.string.workspace_file_info_type_unknown)
                else -> context.getString(R.string.workspace_file_info_unknown)
            }
            InfoRow(
                label = stringResource(R.string.workspace_file_info_type_label),
                value = typeValue
            )

            // Size - always shown for files, shown for directories if available
            if (lookup.fileType == FileType.FILE || lookup.size > 0) {
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_size_label),
                    value = formatFileSize(lookup.size)
                )
            }

            // Child count - only for directories, if provided
            if (lookup.fileType == FileType.DIRECTORY && childCount != null) {
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_child_count_label),
                    value = childCount.toString()
                )
            }

            // Modified date - always shown if available
            lookup.modifiedAt?.let { modifiedAt ->
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_modified_label),
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(modifiedAt.toEpochMilliseconds()))
                )
            }

            // Permissions - only shown if provided (Explorer has this, Searcher doesn't)
            permissions?.let { perms ->
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_permissions_label),
                    value = perms.octal
                )
            }

            // Owner - only shown if provided (Explorer has this, Searcher doesn't)
            ownership?.let { owner ->
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_owner_label),
                    value = owner.userName ?: owner.userId.toString()
                )
            }

            // Created date - only shown if provided (Explorer has this, Searcher doesn't)
            createdAt?.let { created ->
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_created_label),
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(created.toEpochMilliseconds()))
                )
            }
        }

        // Symlink target info - shown if this is a symlink with target
        if (lookup.fileType == FileType.SYMBOLIC_LINK && lookup.target != null) {
            Spacer(modifier = Modifier.height(8.dp))
            InfoCard {
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_symlink_target_label),
                    value = lookup.target?.path ?: stringResource(R.string.workspace_file_info_unknown),
                    isCopyable = lookup.target != null,
                    onCopy = { lookup.target?.path?.let { onCopyToClipboard(it) } }
                )
            }
        }
    }
}

/**
 * Bottom sheet for displaying multiple selected items info with aggregated stats.
 */
@Composable
fun MultipleItemsInfoBottomSheet(
    totalCount: Int,
    fileCount: Int,
    directoryCount: Int,
    totalSize: Long?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInPreview = LocalInspectionMode.current

    if (isInPreview) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            MultipleItemsInfoContent(
                totalCount = totalCount,
                fileCount = fileCount,
                directoryCount = directoryCount,
                totalSize = totalSize,
            )
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier,
        ) {
            MultipleItemsInfoContent(
                totalCount = totalCount,
                fileCount = fileCount,
                directoryCount = directoryCount,
                totalSize = totalSize,
            )
        }
    }
}

@Composable
private fun MultipleItemsInfoContent(
    totalCount: Int,
    fileCount: Int,
    directoryCount: Int,
    totalSize: Long?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title
        Text(
            text = stringResource(R.string.workspace_file_info_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        InfoCard {
            InfoRow(
                label = stringResource(R.string.workspace_file_info_selected_label),
                value = totalCount.toString()
            )

            if (fileCount > 0) {
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_files_label),
                    value = fileCount.toString()
                )
            }

            if (directoryCount > 0) {
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_directories_label),
                    value = directoryCount.toString()
                )
            }

            totalSize?.let { size ->
                InfoRow(
                    label = stringResource(R.string.workspace_file_info_total_size_label),
                    value = formatFileSize(size)
                )
            }
        }
    }
}

// Helper composables

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

private enum class InfoValueStyle {
    NORMAL,
    MONOSPACE
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isCopyable: Boolean = false,
    onCopy: (() -> Unit)? = null,
    valueStyle: InfoValueStyle = InfoValueStyle.NORMAL,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )

        Row(
            modifier = Modifier.weight(0.65f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (valueStyle == InfoValueStyle.MONOSPACE) FontFamily.Monospace else FontFamily.Default
                ),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = if (valueStyle == InfoValueStyle.MONOSPACE) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (isCopyable && onCopy != null) {
                Icon(
                    imageVector = Icons.TwoTone.ContentCopy,
                    contentDescription = stringResource(R.string.workspace_file_info_copy_action),
                    modifier = Modifier
                        .size(20.dp)
                        .padding(start = 8.dp)
                        .clickable { onCopy() },
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// Previews

@Preview2
@Composable
private fun FileInfoBottomSheetPreviewFile() {
    PreviewWrapper {
        val mockLookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Documents/test.txt"),
            fileType = FileType.FILE,
            size = 1024L * 50,
            modifiedAt = Clock.System.now()
        )

        FileInfoBottomSheet(
            fileInfo = FileInfo(
                lookup = mockLookup,
                mimeInfo = MimeInfo("text/plain"),
            ),
            onDismiss = {},
            onCopyToClipboard = {},
        )
    }
}

@Preview2
@Composable
private fun FileInfoBottomSheetPreviewDirectory() {
    PreviewWrapper {
        val mockLookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Documents"),
            fileType = FileType.DIRECTORY,
            size = 0,
            modifiedAt = Clock.System.now()
        )

        FileInfoBottomSheet(
            fileInfo = FileInfo(
                lookup = mockLookup,
                childCount = 42,
            ),
            onDismiss = {},
            onCopyToClipboard = {},
        )
    }
}

@Preview2
@Composable
private fun MultipleItemsInfoBottomSheetPreview() {
    PreviewWrapper {
        MultipleItemsInfoBottomSheet(
            totalCount = 15,
            fileCount = 12,
            directoryCount = 3,
            totalSize = 1024L * 1024 * 50,
            onDismiss = {}
        )
    }
}
