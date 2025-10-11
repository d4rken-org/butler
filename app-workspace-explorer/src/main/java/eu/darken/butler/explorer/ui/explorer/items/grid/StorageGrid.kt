package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun StorageGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Storage,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
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
                imageVector = item.displayIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = null,
        backgroundColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    )
}

@Preview2
@Composable
private fun StorageGridLocalPreview() {
    PreviewWrapper {
        StorageGrid(
            item = MockDataProvider.createMockStorageLocal(),
            onClick = {}
        )
    }
}

@Preview2
@Composable
private fun StorageGridSAFPreview() {
    PreviewWrapper {
        StorageGrid(
            item = MockDataProvider.createMockStorageSAF(),
            onClick = {}
        )
    }
}
