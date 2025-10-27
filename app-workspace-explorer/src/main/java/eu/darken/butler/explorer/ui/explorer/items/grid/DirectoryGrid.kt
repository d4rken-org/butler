package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun DirectoryGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.RegularDirectory,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean,
) {
    FileGridBase(
        modifier = modifier,
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        icon = {
            Icon(
                imageVector = Icons.TwoTone.Folder,
                contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = when (val count = item.childCount) {
            0 -> stringResource(R.string.explorer_file_empty)
            null -> null
            else -> stringResource(R.string.explorer_file_items_count, count)
        },
        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    )
}

@Preview2
@Composable
private fun DirectoryGridPreview() {
    DirectoryGrid(
        item = MockDataProvider.createMockDirectory(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun DirectoryGridSelectedPreview() {
    DirectoryGrid(
        item = MockDataProvider.createMockDirectory("Downloads", 12),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}