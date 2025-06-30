package eu.darken.butler.explorer.ui.explorer.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun FileItemRow(
    item: ExplorerPathItem,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    showSelection: Boolean = false,
    modifier: Modifier = Modifier
) {
    when (item) {
        is ExplorerPathItem.Directory -> DirectoryRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )

        is ExplorerPathItem.ImageFile -> ImageFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )

        is ExplorerPathItem.MediaFile -> MediaFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )

        is ExplorerPathItem.ApkFile -> ApkFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )

        is ExplorerPathItem.ArchiveFile -> ArchiveFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )

        is ExplorerPathItem.DocumentFile -> DocumentFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )

        is ExplorerPathItem.SymbolicLink -> SymlinkFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )

        is ExplorerPathItem.RegularFile -> RegularFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            showSelection = showSelection,
            modifier = modifier
        )

        is ExplorerPathItem.Shortcut -> ShortcutRow(
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
                isSelected = item is ExplorerPathItem.Directory,
                onToggleSelection = {},
                onClick = {},
                showSelection = true
            )
        }
    }
}