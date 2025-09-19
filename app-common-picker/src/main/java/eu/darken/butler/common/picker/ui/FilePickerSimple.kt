package eu.darken.butler.common.picker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.picker.core.FilePickerConfig
import eu.darken.butler.common.picker.core.FilePickerResult
import eu.darken.butler.common.picker.core.FilePickerState
import eu.darken.butler.common.picker.core.SelectionMode

enum class FilePickerMode {
    FULLSCREEN,
    BOTTOM_SHEET,
    ADAPTIVE
}

/**
 * Simplified file picker that works without ViewModel for initial testing
 */
@Composable
fun FilePickerSimple(
    config: FilePickerConfig,
    mode: FilePickerMode = FilePickerMode.BOTTOM_SHEET,
    onResult: (FilePickerResult) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember {
        mutableStateOf(
            FilePickerState(
                currentPath = config.initialPath ?: LocalPath.build("/storage/emulated/0"),
                items = getSampleItems(),
                showHiddenFiles = config.showHiddenFiles
            )
        )
    }
    
    val onNavigate: (APath) -> Unit = { path ->
        state = state.copy(currentPath = path, items = getSampleItems())
    }
    
    val onItemClick: (FilePickerState.FileItem) -> Unit = { item ->
        when {
            item.isDirectory -> onNavigate(item.path)
            config.mode in listOf(SelectionMode.SingleFile, SelectionMode.MultipleFiles) -> {
                val newSelection = if (config.mode == SelectionMode.SingleFile) {
                    setOf(item.path)
                } else {
                    if (item.path in state.selectedItems) {
                        state.selectedItems - item.path
                    } else {
                        state.selectedItems + item.path
                    }
                }
                state = state.copy(selectedItems = newSelection)
            }
            else -> { /* Do nothing for folder modes on file click */ }
        }
    }
    
    val onItemLongClick: (FilePickerState.FileItem) -> Unit = { item ->
        val newSelection = if (item.path in state.selectedItems) {
            state.selectedItems - item.path
        } else {
            state.selectedItems + item.path
        }
        state = state.copy(selectedItems = newSelection)
    }
    
    val onConfirm: () -> Unit = {
        if (state.selectedItems.isNotEmpty()) {
            onResult(FilePickerResult.Selected(state.selectedItems.toList()))
        }
    }
    
    when (mode) {
        FilePickerMode.BOTTOM_SHEET -> {
            FilePickerBottomSheetSimple(
                config = config,
                state = state,
                onNavigate = onNavigate,
                onItemClick = onItemClick,
                onItemLongClick = onItemLongClick,
                onConfirm = onConfirm,
                onCancel = { onResult(FilePickerResult.Cancelled) },
                onDismiss = onDismiss
            )
        }
        else -> {
            // For now, just use bottom sheet for all modes
            FilePickerBottomSheetSimple(
                config = config,
                state = state,
                onNavigate = onNavigate,
                onItemClick = onItemClick,
                onItemLongClick = onItemLongClick,
                onConfirm = onConfirm,
                onCancel = { onResult(FilePickerResult.Cancelled) },
                onDismiss = onDismiss
            )
        }
    }
}

private fun getSampleItems(): List<FilePickerState.FileItem> {
    return listOf(
        FilePickerState.FileItem(
            path = LocalPath.build("/storage/emulated/0/Download"),
            name = "Download",
            isDirectory = true
        ),
        FilePickerState.FileItem(
            path = LocalPath.build("/storage/emulated/0/Documents"),
            name = "Documents",
            isDirectory = true
        ),
        FilePickerState.FileItem(
            path = LocalPath.build("/storage/emulated/0/Pictures"),
            name = "Pictures",
            isDirectory = true
        ),
        FilePickerState.FileItem(
            path = LocalPath.build("/storage/emulated/0/test.txt"),
            name = "test.txt",
            isDirectory = false,
            size = 1024
        ),
        FilePickerState.FileItem(
            path = LocalPath.build("/storage/emulated/0/readme.md"),
            name = "readme.md",
            isDirectory = false,
            size = 2048
        )
    )
}

@Composable
private fun FilePickerBottomSheetSimple(
    config: FilePickerConfig,
    state: FilePickerState,
    onNavigate: (APath) -> Unit,
    onItemClick: (FilePickerState.FileItem) -> Unit,
    onItemLongClick: (FilePickerState.FileItem) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = {
            onCancel()
            onDismiss()
        },
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header
            FilePickerSheetHeader(
                title = config.title ?: "Select Files",
                subtitle = config.subtitle,
                onClose = {
                    onCancel()
                    onDismiss()
                }
            )
            
            HorizontalDivider()
            
            // Content
            FilePickerCore(
                config = config,
                state = state,
                modifier = androidx.compose.ui.Modifier.weight(1f),
                onNavigate = onNavigate,
                onItemClick = onItemClick,
                onItemLongClick = onItemLongClick,
                onConfirm = {
                    onConfirm()
                    onDismiss()
                },
                onCancel = {
                    onCancel()
                    onDismiss()
                },
                onCreateFolder = null // Disabled for now
            )
        }
    }
}

@Composable
private fun FilePickerSheetHeader(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close"
            )
        }
    }
}