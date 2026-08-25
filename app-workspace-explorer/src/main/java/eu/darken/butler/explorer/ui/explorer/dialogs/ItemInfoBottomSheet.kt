package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material3.Card
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
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.toCaString
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.workspace.ui.dialogs.InfoCard
import eu.darken.butler.workspace.ui.dialogs.InfoField
import eu.darken.butler.workspace.ui.dialogs.InfoValueStyle
import kotlin.time.Clock

@Composable
fun ItemInfoBottomSheet(
    context: ExplorerDialogState.ItemInfo.InfoContext,
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
        ItemInfoContent(
            context = context,
            onCopyToClipboard = onCopyToClipboard,
        )
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
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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

            is ExplorerDialogState.ItemInfo.InfoContext.SingleLocalStorage -> {
                LocalStorageInfo(item = context.item, onCopyToClipboard = onCopyToClipboard)
            }

            is ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork -> {
                // Null while the location is on its way out of the listing, e.g. after a removal.
                context.item?.let { NetworkStorageInfo(item = it, onCopyToClipboard = onCopyToClipboard) }
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
        InfoField(
            label = stringResource(R.string.explorer_info_name_label),
            value = item.lookup.name,
        )

        InfoField(
            label = stringResource(R.string.explorer_info_path_label),
            value = item.lookup.path,
            onCopy = { onCopyToClipboard(item.lookup.path) },
            valueStyle = InfoValueStyle.MONOSPACE,
        )

        InfoField(
            label = stringResource(R.string.explorer_info_type_label),
            value = item.mimeType.toCaString().get(context),
        )

        InfoField(
            label = stringResource(R.string.explorer_info_size_label),
            value = item.lookup.size?.let { formatFileSize(it) } ?: "?",
        )

        item.lookup.modifiedAt?.let { modifiedAt ->
            InfoField(
                label = stringResource(R.string.explorer_info_modified_label),
                value = formatDateTime(modifiedAt, DateTimeStyle.DETAILED),
            )
        }

        item.permissions?.let { permissions ->
            InfoField(
                label = stringResource(R.string.explorer_info_permissions_label),
                value = permissions.toReadableString(),
                valueStyle = InfoValueStyle.MONOSPACE,
            )
        }

        item.ownership?.let { ownership ->
            InfoField(
                label = stringResource(R.string.explorer_info_owner_label),
                value = ownership.userName ?: ownership.userId.toString(),
            )
        }

        item.createdAt?.let { createdAt ->
            InfoField(
                label = stringResource(R.string.explorer_info_created_label),
                value = formatDateTime(createdAt, DateTimeStyle.DETAILED),
            )
        }
    }

    if (item is ExplorerItem.SymbolicLink) {
        Spacer(modifier = Modifier.height(6.dp))
        InfoCard {
            InfoField(
                label = stringResource(R.string.explorer_info_symlink_target_label),
                value = item.targetPath ?: stringResource(R.string.explorer_info_unknown),
                onCopy = item.targetPath?.let { targetPath -> { onCopyToClipboard(targetPath) } },
                valueStyle = InfoValueStyle.MONOSPACE,
            )

            if (item.isBroken) {
                InfoField(
                    label = stringResource(R.string.explorer_info_symlink_status_label),
                    value = stringResource(R.string.explorer_info_symlink_broken),
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
        InfoField(
            label = stringResource(R.string.explorer_info_name_label),
            value = item.lookup.name,
        )

        InfoField(
            label = stringResource(R.string.explorer_info_path_label),
            value = item.lookup.path,
            onCopy = { onCopyToClipboard(item.lookup.path) },
            valueStyle = InfoValueStyle.MONOSPACE,
        )

        InfoField(
            label = stringResource(R.string.explorer_info_type_label),
            value = stringResource(R.string.explorer_info_type_directory),
        )

        when (val count = item.childCount) {
            0 -> InfoField(
                label = stringResource(R.string.explorer_info_child_count_label),
                value = stringResource(R.string.explorer_file_empty),
            )
            null -> {}
            else -> InfoField(
                label = stringResource(R.string.explorer_info_child_count_label),
                value = count.toString(),
            )
        }

        item.lookup.modifiedAt?.let { modifiedAt ->
            InfoField(
                label = stringResource(R.string.explorer_info_modified_label),
                value = formatDateTime(modifiedAt, DateTimeStyle.DETAILED),
            )
        }

        item.permissions?.let { permissions ->
            InfoField(
                label = stringResource(R.string.explorer_info_permissions_label),
                value = permissions.toReadableString(),
                valueStyle = InfoValueStyle.MONOSPACE,
            )
        }

        item.ownership?.let { ownership ->
            InfoField(
                label = stringResource(R.string.explorer_info_owner_label),
                value = ownership.userName ?: ownership.userId.toString(),
            )
        }

        item.createdAt?.let { createdAt ->
            InfoField(
                label = stringResource(R.string.explorer_info_created_label),
                value = formatDateTime(createdAt, DateTimeStyle.DETAILED),
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
        InfoField(
            label = stringResource(R.string.explorer_info_name_label),
            value = item.location.userLabel ?: item.location.displayName.get(context),
        )

        InfoField(
            label = stringResource(R.string.explorer_info_saf_uri_label),
            value = item.location.treeUri.toString(),
            onCopy = { onCopyToClipboard(item.location.treeUri.toString()) },
            valueStyle = InfoValueStyle.MONOSPACE,
        )

        val context = LocalContext.current
        val permissionText = buildString {
            if (item.location.hasReadPermission) append(context.getString(R.string.explorer_info_saf_permission_read))
            if (item.location.hasReadPermission && item.location.hasWritePermission) append(" + ")
            if (item.location.hasWritePermission) append(context.getString(R.string.explorer_info_saf_permission_write))
        }

        InfoField(
            label = stringResource(R.string.explorer_info_saf_permissions_label),
            value = permissionText,
        )

        InfoField(
            label = stringResource(R.string.explorer_info_saf_granted_label),
            value = formatDateTime(item.location.grantedAt, DateTimeStyle.DETAILED),
        )
    }
}

/** Everything about a share except its password: no ciphertext, no length, no hint. */
@Composable
private fun NetworkStorageInfo(
    item: ExplorerItem.Storage.Network,
    onCopyToClipboard: (String) -> Unit,
) {
    val context = LocalContext.current
    val location = item.location

    InfoCard {
        InfoField(
            label = stringResource(R.string.explorer_info_name_label),
            value = item.displayName.get(context),
        )

        InfoField(
            label = stringResource(R.string.explorer_info_network_server_label),
            value = location.host,
            onCopy = { onCopyToClipboard(location.host) },
            valueStyle = InfoValueStyle.MONOSPACE,
        )

        InfoField(
            label = stringResource(R.string.explorer_info_network_port_label),
            value = location.port.toString(),
        )

        InfoField(
            label = stringResource(R.string.explorer_info_network_share_label),
            value = location.share,
        )

        if (location.basePath.isNotEmpty()) {
            InfoField(
                label = stringResource(R.string.explorer_info_network_folder_label),
                value = location.basePath.joinToString("/"),
                valueStyle = InfoValueStyle.MONOSPACE,
            )
        }

        val address = item.endpoint.address
        InfoField(
            label = stringResource(R.string.explorer_info_network_address_label),
            value = address ?: stringResource(R.string.explorer_info_unknown),
            onCopy = address?.let { { onCopyToClipboard(it) } },
            valueStyle = InfoValueStyle.MONOSPACE,
        )

        InfoField(
            label = stringResource(R.string.explorer_info_network_status_label),
            value = when (item.endpoint.reachability) {
                SmbEndpointState.Reachability.CHECKING -> {
                    stringResource(R.string.explorer_network_status_checking_label)
                }
                SmbEndpointState.Reachability.REACHABLE -> {
                    stringResource(R.string.explorer_network_status_available_label)
                }
                SmbEndpointState.Reachability.UNREACHABLE -> {
                    stringResource(R.string.explorer_network_status_unavailable_label)
                }
            },
        )
    }

    Spacer(modifier = Modifier.height(6.dp))

    InfoCard {
        InfoField(
            label = stringResource(R.string.explorer_info_network_auth_label),
            value = when (location.authType) {
                SmbLocation.AuthType.GUEST -> stringResource(R.string.explorer_network_form_auth_guest)
                SmbLocation.AuthType.PASSWORD -> stringResource(R.string.explorer_network_form_auth_password)
            },
        )

        if (location.authType == SmbLocation.AuthType.PASSWORD) {
            InfoField(
                label = stringResource(R.string.explorer_network_form_username_label),
                value = location.username ?: stringResource(R.string.explorer_info_unknown),
            )

            location.domain?.takeIf { it.isNotBlank() }?.let { domain ->
                InfoField(
                    label = stringResource(R.string.explorer_info_network_domain_label),
                    value = domain,
                )
            }

            // From the vault, not from the "remember password" switch: that one says what should be
            // kept, this one says what can actually be produced.
            InfoField(
                label = stringResource(R.string.explorer_info_network_password_label),
                value = when (item.credentials) {
                    SmbCredentialStore.Availability.AVAILABLE -> {
                        stringResource(R.string.explorer_info_network_password_available)
                    }
                    SmbCredentialStore.Availability.MISSING -> {
                        stringResource(R.string.explorer_info_network_password_missing)
                    }
                    SmbCredentialStore.Availability.KEY_UNAVAILABLE -> {
                        stringResource(R.string.explorer_info_network_password_locked)
                    }
                },
            )
        }

        InfoField(
            label = stringResource(R.string.explorer_info_network_added_label),
            value = formatDateTime(location.createdAt, DateTimeStyle.DETAILED),
        )

        InfoField(
            label = stringResource(R.string.explorer_info_network_updated_label),
            value = formatDateTime(location.updatedAt, DateTimeStyle.DETAILED),
        )
    }
}

@Composable
private fun LocalStorageInfo(
    item: ExplorerItem.Storage.Local,
    onCopyToClipboard: (String) -> Unit,
) {
    val context = LocalContext.current
    val path = item.target.path.path
    val totalBytes = item.totalBytes
    val availableBytes = item.availableBytes

    InfoCard {
        InfoField(
            label = stringResource(R.string.explorer_info_name_label),
            value = item.displayName.get(context),
        )

        InfoField(
            label = stringResource(R.string.explorer_info_path_label),
            value = path,
            onCopy = { onCopyToClipboard(path) },
            valueStyle = InfoValueStyle.MONOSPACE,
        )

        totalBytes?.let { total ->
            InfoField(
                label = stringResource(R.string.explorer_info_total_capacity_label),
                value = formatFileSize(total),
            )
        }

        if (totalBytes != null && availableBytes != null) {
            InfoField(
                label = stringResource(R.string.explorer_info_used_space_label),
                value = formatFileSize(totalBytes - availableBytes),
            )
        }

        availableBytes?.let { available ->
            InfoField(
                label = stringResource(R.string.explorer_info_free_space_label),
                value = formatFileSize(available),
            )
        }

        if (totalBytes != null && totalBytes > 0L && availableBytes != null) {
            val percentage = ((totalBytes - availableBytes).toDouble() / totalBytes * 100).toInt()
            InfoField(
                label = stringResource(R.string.explorer_info_usage_label),
                value = "$percentage%",
            )
        }
    }
}

@Composable
private fun MultipleItemsInfo(
    context: ExplorerDialogState.ItemInfo.InfoContext.MultipleItems,
) {
    InfoCard {
        InfoField(
            label = stringResource(R.string.explorer_info_selected_label),
            value = context.selectedItems.size.toString(),
        )

        if (context.fileCount > 0) {
            InfoField(
                label = stringResource(R.string.explorer_info_files_label),
                value = context.fileCount.toString(),
            )
        }

        if (context.directoryCount > 0) {
            InfoField(
                label = stringResource(R.string.explorer_info_directories_label),
                value = context.directoryCount.toString(),
            )
        }

        context.totalSize?.let { size ->
            InfoField(
                label = stringResource(R.string.explorer_info_total_size_label),
                value = formatFileSize(size),
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
            InfoField(
                label = stringResource(R.string.explorer_info_locations_label),
                value = info.locationCount.toString(),
            )

            info.totalCapacity?.let { capacity ->
                InfoField(
                    label = stringResource(R.string.explorer_info_total_capacity_label),
                    value = formatFileSize(capacity),
                )
            }

            info.usedSpace?.let { used ->
                InfoField(
                    label = stringResource(R.string.explorer_info_used_space_label),
                    value = formatFileSize(used),
                )
            }

            if (info.totalCapacity != null && info.usedSpace != null) {
                val freeSpace = info.totalCapacity - info.usedSpace
                InfoField(
                    label = stringResource(R.string.explorer_info_free_space_label),
                    value = formatFileSize(freeSpace),
                )

                if (info.totalCapacity > 0L) {
                    val percentage = (info.usedSpace.toDouble() / info.totalCapacity * 100).toInt()
                    InfoField(
                        label = stringResource(R.string.explorer_info_usage_label),
                        value = "$percentage%",
                    )
                }
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
            InfoField(
                label = stringResource(R.string.explorer_info_shortcuts_label),
                value = info.shortcutCount.toString(),
            )

            info.totalDeviceStorage?.let { total ->
                InfoField(
                    label = stringResource(R.string.explorer_info_device_storage_total_label),
                    value = formatFileSize(total),
                )
            }

            info.usedStorage?.let { used ->
                InfoField(
                    label = stringResource(R.string.explorer_info_device_storage_used_label),
                    value = formatFileSize(used),
                )
            }

            if (info.totalDeviceStorage != null && info.usedStorage != null) {
                val freeSpace = info.totalDeviceStorage - info.usedStorage
                InfoField(
                    label = stringResource(R.string.explorer_info_device_storage_free_label),
                    value = formatFileSize(freeSpace),
                )
            }
        }
    }
}

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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ItemInfoBottomSheetPreviewNetworkReachable() {
    val item = MockDataProvider.createMockStorageNetwork(
        endpoint = SmbEndpointState("192.168.1.50", SmbEndpointState.Reachability.REACHABLE),
    )

    ItemInfoBottomSheet(
        context = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(item.location.id, item),
        onDismiss = {},
        onCopyToClipboard = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ItemInfoBottomSheetPreviewNetworkChecking() {
    val item = MockDataProvider.createMockStorageNetwork()

    ItemInfoBottomSheet(
        context = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(item.location.id, item),
        onDismiss = {},
        onCopyToClipboard = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ItemInfoBottomSheetPreviewNetworkSignInRequired() {
    val item = MockDataProvider.createMockStorageNetwork(
        status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
        endpoint = SmbEndpointState("192.168.1.50", SmbEndpointState.Reachability.UNREACHABLE),
    )

    ItemInfoBottomSheet(
        context = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(item.location.id, item),
        onDismiss = {},
        onCopyToClipboard = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ItemInfoBottomSheetPreviewLocalStorage() {
    ItemInfoBottomSheet(
        context = ExplorerDialogState.ItemInfo.InfoContext.SingleLocalStorage(
            MockDataProvider.createMockStorageLocal(),
        ),
        onDismiss = {},
        onCopyToClipboard = {}
    )
}
