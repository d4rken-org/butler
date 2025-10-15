package eu.darken.butler.explorer.ui.picker

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.ui.explorer.BreadcrumbBar
import eu.darken.butler.workspace.core.picker.PickerConfig

@Composable
fun ExplorerPickerTopBar(
    modifier: Modifier = Modifier,
    selection: PickerConfig.Selection,
    selectionCount: Int,
    breadcrumbs: List<ExplorerBreadcrumb>?,
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            if (breadcrumbs != null) {
                BreadcrumbBar(
                    breadcrumbs = breadcrumbs,
                    onBreadcrumbClick = onBreadcrumbClick
                )
            } else {
                // TODO improve title texts
                // Fallback to static title if breadcrumbs not available
                Text(
                    text = when (selection) {
                        is PickerConfig.Selection.DirectorySingle -> stringResource(R.string.explorer_picker_select_directory_title)
                        is PickerConfig.Selection.DirectoryMulti -> if (selectionCount > 0) {
                            stringResource(R.string.explorer_picker_select_directories_count_title, selectionCount)
                        } else {
                            stringResource(R.string.explorer_picker_select_directories_title)
                        }
                        is PickerConfig.Selection.FileSingle -> stringResource(R.string.explorer_picker_select_file_title)
                        is PickerConfig.Selection.FileMulti -> if (selectionCount > 0) {
                            stringResource(R.string.explorer_picker_select_files_count_title, selectionCount)
                        } else {
                            stringResource(R.string.explorer_picker_select_files_title)
                        }
                    }
                )
            }
        },
        navigationIcon = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(eu.darken.butler.common.R.string.general_cancel_action))
            }
        },
        actions = {
            TextButton(
                onClick = onConfirm,
                enabled = when (selection) {
                    is PickerConfig.Selection.DirectorySingle -> true
                    is PickerConfig.Selection.DirectoryMulti,
                    is PickerConfig.Selection.FileMulti -> selectionCount > 0
                    is PickerConfig.Selection.FileSingle -> false // Instant selection, no confirm needed
                }
            ) {
                Text(
                    text = when (selection) {
                        is PickerConfig.Selection.DirectorySingle -> stringResource(R.string.explorer_picker_select_action)
                        is PickerConfig.Selection.DirectoryMulti,
                        is PickerConfig.Selection.FileSingle,
                        is PickerConfig.Selection.FileMulti -> stringResource(eu.darken.butler.common.R.string.general_done_action)
                    }
                )
            }
        }
    )
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
                eu.darken.butler.explorer.core.ExplorerBreadcrumb(
                    label = "sdcard".toCaString(),
                    target = eu.darken.butler.explorer.core.ExplorerNavigation.Target.Directory(
                        eu.darken.butler.common.files.LocalPath.build("/sdcard")
                    )
                ),
                eu.darken.butler.explorer.core.ExplorerBreadcrumb(
                    label = "Download".toCaString(),
                    target = eu.darken.butler.explorer.core.ExplorerNavigation.Target.Directory(
                        eu.darken.butler.common.files.LocalPath.build("/sdcard/Download")
                    )
                )
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
                eu.darken.butler.explorer.core.ExplorerBreadcrumb(
                    label = "sdcard".toCaString(),
                    target = eu.darken.butler.explorer.core.ExplorerNavigation.Target.Directory(
                        eu.darken.butler.common.files.LocalPath.build("/sdcard")
                    )
                ),
                eu.darken.butler.explorer.core.ExplorerBreadcrumb(
                    label = "Pictures".toCaString(),
                    target = eu.darken.butler.explorer.core.ExplorerNavigation.Target.Directory(
                        eu.darken.butler.common.files.LocalPath.build("/sdcard/Pictures")
                    )
                )
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
                eu.darken.butler.explorer.core.ExplorerBreadcrumb(
                    label = "sdcard".toCaString(),
                    target = eu.darken.butler.explorer.core.ExplorerNavigation.Target.Directory(
                        eu.darken.butler.common.files.LocalPath.build("/sdcard")
                    )
                ),
                eu.darken.butler.explorer.core.ExplorerBreadcrumb(
                    label = "Documents".toCaString(),
                    target = eu.darken.butler.explorer.core.ExplorerNavigation.Target.Directory(
                        eu.darken.butler.common.files.LocalPath.build("/sdcard/Documents")
                    )
                ),
                eu.darken.butler.explorer.core.ExplorerBreadcrumb(
                    label = "Work".toCaString(),
                    target = eu.darken.butler.explorer.core.ExplorerNavigation.Target.Directory(
                        eu.darken.butler.common.files.LocalPath.build("/sdcard/Documents/Work")
                    )
                )
            ),
            onBreadcrumbClick = {},
            onCancel = {},
            onConfirm = {},
        )
    }
}
