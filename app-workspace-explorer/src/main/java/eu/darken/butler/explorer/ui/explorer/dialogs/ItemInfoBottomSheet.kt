package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.location.SAFLocation
import eu.darken.butler.common.files.toCaString
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import java.text.DateFormat
import java.util.Date
import kotlin.time.Clock

@Composable
fun ItemInfoBottomSheet(
    context: ExplorerDialogState.ItemInfo.InfoContext,
    onDismiss: () -> Unit,
    onCopyToClipboard: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val isInPreview = LocalInspectionMode.current

    if (isInPreview) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            ItemInfoContent(
                context = context,
                onCopyToClipboard = onCopyToClipboard,
            )
        }
    } else {
        PaneScopedBottomSheet(
            visible = true,
            onDismiss = onDismiss,
            bottomInset = bottomInset,
            modifier = modifier,
        ) {
            ItemInfoContent(
                context = context,
                onCopyToClipboard = onCopyToClipboard,
            )
        }
    }
}

@Composable
private fun ItemInfoContent(
    context: ExplorerDialogState.ItemInfo.InfoContext,
    onCopyToClipboard: (String) -> Unit,
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
            text = stringResource(R.string.explorer_info_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        when (context) {
            is ExplorerDialogState.ItemInfo.InfoContext.SingleFile -> {
                SingleFileInfo(item = context.item, onCopyToClipboard = onCopyToClipboard)
            }

            is ExplorerDialogState.ItemInfo.InfoContext.SingleDirectory -> {
                SingleDirectoryInfo(item = context.item, onCopyToClipboard = onCopyToClipboard)
            }

            is ExplorerDialogState.ItemInfo.InfoContext.SingleSAF -> {
                SingleSAFInfo(item = context.item, onCopyToClipboard = onCopyToClipboard)
            }

            is ExplorerDialogState.ItemInfo.InfoContext.MultipleItems -> {
                MultipleItemsInfo(context = context)
            }

            is ExplorerDialogState.ItemInfo.InfoContext.DeviceView -> {
                DeviceViewInfo(location = context.location)
            }

            is ExplorerDialogState.ItemInfo.InfoContext.HomeView -> {
                HomeViewInfo(location = context.location)
            }
        }
    }
}

@Composable
private fun SingleFileInfo(
    item: ExplorerItem.File,
    onCopyToClipboard: (String) -> Unit,
) {
    val context = LocalContext.current

    InfoCard {
        InfoRow(
            label = stringResource(R.string.explorer_info_name_label),
            value = item.lookup.name
        )

        InfoRow(
            label = stringResource(R.string.explorer_info_path_label),
            value = item.lookup.path,
            isCopyable = true,
            onCopy = { onCopyToClipboard(item.lookup.path) }
        )

        InfoRow(
            label = stringResource(R.string.explorer_info_type_label),
            value = item.mimeType.toCaString().get(context)
        )

        InfoRow(
            label = stringResource(R.string.explorer_info_size_label),
            value = item.lookup.size?.let { formatFileSize(it) } ?: "?"
        )

        item.lookup.modifiedAt?.let { modifiedAt ->
            InfoRow(
                label = stringResource(R.string.explorer_info_modified_label),
                value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(modifiedAt.toEpochMilliseconds()))
            )
        }

        item.permissions?.let { permissions ->
            InfoRow(
                label = stringResource(R.string.explorer_info_permissions_label),
                value = permissions.toReadableString()
            )
        }

        item.ownership?.let { ownership ->
            InfoRow(
                label = stringResource(R.string.explorer_info_owner_label),
                value = ownership.userName ?: ownership.userId.toString()
            )
        }

        item.createdAt?.let { createdAt ->
            InfoRow(
                label = stringResource(R.string.explorer_info_created_label),
                value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(createdAt.toEpochMilliseconds()))
            )
        }
    }

    if (item is ExplorerItem.SymbolicLink) {
        Spacer(modifier = Modifier.height(8.dp))
        InfoCard {
            InfoRow(
                label = stringResource(R.string.explorer_info_symlink_target_label),
                value = item.targetPath ?: stringResource(R.string.explorer_info_unknown),
                isCopyable = item.targetPath != null,
                onCopy = { item.targetPath?.let { onCopyToClipboard(it) } }
            )

            if (item.isBroken) {
                InfoRow(
                    label = stringResource(R.string.explorer_info_symlink_status_label),
                    value = stringResource(R.string.explorer_info_symlink_broken)
                )
            }
        }
    }
}

