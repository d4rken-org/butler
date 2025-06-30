package eu.darken.butler.explorer.ui.explorer.rows

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun RegularFileRow(
    item: ExplorerPathItem.RegularFile,
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
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = "File",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
        },
        primaryText = item.displayName,
        secondaryText = buildString {
            append(item.displaySize)
            append(" • ")
            append(item.displayDate)
        }
    )
}

@Preview2
@Composable
private fun RegularFileRowPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun RegularFileRowSelectedPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile("config.json"),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}