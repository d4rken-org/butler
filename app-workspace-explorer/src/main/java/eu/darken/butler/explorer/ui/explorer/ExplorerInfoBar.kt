package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckBox
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerLocation

@Composable
fun ExplorerInfoBar(
    modifier: Modifier = Modifier,
    info: ExplorerLocation.LocationInfo?,
    selectedCount: Int = 0,
    onClearSelection: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection chip (always first when present)
        if (selectedCount > 0) {
            InfoChip(
                icon = Icons.TwoTone.CheckBox,
                label = pluralStringResource(R.plurals.explorer_infobar_selected_count, selectedCount, selectedCount),
                isAccented = true,
                onClick = onClearSelection,
                trailingIcon = Icons.TwoTone.Close,
            )
        }

        when (info) {
            is ExplorerLocation.Directory.Info -> {
                if (selectedCount == 0) {
                    if (info.directoryCount != null && info.directoryCount > 0) {
                        InfoChip(
                            icon = Icons.TwoTone.Folder,
                            label = pluralStringResource(
                                R.plurals.explorer_infobar_folders_count,
                                info.directoryCount,
                                info.directoryCount
                            ),
                        )
                    }
                    if (info.fileCount != null && info.fileCount > 0) {
                        InfoChip(
                            icon = Icons.TwoTone.Description,
                            label = pluralStringResource(
                                R.plurals.explorer_infobar_files_count,
                                info.fileCount,
                                info.fileCount
                            ),
                        )
                    }
                    if (info.directoryCount == 0 && info.fileCount == 0) {
                        InfoChip(
                            icon = Icons.TwoTone.Folder,
                            label = stringResource(R.string.explorer_empty_folder_label),
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                if (info.totalSize != null && selectedCount == 0) {
                    InfoChip(
                        icon = Icons.TwoTone.Storage,
                        label = formatFileSize(info.totalSize),
                    )
                }

                if (info.volumeFreeSpace != null) {
                    InfoChip(
                        icon = Icons.TwoTone.Storage,
                        label = stringResource(
                            R.string.explorer_infobar_storage_free,
                            formatFileSize(info.volumeFreeSpace)
                        ),
                    )
                }
            }

            is ExplorerLocation.Home.Info -> {
                InfoChip(
                    icon = Icons.TwoTone.Home,
                    label = pluralStringResource(
                        R.plurals.explorer_infobar_shortcuts_count,
                        info.shortcutCount,
                        info.shortcutCount
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                if (info.totalDeviceStorage != null && info.usedStorage != null) {
                    val freeSpace = info.totalDeviceStorage - info.usedStorage
                    InfoChip(
                        icon = Icons.TwoTone.Storage,
                        label = stringResource(R.string.explorer_infobar_storage_free, formatFileSize(freeSpace)),
                    )
                }
            }

            is ExplorerLocation.Device.Info -> {
                InfoChip(
                    icon = Icons.TwoTone.Storage,
                    label = pluralStringResource(
                        R.plurals.explorer_infobar_location_count,
                        info.locationCount,
                        info.locationCount
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                if (info.totalCapacity != null && info.usedSpace != null) {
                    val freeSpace = info.totalCapacity - info.usedSpace
                    InfoChip(
                        icon = Icons.TwoTone.Storage,
                        label = stringResource(R.string.explorer_infobar_storage_free, formatFileSize(freeSpace)),
                    )
                }
            }

            null -> {
                // No info available, just show spacer
            }
        }
    }
}

@Composable
private fun InfoChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    isAccented: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
) {
    AssistChip(
        onClick = onClick ?: {},
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
        },
        trailingIcon = trailingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
        },
        modifier = modifier.height(24.dp),
        border = null,
        colors = if (isAccented) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primary,
                labelColor = MaterialTheme.colorScheme.onPrimary,
                leadingIconContentColor = MaterialTheme.colorScheme.onPrimary,
                trailingIconContentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    )
}


@Preview2
@Composable
private fun ExplorerInfoBarDirectoryPreview() {
    PreviewWrapper {
        ExplorerInfoBar(
            info = ExplorerLocation.Directory.Info(
                fileCount = 42,
                directoryCount = 7,
                totalSize = 1024L * 1024L * 512L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 25L,
                volumeTotalSpace = 1024L * 1024L * 1024L * 128L,
                isWritable = true,
            ),
            selectedCount = 0,
        )
    }
}

@Preview2
@Composable
private fun ExplorerInfoBarWithSelectionPreview() {
    PreviewWrapper {
        ExplorerInfoBar(
            info = ExplorerLocation.Directory.Info(
                fileCount = 42,
                directoryCount = 7,
                totalSize = 1024L * 1024L * 512L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 25L,
                volumeTotalSpace = 1024L * 1024L * 1024L * 128L,
                isWritable = true,
            ),
            selectedCount = 3,
        )
    }
}

@Preview2
@Composable
private fun ExplorerInfoBarEmptyFolderPreview() {
    PreviewWrapper {
        ExplorerInfoBar(
            info = ExplorerLocation.Directory.Info(
                fileCount = 0,
                directoryCount = 0,
                totalSize = 0L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 50L,
                volumeTotalSpace = 1024L * 1024L * 1024L * 128L,
                isWritable = true,
            ),
            selectedCount = 0,
        )
    }
}

@Preview2
@Composable
private fun ExplorerInfoBarHomePreview() {
    PreviewWrapper {
        ExplorerInfoBar(
            info = ExplorerLocation.Home.Info(
                shortcutCount = 5,
                totalDeviceStorage = 1024L * 1024L * 1024L * 128L,
                usedStorage = 1024L * 1024L * 1024L * 78L,
            ),
            selectedCount = 0,
        )
    }
}

@Preview2
@Composable
private fun ExplorerInfoBarDevicePreview() {
    PreviewWrapper {
        ExplorerInfoBar(
            info = ExplorerLocation.Device.Info(
                locationCount = 2,
                totalCapacity = 1024L * 1024L * 1024L * 256L,
                usedSpace = 1024L * 1024L * 1024L * 120L,
            ),
            selectedCount = 0,
        )
    }
}