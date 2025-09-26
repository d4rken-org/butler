package eu.darken.butler.explorer.ui.explorer.issues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.BorderColor
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import androidx.compose.material.icons.twotone.FolderZip
import androidx.compose.material.icons.twotone.SaveAs
import androidx.compose.material.icons.twotone.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun PathAlreadyExistsIssueSheet(
    issue: PathActionIssue.PathAlreadyExists,
    onResolution: (PathActionIssue.PathAlreadyExists.Resolution) -> Unit,
    modifier: Modifier = Modifier,
) {
    var applyToAll by remember { mutableStateOf(false) }
    var showRenameNewDialog by remember { mutableStateOf(false) }
    var showRenameExistingDialog by remember { mutableStateOf(false) }
    val isDirectory = issue.destination.fileType == FileType.DIRECTORY

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (isDirectory) R.string.explorer_issue_collision_title_folder
                else R.string.explorer_issue_collision_title_file
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        Text(
            text = stringResource(
                if (isDirectory) R.string.explorer_issue_collision_existing_folder_label
                else R.string.explorer_issue_collision_existing_file_label
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathIssueFileComparisonCard(lookup = issue.destination)

        issue.source?.let { source ->
            Text(
                text = stringResource(
                    if (isDirectory) R.string.explorer_issue_collision_new_folder
                    else R.string.explorer_issue_collision_new_file
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PathIssueFileComparisonCard(lookup = source)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = applyToAll,
                onCheckedChange = { applyToAll = it },
            )
            Text(
                text = stringResource(R.string.explorer_issue_apply_all),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Primary row: Skip and Merge/Overwrite
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (issue.canSkip) {
                    Button(
                        onClick = {
                            onResolution(PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.explorer_issue_common_skip))
                        }
                    }
                }

                if (isDirectory && issue.canMerge) {
                    OutlinedButton(
                        onClick = {
                            onResolution(PathActionIssue.PathAlreadyExists.Resolution.Merge(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.FolderZip,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.explorer_issue_collision_merge))
                        }
                    }
                } else if (issue.canOverwrite) {
                    OutlinedButton(
                        onClick = {
                            onResolution(PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.SaveAs,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.explorer_issue_collision_overwrite))
                        }
                    }
                }
            }

            // Rename options row
            if (issue.canRenameSource || issue.canRenameDestination) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (issue.canRenameSource) {
                        OutlinedButton(
                            onClick = { showRenameNewDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.DriveFileRenameOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(stringResource(R.string.explorer_issue_common_rename_new))
                            }
                        }
                    }

                    if (issue.canRenameDestination) {
                        OutlinedButton(
                            onClick = { showRenameExistingDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.BorderColor,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(stringResource(R.string.explorer_issue_common_rename_existing))
                            }
                        }
                    }
                }
            }

            // Cancel button
            TextButton(
                onClick = {
                    onResolution(PathActionIssue.PathAlreadyExists.Resolution.Cancel())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.explorer_issue_common_cancel))
                }
            }
        }
    }

    // Rename new file dialog
    if (showRenameNewDialog) {
        val nameToRename = issue.suggestedName ?: issue.source?.name ?: issue.destination.name
        PathIssueRenameDialog(
            currentName = nameToRename,
            dialogTitle = stringResource(R.string.explorer_rename_dialog_title_new),
            onConfirm = { newName ->
                onResolution(PathActionIssue.PathAlreadyExists.Resolution.RenameSource(newName))
                showRenameNewDialog = false
            },
            onDismiss = { showRenameNewDialog = false },
        )
    }

    // Rename existing file dialog
    if (showRenameExistingDialog) {
        PathIssueRenameDialog(
            currentName = issue.destination.name,
            dialogTitle = stringResource(R.string.explorer_rename_dialog_title_existing),
            onConfirm = { newName ->
                onResolution(PathActionIssue.PathAlreadyExists.Resolution.RenameDestination(newName))
                showRenameExistingDialog = false
            },
            onDismiss = { showRenameExistingDialog = false },
        )
    }
}

@Preview2
@Composable
private fun PathAlreadyExistsIssueSheetFilePreview() {
    PreviewWrapper {
        PathAlreadyExistsIssueSheet(
            issue = MockDataProvider.createMockPathExistsIssue(
                source = MockDataProvider.createMockPdfFile("document.pdf", sizeMB = 5, hoursAgo = 1),
                destination = MockDataProvider.createMockLocalPathLookup(
                    path = "/storage/emulated/0/Download/document.pdf",
                    sizeKB = 3 * 1024,
                    hoursAgo = 24
                )
            ),
            onResolution = {},
        )
    }
}

@Preview2
@Composable
private fun PathAlreadyExistsIssueSheetRenameOptionsPreview() {
    PreviewWrapper {
        PathAlreadyExistsIssueSheet(
            issue = PathActionIssue.PathAlreadyExists(
                destination = MockDataProvider.createMockPdfFile("document.pdf", sizeMB = 2, hoursAgo = 24),
                source = MockDataProvider.createMockLocalPathLookup(
                    path = "/storage/emulated/0/Desktop/document.pdf",
                    sizeKB = 3 * 1024,
                    hoursAgo = 1
                ),
                canSkip = true,
                canOverwrite = true,
                canRenameSource = true,
                canRenameDestination = true,
                canMerge = false,
            ),
            onResolution = {},
        )
    }
}

@Preview2
@Composable
private fun PathAlreadyExistsIssueSheetFolderPreview() {
    PreviewWrapper {
        PathAlreadyExistsIssueSheet(
            issue = PathActionIssue.PathAlreadyExists(
                destination = MockDataProvider.createMockLocalPathLookup(
                    path = "/storage/emulated/0/Pictures/Vacation",
                    fileType = FileType.DIRECTORY,
                    sizeKB = 0,
                    hoursAgo = 168 // 1 week
                ),
                source = MockDataProvider.createMockLocalPathLookup(
                    path = "/storage/emulated/0/Desktop/Vacation",
                    fileType = FileType.DIRECTORY,
                    sizeKB = 0,
                    hoursAgo = 1
                ),
                canSkip = true,
                canOverwrite = true,
                canRenameSource = true,
                canMerge = true,
            ),
            onResolution = {},
        )
    }
}