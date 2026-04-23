package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun LookupItemRow(
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
        is ExplorerItem.RegularDirectory -> DirectoryRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            onLongClick = onLongClick,
            showSelection = showSelection,
            isEnabled = isEnabled,
            isHighlighted = isHighlighted,
            modifier = modifier,
        )

        is ExplorerItem.SymbolicLink -> SymlinkFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            onLongClick = onLongClick,
            showSelection = showSelection,
            isEnabled = isEnabled,
            isHighlighted = isHighlighted,
            modifier = modifier,
        )

        is ExplorerItem.RegularFile -> RegularFileRow(
            item = item,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            onLongClick = onLongClick,
            showSelection = showSelection,
            isEnabled = isEnabled,
            isHighlighted = isHighlighted,
            modifier = modifier,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FileItemRowsPreview() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MockDataProvider.createAllFileTypes()) { item ->
            LookupItemRow(
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
private fun FileItemRowsWithSelectionPreview() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MockDataProvider.createAllFileTypes()) { item ->
            LookupItemRow(
                item = item,
                isSelected = item is ExplorerItem.RegularDirectory,
                onToggleSelection = {},
                onClick = {},
                showSelection = true
            )
        }
    }
}