@Composable
private fun SingleDirectoryInfo(
    item: ExplorerItem.Directory,
    onCopyToClipboard: (String) -> Unit,
) {
    InfoCard {
        InfoRow(
            label = stringResource(R.string.explorer_info_name_label),
            value = item.lookup.name
        )

        InfoRow(
            label = stringResource(R.string.explorer_info_path_label),
            value = item.lookup.path,
            isCopyable = true,
            onCopy = { onCopyToClipboard(item.lookup.path) }
        )

        InfoRow(
            label = stringResource(R.string.explorer_info_type_label),
            value = stringResource(R.string.explorer_info_type_directory)
        )

        when (val count = item.childCount) {
            0 -> InfoRow(
                label = stringResource(R.string.explorer_info_child_count_label),
                value = stringResource(R.string.explorer_file_empty)
            )
            null -> {}
            else -> InfoRow(
                label = stringResource(R.string.explorer_info_child_count_label),
                value = count.toString()
            )
        }

        item.lookup.modifiedAt?.let { modifiedAt ->
            InfoRow(
                label = stringResource(R.string.explorer_info_modified_label),
                value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(modifiedAt.toEpochMilliseconds()))
            )
        }

        item.permissions?.let { permissions ->
            InfoRow(
                label = stringResource(R.string.explorer_info_permissions_label),
                value = permissions.toReadableString()
            )
        }

        item.ownership?.let { ownership ->
            InfoRow(
                label = stringResource(R.string.explorer_info_owner_label),
                value = ownership.userName ?: ownership.userId.toString()
            )
        }

        item.createdAt?.let { createdAt ->
            InfoRow(
                label = stringResource(R.string.explorer_info_created_label),
                value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(createdAt.toEpochMilliseconds()))
            )
        }
    }
}

@Composable
private fun SingleSAFInfo(
    item: ExplorerItem.Storage.SAF,
    onCopyToClipboard: (String) -> Unit,
) {
    val context = LocalContext.current

    InfoCard {
        InfoRow(
            label = stringResource(R.string.explorer_info_name_label),
            value = item.location.userLabel ?: item.location.displayName.get(context)
        )

        InfoRow(
            label = stringResource(R.string.explorer_info_saf_uri_label),
            value = item.location.treeUri.toString(),
            isCopyable = true,
            onCopy = { onCopyToClipboard(item.location.treeUri.toString()) },
            valueStyle = InfoValueStyle.MONOSPACE
        )

        val context = LocalContext.current
        val permissionText = buildString {
            if (item.location.hasReadPermission) append(context.getString(R.string.explorer_info_saf_permission_read))
            if (item.location.hasReadPermission && item.location.hasWritePermission) append(" + ")
            if (item.location.hasWritePermission) append(context.getString(R.string.explorer_info_saf_permission_write))
        }

        InfoRow(
            label = stringResource(R.string.explorer_info_saf_permissions_label),
            value = permissionText
        )

        InfoRow(
            label = stringResource(R.string.explorer_info_saf_granted_label),
            value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(item.location.grantedAt.toEpochMilliseconds()))
        )
    }
}

@Composable
private fun MultipleItemsInfo(
    context: ExplorerDialogState.ItemInfo.InfoContext.MultipleItems,
) {
    InfoCard {
        InfoRow(
            label = stringResource(R.string.explorer_info_selected_label),
            value = context.selectedItems.size.toString()
        )

        if (context.fileCount > 0) {
            InfoRow(
                label = stringResource(R.string.explorer_info_files_label),
                value = context.fileCount.toString()
            )
        }

        if (context.directoryCount > 0) {
            InfoRow(
                label = stringResource(R.string.explorer_info_directories_label),
                value = context.directoryCount.toString()
            )
        }

        context.totalSize?.let { size ->
            InfoRow(
                label = stringResource(R.string.explorer_info_total_size_label),
                value = formatFileSize(size)
            )
        }
    }
}

