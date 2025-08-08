package eu.darken.butler.explorer.ui.explorer.rows

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Archive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun ArchiveFileRow(
    item: ExplorerPathItem.ArchiveFile,
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
                imageVector = Icons.TwoTone.Archive,
                contentDescription = "Archive",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(32.dp)
            )
        },
        primaryText = item.displayName,
        secondaryText = buildString {
            item.entryCount?.let { 
                append("$it items")
                append(" • ")
            }
            append(item.displaySize)
            append(" • ")
            append(item.displayDate)
        }
    )
}

@Preview2
@Composable
private fun ArchiveFileRowPreview() {
    ArchiveFileRow(
        item = MockDataProvider.createMockArchiveFile(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun ArchiveFileRowSelectedPreview() {
    ArchiveFileRow(
        item = MockDataProvider.createMockArchiveFile("backup.tar.gz", 156, 0.42f),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}