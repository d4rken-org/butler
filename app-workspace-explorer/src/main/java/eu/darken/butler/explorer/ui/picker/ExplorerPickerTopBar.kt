package eu.darken.butler.explorer.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.BreadcrumbBar
import eu.darken.butler.workspace.core.picker.PickerConfig

@Composable
fun ExplorerPickerTopBar(
    modifier: Modifier = Modifier,
    selection: PickerConfig.Selection,
    selectionCount: Int,
    breadcrumbs: List<ExplorerBreadcrumb>?,
    currentLocation: ExplorerLocation?,
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = modifier) {
        // Row 1: Action bar with Cancel and Select buttons
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cancel button (left) - subtle secondary action
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }

                // Select button (right) - prominent primary action
                FilledTonalButton(
                    onClick = onConfirm,
                    enabled = when (selection) {
                        is PickerConfig.Selection.DirectorySingle -> {
                            // Only enable for real directories, not virtual locations (Home, Device)
                            currentLocation is ExplorerLocation.Directory
                        }

                        is PickerConfig.Selection.DirectoryMulti,
                        is PickerConfig.Selection.MixedMulti -> {
                            // Enable if items selected OR viewing a real directory (for "Select Current" fallback)
                            selectionCount > 0 || currentLocation is ExplorerLocation.Directory
                        }

                        is PickerConfig.Selection.FileMulti -> selectionCount > 0

                        is PickerConfig.Selection.FileSingle -> false // Instant selection, no confirm needed
                    }
                ) {
                    Text(
                        text = when (selection) {
                            is PickerConfig.Selection.DirectorySingle -> stringResource(R.string.explorer_picker_select_this_folder_action)
                            is PickerConfig.Selection.DirectoryMulti,
                            is PickerConfig.Selection.MixedMulti -> {
                                if (selectionCount > 0) {
                                    pluralStringResource(R.plurals.explorer_picker_select_count_action, selectionCount, selectionCount)
                                } else {
                                    stringResource(R.string.explorer_picker_select_this_folder_action)
                                }
                            }

                            is PickerConfig.Selection.FileSingle -> stringResource(eu.darken.butler.common.R.string.general_done_action)
                            is PickerConfig.Selection.FileMulti -> pluralStringResource(
                                R.plurals.explorer_picker_select_count_action,
                                selectionCount,
                                selectionCount,
                            )
                        }
                    )
                }
            }
        }

        // Divider for visual separation
        HorizontalDivider()

        // Row 2: Breadcrumbs or fallback title
        if (breadcrumbs != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 1.dp,
            ) {
                BreadcrumbBar(
                    breadcrumbs = breadcrumbs,
                    onBreadcrumbClick = onBreadcrumbClick,
                    showBackground = false,
                )
            }
        } else {
            // Fallback to static title if breadcrumbs not available
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp,
            ) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    text = when (selection) {
                        is PickerConfig.Selection.DirectorySingle -> stringResource(R.string.explorer_picker_select_directory_title)
                        is PickerConfig.Selection.DirectoryMulti -> pluralStringResource(
                            R.plurals.explorer_picker_select_directories,
                            selectionCount,
                            selectionCount,
                        )

                        is PickerConfig.Selection.FileSingle -> stringResource(R.string.explorer_picker_select_file_title)
                        is PickerConfig.Selection.FileMulti -> pluralStringResource(
                            R.plurals.explorer_picker_select_files,
                            selectionCount,
                            selectionCount,
                        )

                        is PickerConfig.Selection.MixedMulti -> pluralStringResource(
                            R.plurals.explorer_picker_select_items,
                            selectionCount,
                            selectionCount,
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Preview2
@Composable
private fun ExplorerPickerTopBarDirectoryPreview() {
    PreviewWrapper {
        ExplorerPickerTopBar(
            selection = PickerConfig.Selection.DirectorySingle,
            selectionCount = 0,
            breadcrumbs = listOf(
                eu.darken.butler.explorer.core.BreadcrumbGenerator.HOME,
                ExplorerBreadcrumb(
                    label = "sdcard".toCaString(),
                    target = ExplorerNavigation.Target.Directory(
                        LocalPath.build("/sdcard")
                    )
                ),
                ExplorerBreadcrumb(
                    label = "Download".toCaString(),
                    target = ExplorerNavigation.Target.Directory(
                        LocalPath.build("/sdcard/Download")
                    )
                )
            ),
            currentLocation = ExplorerLocation.Directory(
                path = LocalPath.build("/sdcard/Download")
            ),
            onBreadcrumbClick = {},
            onCancel = {},
            onConfirm = {},
        )
    }
}

@Preview2
@Composable
private fun ExplorerPickerTopBarFileEmptyPreview() {
    PreviewWrapper {
        ExplorerPickerTopBar(
            selection = PickerConfig.Selection.FileMulti,
            selectionCount = 0,
            breadcrumbs = listOf(
                eu.darken.butler.explorer.core.BreadcrumbGenerator.HOME,
                ExplorerBreadcrumb(
                    label = "sdcard".toCaString(),
                    target = ExplorerNavigation.Target.Directory(
                        LocalPath.build("/sdcard")
                    )
                ),
                ExplorerBreadcrumb(
                    label = "Pictures".toCaString(),
                    target = ExplorerNavigation.Target.Directory(
                        LocalPath.build("/sdcard/Pictures")
                    )
                )
            ),
            currentLocation = ExplorerLocation.Directory(
                path = LocalPath.build("/sdcard/Pictures")
            ),
            onBreadcrumbClick = {},
            onCancel = {},
            onConfirm = {},
        )
    }
}

@Preview2
@Composable
private fun ExplorerPickerTopBarFileWithSelectionPreview() {
    PreviewWrapper {
        ExplorerPickerTopBar(
            selection = PickerConfig.Selection.FileMulti,
            selectionCount = 3,
            breadcrumbs = listOf(
                eu.darken.butler.explorer.core.BreadcrumbGenerator.HOME,
                ExplorerBreadcrumb(
                    label = "sdcard".toCaString(),
                    target = ExplorerNavigation.Target.Directory(
                        LocalPath.build("/sdcard")
                    )
                ),
                ExplorerBreadcrumb(
                    label = "Documents".toCaString(),
                    target = ExplorerNavigation.Target.Directory(
                        LocalPath.build("/sdcard/Documents")
                    )
                ),
                ExplorerBreadcrumb(
                    label = "Work".toCaString(),
                    target = ExplorerNavigation.Target.Directory(
                        LocalPath.build("/sdcard/Documents/Work")
                    )
                )
            ),
            currentLocation = ExplorerLocation.Directory(
                path = LocalPath.build("/sdcard/Documents/Work")
            ),
            onBreadcrumbClick = {},
            onCancel = {},
            onConfirm = {},
        )
    }
}
