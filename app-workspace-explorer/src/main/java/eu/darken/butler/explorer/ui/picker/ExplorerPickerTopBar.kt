package eu.darken.butler.explorer.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
import eu.darken.butler.explorer.core.picker.PickerConfig

@Composable
fun ExplorerPickerTopBar(
    modifier: Modifier = Modifier,
    selection: PickerConfig.Selection,
    selectionCount: Int,
    breadcrumbs: List<ExplorerBreadcrumb>?,
    currentLocation: ExplorerLocation?,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    saveAsFilename: String = "",
    onSaveAsFilenameChange: (String) -> Unit = {},
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val density = LocalDensity.current

    Column(modifier = modifier) {
        // Row 1: Action bar with Cancel and Select buttons (pinned, always visible)
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
                            // Enable for real directories OR when storage item selected at Device level
                            currentLocation is ExplorerLocation.Directory
                                || (currentLocation is ExplorerLocation.Device && selectionCount > 0)
                        }

                        is PickerConfig.Selection.SaveAs -> {
                            // Enable when at directory and filename is valid
                            val hasValidFilename = saveAsFilename.isNotBlank()
                            val atDirectory = currentLocation is ExplorerLocation.Directory
                                || (currentLocation is ExplorerLocation.Device && selectionCount > 0)
                            hasValidFilename && atDirectory
                        }

                        is PickerConfig.Selection.DirectoryMulti,
                        is PickerConfig.Selection.MixedMulti -> {
                            // Enable if items selected OR at real directory (for "Select Current" fallback)
                            // At Device level, only enable when storage items are selected
                            selectionCount > 0 || currentLocation is ExplorerLocation.Directory
                        }

                        is PickerConfig.Selection.FileMulti -> selectionCount > 0

                        is PickerConfig.Selection.FileSingle -> false // Instant selection, no confirm needed
                    }
                ) {
                    Text(
                        text = when (selection) {
                            is PickerConfig.Selection.DirectorySingle -> stringResource(R.string.explorer_picker_select_this_folder_action)
                            is PickerConfig.Selection.SaveAs -> stringResource(R.string.explorer_picker_save_here_action)
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

        // Row 2: Filename input for SaveAs mode (pinned, always visible)
        if (selection is PickerConfig.Selection.SaveAs) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    value = saveAsFilename,
                    onValueChange = onSaveAsFilenameChange,
                    label = { Text(stringResource(R.string.explorer_picker_save_as_filename_label)) },
                    placeholder = { Text(stringResource(R.string.explorer_picker_save_as_filename_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.Description,
                            contentDescription = null,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (saveAsFilename.isNotBlank()) onConfirm() }
                    ),
                )
            }
            HorizontalDivider()
        }

        // Row 3: Breadcrumbs or fallback title (collapsible when scrolling)
        if (breadcrumbs != null) {
            Surface(
                modifier = Modifier
                    .height(
                        with(density) {
                            (40.dp.toPx() + (scrollBehavior?.state?.heightOffset ?: 0f)).toDp()
                        }.coerceAtLeast(0.dp)
                    )
                    .clipToBounds(),
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
                modifier = Modifier
                    .height(
                        with(density) {
                            (52.dp.toPx() + (scrollBehavior?.state?.heightOffset ?: 0f)).toDp()
                        }.coerceAtLeast(0.dp)
                    )
                    .clipToBounds(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp,
            ) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    text = when (selection) {
                        is PickerConfig.Selection.DirectorySingle -> stringResource(R.string.explorer_picker_select_directory_title)
                        is PickerConfig.Selection.SaveAs -> stringResource(R.string.explorer_picker_save_as_title)
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

@Preview2
@Composable
private fun ExplorerPickerTopBarSaveAsPreview() {
    PreviewWrapper {
        ExplorerPickerTopBar(
            selection = PickerConfig.Selection.SaveAs(suggestedFilename = "shared_file.pdf"),
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
            saveAsFilename = "shared_file.pdf",
            onSaveAsFilenameChange = {},
            onBreadcrumbClick = {},
            onCancel = {},
            onConfirm = {},
        )
    }
}
