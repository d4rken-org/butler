package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.HourglassEmpty
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material.icons.twotone.Scale
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.InfoChip
import eu.darken.butler.workspace.ui.WorkspaceInfoBar

@Composable
fun ExplorerInfoBar(
    modifier: Modifier = Modifier,
    info: ExplorerLocation.LocationInfo?,
    isLoading: Boolean = false,
    progress: Progress.Data? = null,
    onCancel: () -> Unit = {},
    selectedCount: Int = 0,
    selectedSize: Long? = null,
    onClearSelection: () -> Unit = {},
    onSelectFolders: () -> Unit = {},
    onSelectFiles: () -> Unit = {},
    isTrashDisabled: Boolean = false,
) {
    val context = LocalContext.current
    WorkspaceInfoBar(
        modifier = modifier,
        selectedCount = selectedCount,
        onClearSelection = onClearSelection,
        leadingContent = {
            // Show progress chips when loading (all on left side for layout stability)
            if (progress != null) {
                InfoChip(
                    icon = Icons.TwoTone.HourglassEmpty,
                    label = stringResource(R.string.explorer_infobar_loading),
                    isAccented = true,
                    onClick = onCancel,
                    trailingIcon = Icons.TwoTone.Close,
                )
                val countDisplay = progress.count.displayValue.get(context)
                if (countDisplay.isNotEmpty()) {
                    InfoChip(
                        icon = Icons.TwoTone.Scale,
                        label = countDisplay,
                    )
                }
                val secondary = progress.secondary
                if (secondary != CaString.EMPTY) {
                    InfoChip(
                        icon = Icons.TwoTone.Info,
                        label = secondary.get(context),
                    )
                }
                return@WorkspaceInfoBar
            }

            when (info) {
                is ExplorerLocation.Directory.Info -> {
                    if (selectedCount == 0) {
                        if (info.directoryCount != null && info.directoryCount > 0) {
                            InfoChip(
                                icon = Icons.TwoTone.Folder,
                                label = pluralStringResource(
                                    CommonR.plurals.common_folders_count,
                                    info.directoryCount,
                                    info.directoryCount
                                ),
                                onClick = onSelectFolders,
                            )
                        }
                        if (info.fileCount != null && info.fileCount > 0) {
                            InfoChip(
                                icon = Icons.TwoTone.Description,
                                label = pluralStringResource(
                                    CommonR.plurals.common_files_count,
                                    info.fileCount,
                                    info.fileCount
                                ),
                                onClick = onSelectFiles,
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
                                    CommonR.plurals.common_folders_count,
                                    info.directoryCount,
                                    info.directoryCount
                                ),
                                onClick = onSelectFolders,
                            )
                        }
                        if (info.fileCount != null && info.fileCount > 0) {
                            InfoChip(
                                icon = Icons.TwoTone.Description,
                                label = pluralStringResource(
                                    CommonR.plurals.common_files_count,
                                    info.fileCount,
                                    info.fileCount
                                ),
                                onClick = onSelectFiles,
                            )
                        }
                    }
                }

                null -> {
                    if (isLoading) {
                        InfoChip(
                            icon = Icons.TwoTone.HourglassEmpty,
                            label = stringResource(R.string.explorer_infobar_loading),
                        )
                    }
                }
            }
        },
        trailingContent = {
            // No trailing content during loading (all progress chips on left)
            if (progress != null) return@WorkspaceInfoBar

            if (selectedCount > 0 && selectedSize != null) {
                Spacer(modifier = Modifier.weight(1f))
                InfoChip(
                    icon = Icons.TwoTone.Scale,
                    label = formatFileSize(selectedSize),
                    isAccented = true,
                )
                return@WorkspaceInfoBar
            }

            when (info) {
                is ExplorerLocation.Directory.Info -> {
                    Spacer(modifier = Modifier.weight(1f))

                    if (info.totalSize != null && selectedCount == 0) {
                        InfoChip(
                            icon = Icons.TwoTone.Scale,
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
                                CommonR.plurals.common_files_count,
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
private fun ExplorerInfoBarDirectoryPreview() = PreviewWrapper {
    ExplorerInfoBar(
        info = MockDataProvider.createMockDirectoryInfo(fileCount = 42, directoryCount = 7),
    )
}

@Preview2
@Composable
private fun ExplorerInfoBarWithSelectionPreview() = PreviewWrapper {
    ExplorerInfoBar(
        info = MockDataProvider.createMockDirectoryInfo(fileCount = 42, directoryCount = 7),
        selectedCount = 3,
        selectedSize = MockDataProvider.MockSizes.mb(128),
    )
}

@Preview2
@Composable
private fun ExplorerInfoBarEmptyFolderPreview() = PreviewWrapper {
    ExplorerInfoBar(info = MockDataProvider.createMockEmptyDirectoryInfo())
}

@Preview2
@Composable
private fun ExplorerInfoBarHomePreview() = PreviewWrapper {
    ExplorerInfoBar(info = MockDataProvider.createMockHomeInfo())
}

@Preview2
@Composable
private fun ExplorerInfoBarDevicePreview() = PreviewWrapper {
    ExplorerInfoBar(info = MockDataProvider.createMockDeviceInfo())
}

@Preview2
@Composable
private fun ExplorerInfoBarLoadingWithCountPreview() = PreviewWrapper {
    ExplorerInfoBar(
        info = null,
        progress = MockDataProvider.createMockProgress(secondary = "Loading folder content", current = 42, total = 150),
    )
}

@Preview2
@Composable
private fun ExplorerInfoBarLoadingIndeterminatePreview() = PreviewWrapper {
    ExplorerInfoBar(
        info = null,
        progress = MockDataProvider.createMockIndeterminateProgress(secondary = "Checking permissions"),
    )
}