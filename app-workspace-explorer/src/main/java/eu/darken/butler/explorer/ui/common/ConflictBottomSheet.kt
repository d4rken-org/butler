package eu.darken.butler.explorer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberStandardBottomSheetState
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
import kotlin.time.Duration.Companion.seconds
import java.util.Date

@Composable
fun ConflictBottomSheet(
    conflict: ExplorerError.FileConflict,
    onResolution: (ConflictResolution) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var applyToAll by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val isDirectory = conflict.source.fileType == FileType.DIRECTORY

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (isDirectory) R.string.explorer_conflict_title_folder 
                    else R.string.explorer_conflict_title_file
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            HorizontalDivider()

            // Existing file section
            Text(
                text = stringResource(
                    if (isDirectory) R.string.explorer_conflict_existing_folder 
                    else R.string.explorer_conflict_existing_file
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FileComparisonCard(lookup = conflict.destination)

            // New file section
            Text(
                text = stringResource(
                    if (isDirectory) R.string.explorer_conflict_new_folder 
                    else R.string.explorer_conflict_new_file
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FileComparisonCard(lookup = conflict.source)

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

            // Button grid layout
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { 
                            onResolution(ConflictResolution.Skip(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.explorer_conflict_skip))
                    }
                    
                    if (isDirectory) {
                        Button(
                            onClick = { 
                                onResolution(ConflictResolution.Merge(applyToAll))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.explorer_conflict_merge))
                        }
                    } else {
                        Button(
                            onClick = { 
                                onResolution(ConflictResolution.Overwrite(applyToAll))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.explorer_conflict_overwrite))
                        }
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isDirectory) {
                        OutlinedButton(
                            onClick = { 
                                onResolution(ConflictResolution.Overwrite(applyToAll))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.explorer_conflict_overwrite))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showRenameDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.explorer_conflict_rename))
                        }
                    }
                    
                    if (isDirectory) {
                        OutlinedButton(
                            onClick = { showRenameDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.explorer_conflict_rename))
                        }
                    } else {
                        TextButton(
                            onClick = { 
                                onResolution(ConflictResolution.Cancel)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.explorer_conflict_cancel))
                        }
                    }
                }
                
                if (isDirectory) {
                    TextButton(
                        onClick = { 
                            onResolution(ConflictResolution.Cancel)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.explorer_conflict_cancel))
                    }
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
    lookup: APathLookup<APath>,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when (lookup.fileType) {
                    FileType.DIRECTORY -> Icons.TwoTone.Folder
                    else -> Icons.TwoTone.Description
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            
            Text(
                text = lookup.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            
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
                text = dateFormat.format(Date(lookup.modifiedAt.toEpochMilliseconds())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                override val modifiedAt = kotlin.time.Clock.System.now() - 3600.seconds
                override val target: APath? = null
            }
        }
        
        val mockDestLookup = remember {
            object : APathLookup<APath> {
                override val lookedUp = LocalPath.build("/storage/emulated/0/Documents/document.pdf")
                override val fileType = FileType.FILE
                override val size = 1_800_000L
                override val modifiedAt = kotlin.time.Clock.System.now() - 86400.seconds
                override val target: APath? = null
            }
        }
        
        val mockConflict = ExplorerError.FileConflict(
            source = mockSourceLookup,
            destination = mockDestLookup
        )
        
        var applyToAll by remember { mutableStateOf(false) }
        var showRenameDialog by remember { mutableStateOf(false) }
        
        val bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Expanded,
            skipHiddenState = false
        )
        val scaffoldState = rememberBottomSheetScaffoldState(
            bottomSheetState = bottomSheetState
        )
        
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.explorer_conflict_title_file),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    HorizontalDivider()

                    Text(
                        text = stringResource(R.string.explorer_conflict_existing_file),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FileComparisonCard(lookup = mockConflict.destination)

                    Text(
                        text = stringResource(R.string.explorer_conflict_new_file),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FileComparisonCard(lookup = mockConflict.source)

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
                            OutlinedButton(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.explorer_conflict_skip))
                            }
                            
                            Button(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.explorer_conflict_overwrite))
                            }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showRenameDialog = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.explorer_conflict_rename))
                            }
                            
                            TextButton(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.explorer_conflict_cancel))
                            }
                        }
                    }
                }
            },
            sheetPeekHeight = 0.dp,
        ) { paddingValues ->
            // Background content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Background Content",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        if (showRenameDialog) {
            RenameDialog(
                currentName = mockConflict.source.name,
                onConfirm = { showRenameDialog = false },
                onDismiss = { showRenameDialog = false },
            )
        }
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
                override val modifiedAt = kotlin.time.Clock.System.now() - 7200.seconds
                override val target: APath? = null
            }
        }
        
        val mockDestLookup = remember {
            object : APathLookup<APath> {
                override val lookedUp = LocalPath.build("/storage/emulated/0/Pictures/Photos")
                override val fileType = FileType.DIRECTORY
                override val size = 0L
                override val modifiedAt = kotlin.time.Clock.System.now() - 172800.seconds
                override val target: APath? = null
            }
        }
        
        val mockConflict = ExplorerError.FileConflict(
            source = mockSourceLookup,
            destination = mockDestLookup
        )
        
        var applyToAll by remember { mutableStateOf(false) }
        var showRenameDialog by remember { mutableStateOf(false) }
        val isDirectory = true // This is a folder preview
        
        val bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Expanded,
            skipHiddenState = false
        )
        val scaffoldState = rememberBottomSheetScaffoldState(
            bottomSheetState = bottomSheetState
        )
        
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.explorer_conflict_title_folder),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    HorizontalDivider()

                    Text(
                        text = stringResource(R.string.explorer_conflict_existing_folder),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FileComparisonCard(lookup = mockConflict.destination)

                    Text(
                        text = stringResource(R.string.explorer_conflict_new_folder),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FileComparisonCard(lookup = mockConflict.source)

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
                            OutlinedButton(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.explorer_conflict_skip))
                            }
                            
                            Button(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.explorer_conflict_merge))
                            }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.explorer_conflict_overwrite))
                            }
                            
                            OutlinedButton(
                                onClick = { showRenameDialog = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.explorer_conflict_rename))
                            }
                        }
                        
                        TextButton(
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.explorer_conflict_cancel))
                        }
                    }
                }
            },
            sheetPeekHeight = 0.dp,
        ) { paddingValues ->
            // Background content  
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Background Content",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        if (showRenameDialog) {
            RenameDialog(
                currentName = mockConflict.source.name,
                onConfirm = { showRenameDialog = false },
                onDismiss = { showRenameDialog = false },
            )
        }
    }
}