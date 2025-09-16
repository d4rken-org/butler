package eu.darken.butler.explorer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
    var showRenameDialog by remember { mutableStateOf(false) }
    val isDirectory = conflict.destination.fileType == FileType.DIRECTORY

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (conflict.canSkip) {
                    OutlinedButton(
                        onClick = {
                            onResolution(Conflict.PathAlreadyExists.Resolution.Skip(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.explorer_conflict_common_skip))
                    }
                }

                if (isDirectory && conflict.canMerge) {
                    Button(
                        onClick = {
                            onResolution(Conflict.PathAlreadyExists.Resolution.Merge(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.explorer_conflict_collision_merge))
                    }
                } else if (!isDirectory && conflict.canOverwrite) {
                    Button(
                        onClick = {
                            onResolution(Conflict.PathAlreadyExists.Resolution.Overwrite(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.explorer_conflict_collision_overwrite))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isDirectory && conflict.canOverwrite) {
                    OutlinedButton(
                        onClick = {
                            onResolution(Conflict.PathAlreadyExists.Resolution.Overwrite(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.explorer_conflict_collision_overwrite))
                    }
                } else if (!isDirectory && conflict.canRename) {
                    OutlinedButton(
                        onClick = { showRenameDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.explorer_conflict_common_rename))
                    }
                }

                if (isDirectory && conflict.canRename) {
                    OutlinedButton(
                        onClick = { showRenameDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.explorer_conflict_common_rename))
                    }
                } else if (!isDirectory) {
                    TextButton(
                        onClick = {
                            onResolution(Conflict.PathAlreadyExists.Resolution.Cancel)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.explorer_conflict_common_cancel))
                    }
                }
            }

            if (isDirectory) {
                TextButton(
                    onClick = {
                        onResolution(Conflict.PathAlreadyExists.Resolution.Cancel)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.explorer_conflict_common_cancel))
                }
            }
        }
    }

    if (showRenameDialog) {
        val nameToRename = conflict.suggestedName ?: conflict.source?.name ?: conflict.destination.name
        PathConflictRenameDialog(
            currentName = nameToRename,
            onConfirm = { newName ->
                onResolution(Conflict.PathAlreadyExists.Resolution.Rename(newName))
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
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
                canRename = true,
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
                canRename = true,
                canMerge = true, // Directory can be merged
            ),
            onResolution = {},
        )
    }
}