package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.workspace.ui.InfoChip
import eu.darken.butler.workspace.ui.WorkspaceInfoBar

@Composable
fun ExplorerInfoBar(
    modifier: Modifier = Modifier,
    info: ExplorerLocation.LocationInfo?,
    selectedCount: Int = 0,
    onClearSelection: () -> Unit = {},
    isTrashDisabled: Boolean = false,
) {
    WorkspaceInfoBar(
        modifier = modifier,
        selectedCount = selectedCount,
        onClearSelection = onClearSelection,
        leadingContent = {
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
                }

                is ExplorerLocation.Trash.Root.Info -> {
                    if (isTrashDisabled && selectedCount == 0) {
                        InfoChip(
                            icon = Icons.TwoTone.PauseCircle,
                            label = stringResource(R.string.explorer_trash_disabled_warning),
                        )
                    }
                }

                is ExplorerLocation.Trash.Nested.Info -> {
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
                    }
                }

                null -> {
                    // No info available
                }
            }
        },
        trailingContent = {
            when (info) {
                is ExplorerLocation.Directory.Info -> {
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
                    Spacer(modifier = Modifier.weight(1f))
                    if (info.totalCapacity != null && info.usedSpace != null) {
                        val freeSpace = info.totalCapacity - info.usedSpace
                        InfoChip(
                            icon = Icons.TwoTone.Storage,
                            label = stringResource(R.string.explorer_infobar_storage_free, formatFileSize(freeSpace)),
                        )
                    }
                }

                is ExplorerLocation.Trash.Root.Info -> {
                    if (selectedCount > 0) return@WorkspaceInfoBar
                    Spacer(modifier = Modifier.weight(1f))
                    if (info.totalSize > 0) {
                        InfoChip(
                            icon = Icons.TwoTone.Storage,
                            label = formatFileSize(info.totalSize),
                        )
                    }
                    if (info.itemCount > 0) {
                        InfoChip(
                            icon = Icons.TwoTone.Delete,
                            label = pluralStringResource(
                                R.plurals.explorer_infobar_files_count,
                                info.itemCount,
                                info.itemCount
                            ),
                        )
                    } else {
                        InfoChip(
                            icon = Icons.TwoTone.Delete,
                            label = stringResource(R.string.explorer_trash_empty_state),
                        )
                    }
                }

                is ExplorerLocation.Trash.Nested.Info -> {
                    if (selectedCount > 0) return@WorkspaceInfoBar
                    Spacer(modifier = Modifier.weight(1f))
                    if (info.totalSize != null && info.totalSize > 0) {
                        InfoChip(
                            icon = Icons.TwoTone.Storage,
                            label = formatFileSize(info.totalSize),
                        )
                    }
                }

                null -> {
                    // No trailing content
                }
            }
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