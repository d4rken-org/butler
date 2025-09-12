package eu.darken.butler.common.picker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.picker.core.FilePickerConfig
import eu.darken.butler.common.picker.core.FilePickerState
import eu.darken.butler.common.picker.core.SelectionMode

@Composable
fun FilePickerCore(
    config: FilePickerConfig,
    state: FilePickerState,
    modifier: Modifier = Modifier,
    onNavigate: (APath) -> Unit,
    onItemClick: (FilePickerState.FileItem) -> Unit,
    onItemLongClick: (FilePickerState.FileItem) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onCreateFolder: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Breadcrumbs
        FilePickerBreadcrumbs(
            currentPath = state.currentPath,
            onNavigate = onNavigate,
            modifier = Modifier.fillMaxWidth()
        )
        
        HorizontalDivider()
        
        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                state.error != null -> {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }
                state.items.isEmpty() -> {
                    Text(
                        text = "No items",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.items) { item ->
                            FilePickerItem(
                                item = item,
                                isSelected = item.path in state.selectedItems,
                                showSelection = shouldShowSelection(config.mode, item),
                                onClick = { onItemClick(item) },
                                onLongClick = { onItemLongClick(item) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
        
        HorizontalDivider()
        
        // Action buttons
        FilePickerActions(
            canConfirm = state.canConfirm,
            selectedCount = state.selectedItems.size,
            onConfirm = onConfirm,
            onCancel = onCancel,
            onCreateFolder = if (config.allowCreateFolder) onCreateFolder else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}

@Composable
private fun FilePickerItem(
    item: FilePickerState.FileItem,
    isSelected: Boolean,
    showSelection: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Icon(
                imageVector = if (item.isDirectory) {
                    Icons.Default.Folder
                } else {
                    Icons.AutoMirrored.Default.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (item.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Name
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            // Selection indicator
            if (showSelection) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (isSelected) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.Circle
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun FilePickerActions(
    canConfirm: Boolean,
    selectedCount: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onCreateFolder: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (onCreateFolder != null) {
            OutlinedButton(
                onClick = onCreateFolder,
                modifier = Modifier.weight(1f)
            ) {
                Text("New Folder")
            }
        }
        
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text("Cancel")
        }
        
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
            modifier = Modifier.weight(1f)
        ) {
            val text = if (selectedCount > 0) {
                "Select ($selectedCount)"
            } else {
                "Select"
            }
            Text(text)
        }
    }
}

private fun shouldShowSelection(mode: SelectionMode, item: FilePickerState.FileItem): Boolean {
    return when (mode) {
        SelectionMode.SingleFile, SelectionMode.MultipleFiles -> !item.isDirectory
        SelectionMode.SingleFolder, SelectionMode.MultipleFolders -> item.isDirectory
        SelectionMode.Mixed -> true
    }
}