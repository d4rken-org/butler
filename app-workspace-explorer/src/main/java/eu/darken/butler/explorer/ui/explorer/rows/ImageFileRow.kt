package eu.darken.butler.explorer.ui.explorer.rows

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun ImageFileRow(
    item: ExplorerPathItem.ImageFile,
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
            // TODO: Replace with AsyncImage when Coil integration is complete
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = "Image",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(32.dp)
            )
        },
        primaryText = item.displayName,
        secondaryText = buildString {
            item.dimensions?.let { 
                append(it)
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
private fun ImageFileRowPreview() {
    ImageFileRow(
        item = MockDataProvider.createMockImageFile(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun ImageFileRowSelectedPreview() {
    ImageFileRow(
        item = MockDataProvider.createMockImageFile("photo.png", "4K • 3840x2160"),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}