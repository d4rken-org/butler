package eu.darken.butler.workspace.ui.issues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.R
import kotlin.time.Instant

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
            .padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = issue.title.asComposable(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        Text(
            modifier = Modifier.padding(bottom = 8.dp),
            text = issue.description.asComposable(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(
                if (isDirectory) R.string.workspace_issue_collision_existing_folder_label
                else R.string.workspace_issue_collision_existing_file_label
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathIssueFileComparisonCard(lookup = issue.destination)

        issue.source?.let { source ->
            Text(
                text = stringResource(
                    if (isDirectory) R.string.workspace_issue_collision_new_folder
                    else R.string.workspace_issue_collision_new_file
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PathIssueFileComparisonCard(lookup = source)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .toggleable(
                    value = applyToAll,
                    onValueChange = { applyToAll = it }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(
                checked = applyToAll,
                onCheckedChange = null,
            )
            Text(
                text = stringResource(R.string.workspace_issue_apply_all),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Primary action: Skip
            if (issue.canSkip) {
                Button(
                    onClick = {
                        onResolution(PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll))
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                        Text(
                            text = stringResource(R.string.workspace_issue_common_skip),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Secondary actions: Merge and/or Overwrite
            if ((isDirectory && issue.canMerge) || issue.canOverwrite) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                                Text(
                                    text = stringResource(R.string.workspace_issue_collision_merge),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (issue.canOverwrite) {
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
                                Text(
                                    text = stringResource(R.string.workspace_issue_collision_overwrite),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                            onClick = {
                                if (applyToAll) {
                                    onResolution(
                                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource(
                                            issue.suggestedName!!,
                                            applyToAll = true
                                        )
                                    )
                                } else {
                                    showRenameNewDialog = true
                                }
                            },
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
                                Text(
                                    text = stringResource(R.string.workspace_issue_common_rename_new),
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                        }
                    }

                    if (issue.canRenameDestination) {
                        OutlinedButton(
                            onClick = {
                                if (applyToAll) {
                                    onResolution(
                                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination(
                                            issue.suggestedName!!,
                                            applyToAll = true
                                        )
                                    )
                                } else {
                                    showRenameExistingDialog = true
                                }
                            },
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
                                Text(
                                    text = stringResource(R.string.workspace_issue_common_rename_existing),
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
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
                    Text(
                        text = stringResource(R.string.workspace_issue_common_cancel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // Rename new file dialog
    if (showRenameNewDialog) {
        val originalName = issue.source?.name ?: issue.destination.name
        PathIssueRenameDialog(
            currentName = originalName,
            initialValue = issue.suggestedName,
            dialogTitle = stringResource(R.string.workspace_issue_rename_dialog_title_new),
            onConfirm = { newName ->
                onResolution(PathActionIssue.PathAlreadyExists.Resolution.RenameSource(newName, applyToAll = false))
                showRenameNewDialog = false
            },
            onDismiss = { showRenameNewDialog = false },
        )
    }

    // Rename existing file dialog
    if (showRenameExistingDialog) {
        PathIssueRenameDialog(
            currentName = issue.destination.name,
            dialogTitle = stringResource(R.string.workspace_issue_rename_dialog_title_existing),
            onConfirm = { newName ->
                onResolution(
                    PathActionIssue.PathAlreadyExists.Resolution.RenameDestination(
                        newName,
                        applyToAll = false
                    )
                )
                showRenameExistingDialog = false
            },
            onDismiss = { showRenameExistingDialog = false },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PathAlreadyExistsIssueSheetFilePreview() {
    PathAlreadyExistsIssueSheet(
        issue = PathActionIssue.PathAlreadyExists(
            source = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Desktop/document.pdf"),
                fileType = FileType.FILE,
                size = 5 * 1024 * 1024,
                modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000),
                target = null,
            ),
            destination = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Download/document.pdf"),
                fileType = FileType.FILE,
                size = 3 * 1024 * 1024,
                modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 86400000),
                target = null,
            ),
            canSkip = true,
            canOverwrite = true,
            canRenameSource = false,
            canRenameDestination = false,
        ),
        onResolution = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PathAlreadyExistsIssueSheetRenameOptionsPreview() {
    PathAlreadyExistsIssueSheet(
        issue = PathActionIssue.PathAlreadyExists(
            destination = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Download/document.pdf"),
                fileType = FileType.FILE,
                size = 2 * 1024 * 1024,
                modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 86400000),
                target = null,
            ),
            source = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Desktop/document.pdf"),
                fileType = FileType.FILE,
                size = 3 * 1024 * 1024,
                modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000),
                target = null,
            ),
            canSkip = true,
            canOverwrite = true,
            canRenameSource = true,
            canRenameDestination = true,
        ),
        onResolution = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PathAlreadyExistsIssueSheetFolderPreview() {
    PathAlreadyExistsIssueSheet(
        issue = PathActionIssue.PathAlreadyExists(
            destination = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Pictures/Vacation"),
                fileType = FileType.DIRECTORY,
                size = 0,
                modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 604800000), // 1 week
                target = null,
            ),
            source = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Desktop/Vacation"),
                fileType = FileType.DIRECTORY,
                size = 0,
                modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000),
                target = null,
            ),
            canSkip = true,
            canMerge = true,
            canOverwrite = true,
            canRenameSource = true,
            canRenameDestination = true,
        ),
        onResolution = {},
    )
}
