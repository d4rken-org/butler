package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Block
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.icons.NetworkOffline
import eu.darken.butler.common.compose.icons.NetworkOnline
import eu.darken.butler.common.rememberMinuteTick
import eu.darken.butler.explorer.ui.explorer.items.AppIconImage
import eu.darken.butler.explorer.ui.explorer.items.ItemDecorations
import eu.darken.butler.common.files.saf.location.SAFLocation
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.common.storage.saf.StorageProviderApp
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.statusLabel
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun StorageRow(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Storage,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
    isEnabled: Boolean = true,
    decorations: ItemDecorations = ItemDecorations(),
) {
    val context = LocalContext.current

    FileRowBase(
        modifier = modifier,
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        isEnabled = isEnabled,
        decorations = decorations,
        leadingContent = {
            val providerApp = (item as? ExplorerItem.Storage.SAF)?.providerApp
            if (providerApp != null) {
                AppIconImage(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    pkg = providerApp,
                    fallback = { StorageIcon(item) },
                )
            } else {
                StorageIcon(item)
            }
        },
        primaryText = item.displayName.get(context),
        // A network path is a UUID, the location's own subtitle is what identifies it to the user.
        secondaryText = item.subtitle?.get(context) ?: item.target.path.userReadablePath.get(context),
        tertiaryText = run {
            val totalBytes = item.totalBytes
            val availableBytes = item.availableBytes
            when {
                totalBytes != null && availableBytes != null -> {
                    val total = eu.darken.butler.common.formatFileSize(context, totalBytes)
                    val free = eu.darken.butler.common.formatFileSize(context, availableBytes)
                    val typeLabel = when (item) {
                        is ExplorerItem.Storage.Local -> stringResource(R.string.explorer_file_storage_local_label)
                        is ExplorerItem.Storage.SAF -> stringResource(R.string.explorer_file_storage_saf_label)
                        is ExplorerItem.Storage.Network -> stringResource(R.string.explorer_network_storage_label)
                    }
                    stringResource(R.string.explorer_file_storage_size_format, typeLabel, total, free)
                }
                item is ExplorerItem.Storage.SAF -> stringResource(R.string.explorer_file_storage_saf_label)
                item is ExplorerItem.Storage.Local -> stringResource(R.string.explorer_file_storage_local_label)
                item is ExplorerItem.Storage.Network -> item.statusLabel(context, rememberMinuteTick())
                else -> null
            }
        },
        // Only a state worth acting on gets colour; "Available" is the normal case and stays muted.
        tertiaryColor = when {
            item !is ExplorerItem.Storage.Network -> null
            item.hasIssue -> MaterialTheme.colorScheme.error
            else -> null
        },
        trailingContent = when {
            item is ExplorerItem.Storage.SAF -> {
                { PermissionIndicator(item.location) }
            }

            item is ExplorerItem.Storage.Network -> {
                { NetworkStatusIndicator(item) }
            }

            else -> null
        }
    )
}

@Composable
private fun StorageIcon(item: ExplorerItem.Storage) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = item.displayIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Nothing is drawn while the probe is still checking, so the row does not flash a wrong verdict. */
@Composable
private fun NetworkStatusIndicator(item: ExplorerItem.Storage.Network) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.status == ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED) {
            Icon(
                imageVector = Icons.TwoTone.Lock,
                contentDescription = stringResource(R.string.explorer_network_sign_in_required_label),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
        when (item.endpoint.reachability) {
            SmbEndpointState.Reachability.CHECKING -> Unit

            SmbEndpointState.Reachability.REACHABLE -> Icon(
                imageVector = Icons.TwoTone.NetworkOnline,
                contentDescription = stringResource(R.string.explorer_network_status_available_label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )

            SmbEndpointState.Reachability.UNREACHABLE -> Icon(
                imageVector = Icons.TwoTone.NetworkOffline,
                contentDescription = stringResource(R.string.explorer_network_status_unavailable_label),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PermissionIndicator(location: SAFLocation) {
    // Only show indicators when permissions are limited
    if (location.hasReadPermission && location.hasWritePermission) return

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            // Read-only
            location.hasReadPermission -> Icon(
                imageVector = Icons.TwoTone.Visibility,
                contentDescription = stringResource(R.string.explorer_file_storage_saf_read_only_label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            // Write-only (rare but handle it)
            location.hasWritePermission -> Icon(
                imageVector = Icons.TwoTone.Edit,
                contentDescription = stringResource(R.string.explorer_file_storage_saf_write_only_label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            // No access (shouldn't happen but handle gracefully)
            else -> Icon(
                imageVector = Icons.TwoTone.Block,
                contentDescription = stringResource(R.string.explorer_file_storage_saf_no_access_label),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowLocalPreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageLocal(),
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowNetworkCheckingPreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageNetwork(),
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowNetworkReachablePreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageNetwork(
            endpoint = SmbEndpointState("192.168.1.50", SmbEndpointState.Reachability.REACHABLE),
        ),
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowNetworkUnreachablePreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageNetwork(
            endpoint = SmbEndpointState("192.168.1.50", SmbEndpointState.Reachability.UNREACHABLE),
        ),
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowNetworkUnreachableSincePreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageNetwork(
            endpoint = SmbEndpointState("192.168.1.50", SmbEndpointState.Reachability.UNREACHABLE),
            lastSeenAt = MockDataProvider.MockTimes.hoursAgo(3),
        ),
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowNetworkSignInRequiredPreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageNetwork(
            name = "Work NAS",
            status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
        ),
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowSAFPreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageSAF(),
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowSAFAppPreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageSAF(
            name = "Termux",
            treeUri = "content://com.termux.documents/tree/%2Fdata%2Fdata%2Fcom.termux%2Ffiles%2Fhome",
            providerApp = StorageProviderApp(packageName = "com.termux", appLabel = "Termux", lastUpdateTime = 0L),
        ),
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowSAFReadOnlyPreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageSAF(
            name = "SD Card (Read-only)",
            hasReadPermission = true,
            hasWritePermission = false
        ),
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageRowSAFWriteOnlyPreview() {
    StorageRow(
        item = MockDataProvider.createMockStorageSAF(
            name = "SD Card (Write-only)",
            hasReadPermission = false,
            hasWritePermission = true
        ),
        onClick = {}
    )
}
