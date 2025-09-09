package eu.darken.butler.explorer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.errors.ConflictResolution
import eu.darken.butler.explorer.core.errors.ExplorerError
import java.text.DateFormat
import java.time.Instant
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictBottomSheet(
    conflict: ExplorerError.FileConflict,
    onResolution: (ConflictResolution) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var applyToAll by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.explorer_conflict_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            HorizontalDivider()

            FileComparisonCard(
                label = stringResource(R.string.explorer_conflict_existing_file),
                lookup = conflict.destination,
            )

            FileComparisonCard(
                label = stringResource(R.string.explorer_conflict_new_file),
                lookup = conflict.source,
            )

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(
                    onClick = { 
                        onResolution(ConflictResolution.Skip(applyToAll))
                    },
                ) {
                    Text(stringResource(R.string.explorer_conflict_skip))
                }
                
                Button(
                    onClick = { 
                        onResolution(ConflictResolution.Overwrite(applyToAll))
                    },
                ) {
                    Text(stringResource(R.string.explorer_conflict_overwrite))
                }
                
                OutlinedButton(
                    onClick = { showRenameDialog = true },
                ) {
                    Text(stringResource(R.string.explorer_conflict_rename))
                }
                
                TextButton(
                    onClick = { 
                        onResolution(ConflictResolution.Cancel)
                    },
                ) {
                    Text(stringResource(R.string.explorer_conflict_cancel))
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = conflict.source.name,
            onConfirm = { newName ->
                onResolution(ConflictResolution.Rename(newName))
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
}

@Composable
private fun FileComparisonCard(
    label: String,
    lookup: APathLookup<out APath>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (lookup.fileType) {
                        FileType.DIRECTORY -> Icons.TwoTone.Folder
                        else -> Icons.TwoTone.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = lookup.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = when (lookup.fileType) {
                        FileType.FILE -> formatFileSize(lookup.size)
                        FileType.DIRECTORY -> stringResource(R.string.explorer_type_folder)
                        else -> "-"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                
                Text(
                    text = dateFormat.format(Date(lookup.modifiedAt.toEpochMilli())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf(currentName) }
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_conflict_rename)) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.explorer_rename_new_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { 
                    if (newName.isNotBlank()) {
                        onConfirm(newName)
                    }
                },
                enabled = newName.isNotBlank() && newName != currentName,
            ) {
                Text(stringResource(eu.darken.butler.common.R.string.general_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
            }
        },
    )
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

@Preview2
@Composable
fun ConflictBottomSheetPreview() {
    PreviewWrapper {
        // Create mock conflict data
        val mockSourceLookup = remember {
            object : APathLookup<APath> {
                override val lookedUp = LocalPath.build("/storage/emulated/0/Download/document.pdf")
                override val fileType = FileType.FILE
                override val size = 2_500_000L
                override val modifiedAt = Instant.now().minusSeconds(3600)
                override val target: APath? = null
            }
        }
        
        val mockDestLookup = remember {
            object : APathLookup<APath> {
                override val lookedUp = LocalPath.build("/storage/emulated/0/Documents/document.pdf")
                override val fileType = FileType.FILE
                override val size = 1_800_000L
                override val modifiedAt = Instant.now().minusSeconds(86400)
                override val target: APath? = null
            }
        }
        
        val mockConflict = ExplorerError.FileConflict(
            source = mockSourceLookup,
            destination = mockDestLookup
        )
        
        ConflictBottomSheet(
            conflict = mockConflict,
            onResolution = {},
            onDismiss = {}
        )
    }
}

@Preview2
@Composable
fun ConflictBottomSheetFolderPreview() {
    PreviewWrapper {
        // Create mock conflict data for folders
        val mockSourceLookup = remember {
            object : APathLookup<APath> {
                override val lookedUp = LocalPath.build("/storage/emulated/0/Download/Photos")
                override val fileType = FileType.DIRECTORY
                override val size = 0L
                override val modifiedAt = Instant.now().minusSeconds(7200)
                override val target: APath? = null
            }
        }
        
        val mockDestLookup = remember {
            object : APathLookup<APath> {
                override val lookedUp = LocalPath.build("/storage/emulated/0/Pictures/Photos")
                override val fileType = FileType.DIRECTORY
                override val size = 0L
                override val modifiedAt = Instant.now().minusSeconds(172800)
                override val target: APath? = null
            }
        }
        
        val mockConflict = ExplorerError.FileConflict(
            source = mockSourceLookup,
            destination = mockDestLookup
        )
        
        ConflictBottomSheet(
            conflict = mockConflict,
            onResolution = {},
            onDismiss = {}
        )
    }
}