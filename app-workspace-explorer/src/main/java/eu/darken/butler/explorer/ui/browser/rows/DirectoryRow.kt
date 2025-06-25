package eu.darken.butler.explorer.ui.browser.rows

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.ui.browser.FileItem
import eu.darken.butler.explorer.ui.browser.preview.MockDataProvider

@Composable
internal fun DirectoryRow(
    item: FileItem.Directory,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    showSelection: Boolean,
    modifier: Modifier = Modifier
) {
    FileRowBase(
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        showSelection = showSelection,
        modifier = modifier,
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Folder",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        },
        primaryText = item.displayName,
        secondaryText = buildString {
            item.childCount?.let { count ->
                append("$count items")
                append(" • ")
            }
            append(item.displayDate)
        }
    )
}

@Preview2
@Composable
private fun DirectoryRowPreview() {
    DirectoryRow(
        item = MockDataProvider.createMockDirectory(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun DirectoryRowSelectedPreview() {
    DirectoryRow(
        item = MockDataProvider.createMockDirectory("Downloads", 12),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}