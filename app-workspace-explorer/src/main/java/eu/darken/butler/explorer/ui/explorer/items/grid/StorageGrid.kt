package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Block
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.saf.location.SAFLocation
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
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
) {
    FileGridBase(
        modifier = modifier,
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        icon = {
            Icon(
                imageVector = item.displayIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = item.target.path.path,
        tertiaryText = run {
            val totalBytes = item.totalBytes
            val availableBytes = item.availableBytes
            when {
                totalBytes != null && availableBytes != null -> {
                    val context = LocalContext.current
                    val total = eu.darken.butler.common.formatFileSize(context, totalBytes, shortFormat = true)
                    val free = eu.darken.butler.common.formatFileSize(context, availableBytes, shortFormat = true)
                    val typeLabel = when (item) {
                        is ExplorerItem.Storage.Local -> stringResource(R.string.explorer_file_storage_local_label)
                        is ExplorerItem.Storage.SAF -> stringResource(R.string.explorer_file_storage_saf_label)
                    }
                    stringResource(R.string.explorer_file_storage_size_format, typeLabel, total, free)
                }
                else -> null
            }
        },
        backgroundColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        trailingContent = if (item is ExplorerItem.Storage.SAF) {
            { PermissionIndicator(item.location) }
        } else null
    )
}

@Preview2
@Composable
private fun StorageGridLocalPreview() {
    PreviewWrapper {
        StorageGrid(
            item = MockDataProvider.createMockStorageLocal(),
            onClick = {}
        )
    }
}

@Preview2
@Composable
private fun StorageGridSAFPreview() {
    PreviewWrapper {
        StorageGrid(
            item = MockDataProvider.createMockStorageSAF(),
            onClick = {}
        )
    }
}

@Preview2
@Composable
private fun StorageGridSAFReadOnlyPreview() {
    PreviewWrapper {
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

@Preview2
@Composable
private fun StorageGridSAFWriteOnlyPreview() {
    PreviewWrapper {
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
