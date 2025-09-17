package eu.darken.butler.explorer.ui.explorer.conflicts

import android.R.attr.onClick
import android.widget.Button
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
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.operations.conflicts.Conflict
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Composable
fun PathAlreadyExistsConflictSheet(
    conflict: Conflict.PathAlreadyExists,
    onResolution: (Conflict.PathAlreadyExists.Resolution) -> Unit,
    modifier: Modifier = Modifier,
) {
    var applyToAll by remember { mutableStateOf(false) }
    var showRenameNewDialog by remember { mutableStateOf(false) }
    var showRenameExistingDialog by remember { mutableStateOf(false) }
    val isDirectory = conflict.destination.fileType == FileType.DIRECTORY

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (isDirectory) R.string.explorer_conflict_collision_title_folder
                else R.string.explorer_conflict_collision_title_file
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        Text(
            text = stringResource(
                if (isDirectory) R.string.explorer_conflict_collision_existing_folder_label
                else R.string.explorer_conflict_collision_existing_file_label
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathConflictFileComparisonCard(lookup = conflict.destination)

        conflict.source?.let { source ->
            Text(
                text = stringResource(
                    if (isDirectory) R.string.explorer_conflict_collision_new_folder
                    else R.string.explorer_conflict_collision_new_file
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PathConflictFileComparisonCard(lookup = source)
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
                text = stringResource(R.string.explorer_conflict_apply_all),
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
                if (conflict.canSkip) {
                    Button(
                        onClick = {
                            onResolution(Conflict.PathAlreadyExists.Resolution.Skip(applyToAll))
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
                            Text(stringResource(R.string.explorer_conflict_common_skip))
                        }
                    }
                }

                if (isDirectory && conflict.canMerge) {
                    OutlinedButton(
                        onClick = {
                            onResolution(Conflict.PathAlreadyExists.Resolution.Merge(applyToAll))
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
                            Text(stringResource(R.string.explorer_conflict_collision_merge))
                        }
                    }
                } else if (conflict.canOverwrite) {
                    OutlinedButton(
                        onClick = {
                            onResolution(Conflict.PathAlreadyExists.Resolution.Overwrite(applyToAll))
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
                            Text(stringResource(R.string.explorer_conflict_collision_overwrite))
                        }
                    }
                }
            }

            // Rename options row
            if (conflict.canRenameNew || conflict.canRenameExisting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (conflict.canRenameNew) {
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
                                Text(stringResource(R.string.explorer_conflict_common_rename_new))
                            }
                        }
                    }

                    if (conflict.canRenameExisting) {
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
                                Text(stringResource(R.string.explorer_conflict_common_rename_existing))
                            }
                        }
                    }
                }
            }

            // Cancel button
            TextButton(
                onClick = {
                    onResolution(Conflict.PathAlreadyExists.Resolution.Cancel)
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
                    Text(stringResource(R.string.explorer_conflict_common_cancel))
                }
            }
        }
    }

    // Rename new file dialog
    if (showRenameNewDialog) {
        val nameToRename = conflict.suggestedName ?: conflict.source?.name ?: conflict.destination.name
        PathConflictRenameDialog(
            currentName = nameToRename,
            dialogTitle = stringResource(R.string.explorer_rename_dialog_title_new),
            onConfirm = { newName ->
                onResolution(Conflict.PathAlreadyExists.Resolution.Rename(newName))
                showRenameNewDialog = false
            },
            onDismiss = { showRenameNewDialog = false },
        )
    }

    // Rename existing file dialog
    if (showRenameExistingDialog) {
        PathConflictRenameDialog(
            currentName = conflict.destination.name,
            dialogTitle = stringResource(R.string.explorer_rename_dialog_title_existing),
            onConfirm = { newName ->
                onResolution(Conflict.PathAlreadyExists.Resolution.RenameExisting(newName))
                showRenameExistingDialog = false
            },
            onDismiss = { showRenameExistingDialog = false },
        )
    }
}

@Preview2
@Composable
private fun PathAlreadyExistsConflictSheetFilePreview() {
    PreviewWrapper {
        PathAlreadyExistsConflictSheet(
            conflict = Conflict.PathAlreadyExists(
                conflictId = Uuid.random(),
                destination = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Download/document.pdf"),
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 3, // 3MB
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 86400000), // 1 day ago
                    target = null,
                ),
                source = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Desktop/document.pdf"),
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 5, // 5MB - newer, larger file
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000), // 1 hour ago
                    target = null,
                ),
                canSkip = true,
                canOverwrite = true,
                canRenameNew = true,
                canMerge = false,
            ),
            onResolution = {},
        )
    }
}

@Preview2
@Composable
private fun PathAlreadyExistsConflictSheetRenameOptionsPreview() {
    PreviewWrapper {
        PathAlreadyExistsConflictSheet(
            conflict = Conflict.PathAlreadyExists(
                conflictId = Uuid.random(),
                destination = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Downloads/document.pdf"),
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 2, // 2MB
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 86400000), // 1 day ago
                ),
                source = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Desktop/document.pdf"),
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 3, // 3MB
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000), // 1 hour ago
                ),
                canSkip = true,
                canOverwrite = true,
                canRenameNew = true,
                canRenameExisting = true, // Show both rename options
                canMerge = false,
            ),
            onResolution = {},
        )
    }
}

@Preview2
@Composable
private fun PathAlreadyExistsConflictSheetFolderPreview() {
    PreviewWrapper {
        PathAlreadyExistsConflictSheet(
            conflict = Conflict.PathAlreadyExists(
                conflictId = Uuid.random(),
                destination = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Pictures/Vacation"),
                    fileType = FileType.DIRECTORY,
                    size = 0,
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 86400000 * 7), // 1 week ago
                    target = null,
                ),
                source = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Desktop/Vacation"),
                    fileType = FileType.DIRECTORY,
                    size = 0,
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000), // 1 hour ago
                    target = null,
                ),
                canSkip = true,
                canOverwrite = true,
                canRenameNew = true,
                canMerge = true, // Directory can be merged
            ),
            onResolution = {},
        )
    }
}