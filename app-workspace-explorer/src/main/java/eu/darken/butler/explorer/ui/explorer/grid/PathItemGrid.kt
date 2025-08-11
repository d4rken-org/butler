package eu.darken.butler.explorer.ui.explorer.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun PathItemGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.PathItem,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
) {
    when (item) {
        is ExplorerItem.RegularDirectory -> DirectoryGrid(
            modifier = modifier,
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            onLongClick = onLongClick,
            showSelection = showSelection,
        )

        is ExplorerItem.SymbolicLink -> SymlinkFileGrid(
            modifier = modifier,
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            onLongClick = onLongClick,
            showSelection = showSelection,
        )

        is ExplorerItem.RegularFile -> RegularFileGrid(
            modifier = modifier,
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            onLongClick = onLongClick,
            showSelection = showSelection,
        )
    }
}

@Preview2
@Composable
private fun PathItemGridPreview() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MockDataProvider.createAllFileTypes()) { item ->
            PathItemGrid(
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
private fun PathItemGridWithSelectionPreview() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MockDataProvider.createAllFileTypes()) { item ->
            PathItemGrid(
                item = item,
                isSelected = item is ExplorerItem.RegularDirectory,
                onToggleSelection = {},
                onClick = {},
                showSelection = true
            )
        }
    }
}