@Composable
private fun DeviceViewInfo(
    location: ExplorerLocation.Device,
) {
    InfoCard {
        location.info?.let { info ->
            InfoRow(
                label = stringResource(R.string.explorer_info_locations_label),
                value = info.locationCount.toString()
            )

            info.totalCapacity?.let { capacity ->
                InfoRow(
                    label = stringResource(R.string.explorer_info_total_capacity_label),
                    value = formatFileSize(capacity)
                )
            }

            info.usedSpace?.let { used ->
                InfoRow(
                    label = stringResource(R.string.explorer_info_used_space_label),
                    value = formatFileSize(used)
                )
            }

            if (info.totalCapacity != null && info.usedSpace != null) {
                val freeSpace = info.totalCapacity - info.usedSpace
                InfoRow(
                    label = stringResource(R.string.explorer_info_free_space_label),
                    value = formatFileSize(freeSpace)
                )

                val percentage = (info.usedSpace.toDouble() / info.totalCapacity * 100).toInt()
                InfoRow(
                    label = stringResource(R.string.explorer_info_usage_label),
                    value = "$percentage%"
                )
            }
        }
    }
}

@Composable
private fun HomeViewInfo(
    location: ExplorerLocation.Home,
) {
    InfoCard {
        location.info?.let { info ->
            InfoRow(
                label = stringResource(R.string.explorer_info_shortcuts_label),
                value = info.shortcutCount.toString()
            )

            info.totalDeviceStorage?.let { total ->
                InfoRow(
                    label = stringResource(R.string.explorer_info_device_storage_total_label),
                    value = formatFileSize(total)
                )
            }

            info.usedStorage?.let { used ->
                InfoRow(
                    label = stringResource(R.string.explorer_info_device_storage_used_label),
                    value = formatFileSize(used)
                )
            }

            if (info.totalDeviceStorage != null && info.usedStorage != null) {
                val freeSpace = info.totalDeviceStorage - info.usedStorage
                InfoRow(
                    label = stringResource(R.string.explorer_info_device_storage_free_label),
                    value = formatFileSize(freeSpace)
                )
            }
        }
    }
}

// InfoCard, InfoRow, and InfoValueStyle are now in InfoComponents.kt

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ItemInfoBottomSheetPreviewSingleFile() {
    val mockFile = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Documents/test.txt"),
            fileType = FileType.FILE,
            size = 1024L * 50,
            modifiedAt = Clock.System.now()
        ),
        mimeType = MimeInfo("text/plain")
    )

    ItemInfoBottomSheet(
        context = ExplorerDialogState.ItemInfo.InfoContext.SingleFile(mockFile),
        onDismiss = {},
        onCopyToClipboard = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ItemInfoBottomSheetPreviewSAF() {
    val uriString = "content://com.android.externalstorage.documents/tree/primary"
    val mockUri = SafUri.parse(uriString)
    val mockPath = SAFPath.build(uriString)

    val mockSAF = ExplorerItem.Storage.SAF(
        location = SAFLocation(
            id = "test-id",
            treeUri = mockUri,
            path = mockPath,
            hasReadPermission = true,
            hasWritePermission = true,
            grantedAt = Clock.System.now(),
            userLabel = "My SD Card"
        ),
        displayName = "My SD Card".toCaString(),
        displayIcon = Icons.TwoTone.FolderShared,
        target = eu.darken.butler.explorer.core.ExplorerNavigation.Target.Directory(mockPath)
    )

    ItemInfoBottomSheet(
        context = ExplorerDialogState.ItemInfo.InfoContext.SingleSAF(mockSAF),
        onDismiss = {},
        onCopyToClipboard = {}
    )
}
