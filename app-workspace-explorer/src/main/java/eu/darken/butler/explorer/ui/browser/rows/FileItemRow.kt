package eu.darken.butler.explorer.ui.browser.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.ui.browser.FileItem
import eu.darken.butler.explorer.ui.browser.preview.MockDataProvider

@Composable
fun FileItemRow(
    item: FileItem,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    showSelection: Boolean = false,
    modifier: Modifier = Modifier
) {
    when (item) {
        is FileItem.Directory -> DirectoryRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )
        
        is FileItem.ImageFile -> ImageFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )
        
        is FileItem.MediaFile -> MediaFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )
        
        is FileItem.ApkFile -> ApkFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )
        
        is FileItem.ArchiveFile -> ArchiveFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )
        
        is FileItem.DocumentFile -> DocumentFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )
        
        is FileItem.SymbolicLink -> SymlinkFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )
        
        is FileItem.RegularFile -> RegularFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )
    }
}

@Preview2
@Composable
private fun FileItemRowsPreview() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MockDataProvider.createAllFileTypes()) { item ->
            FileItemRow(
                item = item,
                isSelected = false,
                onToggleSelection = {},
                onClick = {},
                showSelection = false
            )
        }
    }
}

@Preview2
@Composable
private fun FileItemRowsWithSelectionPreview() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MockDataProvider.createAllFileTypes()) { item ->
            FileItemRow(
                item = item,
                isSelected = item is FileItem.Directory,
                onToggleSelection = {},
                onClick = {},
                showSelection = true
            )
        }
    }
}