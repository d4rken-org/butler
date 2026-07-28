package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
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
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
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
            InfoField(
                label = stringResource(R.string.workspace_file_info_name_label),
                value = lookup.name,
                onCopy = { onCopyToClipboard(lookup.name) },
            )

            // Path - always shown, copyable
            InfoField(
                label = stringResource(R.string.workspace_file_info_path_label),
                value = lookup.path,
                onCopy = { onCopyToClipboard(lookup.path) },
                valueStyle = InfoValueStyle.MONOSPACE,
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
            InfoField(
                label = stringResource(R.string.workspace_file_info_type_label),
                value = typeValue,
            )

            // Size - always shown for files, shown for directories if available
            if (lookup.fileType == FileType.FILE || (lookup.size ?: 0) > 0) {
                InfoField(
                    label = stringResource(R.string.workspace_file_info_size_label),
                    value = formatFileSize(lookup.size ?: 0),
                )
            }

            // Child count - only for directories, if provided
            if (lookup.fileType == FileType.DIRECTORY && childCount != null) {
                InfoField(
                    label = stringResource(R.string.workspace_file_info_child_count_label),
                    value = childCount.toString(),
                )
            }

            // Modified date - always shown if available
            lookup.modifiedAt?.let { modifiedAt ->
                InfoField(
                    label = stringResource(R.string.workspace_file_info_modified_label),
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(modifiedAt.toEpochMilliseconds())),
                )
            }

            // Permissions - only shown if provided (Explorer has this, Searcher doesn't)
            permissions?.let { perms ->
                InfoField(
                    label = stringResource(R.string.workspace_file_info_permissions_label),
                    value = perms.octal,
                    valueStyle = InfoValueStyle.MONOSPACE,
                )
            }

            // Owner - only shown if provided (Explorer has this, Searcher doesn't)
            ownership?.let { owner ->
                InfoField(
                    label = stringResource(R.string.workspace_file_info_owner_label),
                    value = owner.userName ?: owner.userId.toString(),
                )
            }

            // Created date - only shown if provided (Explorer has this, Searcher doesn't)
            createdAt?.let { created ->
                InfoField(
                    label = stringResource(R.string.workspace_file_info_created_label),
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(created.toEpochMilliseconds())),
                )
            }
        }

        // Symlink target info - shown if this is a symlink with target
        if (lookup.fileType == FileType.SYMBOLIC_LINK && lookup.target != null) {
            Spacer(modifier = Modifier.height(8.dp))
            InfoCard {
                InfoField(
                    label = stringResource(R.string.workspace_file_info_symlink_target_label),
                    value = lookup.target?.path ?: stringResource(R.string.workspace_file_info_unknown),
                    onCopy = lookup.target?.path?.let { targetPath -> { onCopyToClipboard(targetPath) } },
                    valueStyle = InfoValueStyle.MONOSPACE,
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
        MultipleItemsInfoContent(
            totalCount = totalCount,
            fileCount = fileCount,
            directoryCount = directoryCount,
            totalSize = totalSize,
        )
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
            InfoField(
                label = stringResource(R.string.workspace_file_info_selected_label),
                value = totalCount.toString(),
            )

            if (fileCount > 0) {
                InfoField(
                    label = stringResource(R.string.workspace_file_info_files_label),
                    value = fileCount.toString(),
                )
            }

            if (directoryCount > 0) {
                InfoField(
                    label = stringResource(R.string.workspace_file_info_directories_label),
                    value = directoryCount.toString(),
                )
            }

            totalSize?.let { size ->
                InfoField(
                    label = stringResource(R.string.workspace_file_info_total_size_label),
                    value = formatFileSize(size),
                )
            }
        }
    }
}

// Previews

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FileInfoBottomSheetPreviewFile() {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FileInfoBottomSheetPreviewDirectory() {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MultipleItemsInfoBottomSheetPreview() {
    MultipleItemsInfoBottomSheet(
        totalCount = 15,
        fileCount = 12,
        directoryCount = 3,
        totalSize = 1024L * 1024 * 50,
        onDismiss = {}
    )
}
