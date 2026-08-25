package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Block
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.icons.NetworkOffline
import eu.darken.butler.common.compose.icons.NetworkOnline
import eu.darken.butler.explorer.ui.explorer.items.ItemDecorations
import eu.darken.butler.common.files.saf.location.SAFLocation
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.statusLabel
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
private fun PermissionIndicator(location: SAFLocation) {
    // Only show indicators when permissions are limited
    val hasFullAccess = location.hasReadPermission && location.hasWritePermission

    if (hasFullAccess) return

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            // Read-only
            location.hasReadPermission && !location.hasWritePermission -> {
                Icon(
                    imageVector = Icons.TwoTone.Visibility,
                    contentDescription = stringResource(R.string.explorer_file_storage_saf_read_only_label),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            // Write-only (rare but handle it)
            !location.hasReadPermission && location.hasWritePermission -> {
                Icon(
                    imageVector = Icons.TwoTone.Edit,
                    contentDescription = stringResource(R.string.explorer_file_storage_saf_write_only_label),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            // No access (shouldn't happen but handle gracefully)
            !location.hasReadPermission && !location.hasWritePermission -> {
                Icon(
                    imageVector = Icons.TwoTone.Block,
                    contentDescription = stringResource(R.string.explorer_file_storage_saf_no_access_label),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun StorageGrid(
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
    FileGridBase(
        modifier = modifier,
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        isEnabled = isEnabled,
        decorations = decorations,
        icon = {
            Icon(
                imageVector = item.displayIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = run {
            val context = LocalContext.current
            val totalBytes = item.totalBytes
            val availableBytes = item.availableBytes
            when {
                totalBytes != null && availableBytes != null -> {
                    val total = eu.darken.butler.common.formatFileSize(context, totalBytes, shortFormat = true)
                    val free = eu.darken.butler.common.formatFileSize(context, availableBytes, shortFormat = true)
                    val typeLabel = when (item) {
                        is ExplorerItem.Storage.Local -> stringResource(R.string.explorer_file_storage_local_label)
                        is ExplorerItem.Storage.SAF -> stringResource(R.string.explorer_file_storage_saf_label)
                        is ExplorerItem.Storage.Network -> stringResource(R.string.explorer_network_storage_label)
                    }
                    stringResource(R.string.explorer_file_storage_size_format, typeLabel, total, free)
                }
                // Network tiles carry their status in the trailing slot, icon and wording together.
                else -> null
            }
        },
        // A network path is a UUID, the location's own subtitle is what identifies it to the user.
        tertiaryText = item.subtitle?.get(LocalContext.current) ?: item.target.path.path,
        backgroundColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        trailingContent = when {
            item is ExplorerItem.Storage.SAF -> {
                { PermissionIndicator(item.location) }
            }

            // Icon plus wording together, because this slot replaces the tile's status text.
            item is ExplorerItem.Storage.Network -> {
                { NetworkStatusIndicator(item) }
            }

            else -> null
        }
    )
}

/**
 * The tile's own status text, since this slot takes the place of it in [FileGridBase].
 *
 * Nothing is drawn for the icon while the probe is still checking, so the tile does not flash a
 * wrong verdict; the wording still says so.
 */
@Composable
private fun NetworkStatusIndicator(item: ExplorerItem.Storage.Network) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (item.status == ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED) {
            Icon(
                imageVector = Icons.TwoTone.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
        when (item.endpoint.reachability) {
            SmbEndpointState.Reachability.CHECKING -> Unit

            SmbEndpointState.Reachability.REACHABLE -> Icon(
                imageVector = Icons.TwoTone.NetworkOnline,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )

            SmbEndpointState.Reachability.UNREACHABLE -> Icon(
                imageVector = Icons.TwoTone.NetworkOffline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = item.statusLabel(context),
            style = MaterialTheme.typography.labelSmall,
            color = if (item.hasIssue) MaterialTheme.colorScheme.error else Color.White,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageGridLocalPreview() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(3) {
            StorageGrid(
                item = MockDataProvider.createMockStorageLocal(),
                onClick = {}
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageGridNetworkPreview() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            StorageGrid(
                item = MockDataProvider.createMockStorageNetwork(),
                onClick = {}
            )
        }
        item {
            StorageGrid(
                item = MockDataProvider.createMockStorageNetwork(
                    endpoint = SmbEndpointState("192.168.1.50", SmbEndpointState.Reachability.REACHABLE),
                ),
                onClick = {}
            )
        }
        item {
            StorageGrid(
                item = MockDataProvider.createMockStorageNetwork(
                    endpoint = SmbEndpointState("192.168.1.50", SmbEndpointState.Reachability.UNREACHABLE),
                ),
                onClick = {}
            )
        }
        item {
            StorageGrid(
                item = MockDataProvider.createMockStorageNetwork(
                    name = "Work NAS",
                    status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
                    endpoint = SmbEndpointState("192.168.1.51", SmbEndpointState.Reachability.REACHABLE),
                ),
                onClick = {}
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageGridSAFPreview() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(3) {
            StorageGrid(
                item = MockDataProvider.createMockStorageSAF(),
                onClick = {}
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageGridSAFReadOnlyPreview() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(3) {
            StorageGrid(
                item = MockDataProvider.createMockStorageSAF(
                    name = "SD Card (Read-only)",
                    hasReadPermission = true,
                    hasWritePermission = false
                ),
                onClick = {}
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageGridSAFWriteOnlyPreview() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(3) {
            StorageGrid(
                item = MockDataProvider.createMockStorageSAF(
                    name = "SD Card (Write-only)",
                    hasReadPermission = false,
                    hasWritePermission = true
                ),
                onClick = {}
            )
        }
    }
}
