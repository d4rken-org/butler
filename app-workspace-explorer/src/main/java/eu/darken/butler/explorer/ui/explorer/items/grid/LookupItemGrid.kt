package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun LookupItemGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Lookup,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
    isEnabled: Boolean = true,
    isHighlighted: Boolean = false,
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
            isEnabled = isEnabled,
            isHighlighted = isHighlighted,
        )

        is ExplorerItem.SymbolicLink -> SymlinkFileGrid(
            modifier = modifier,
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            onLongClick = onLongClick,
            showSelection = showSelection,
            isEnabled = isEnabled,
            isHighlighted = isHighlighted,
        )

        is ExplorerItem.RegularFile -> RegularFileGrid(
            modifier = modifier,
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            onLongClick = onLongClick,
            showSelection = showSelection,
            isEnabled = isEnabled,
            isHighlighted = isHighlighted,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LookupItemGridPreview() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MockDataProvider.createAllFileTypes()) { item ->
            LookupItemGrid(
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LookupItemGridWithSelectionPreview() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MockDataProvider.createAllFileTypes()) { item ->
            LookupItemGrid(
                item = item,
                isSelected = item is ExplorerItem.RegularDirectory,
                onToggleSelection = {},
                onClick = {},
                showSelection = true
            )
        }
    }
}