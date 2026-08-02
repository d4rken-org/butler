package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.formatDateCompact
import eu.darken.butler.explorer.ui.explorer.items.ItemDecorations
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun SymlinkFileGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.SymbolicLink,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean,
    isEnabled: Boolean = true,
    isHighlighted: Boolean = false,
    decorations: ItemDecorations = ItemDecorations(),
) {
    FileGridBase(
        modifier = modifier,
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        isEnabled = isEnabled,
        isHighlighted = isHighlighted,
        decorations = decorations,
        icon = {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = stringResource(R.string.explorer_file_symlink_content_desc),
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = null, // Don't show target path in top corner to avoid overlap
        tertiaryText = item.lookup.modifiedAt?.let { formatDateCompact(it) },
        backgroundColor = if (item.isBroken) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SymlinkFileGridPreview() {
    SymlinkFileGrid(
        item = MockDataProvider.createMockSymbolicLink(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SymlinkFileGridBrokenPreview() {
    SymlinkFileGrid(
        item = MockDataProvider.createMockSymbolicLink("broken_link", "/path/to/missing/file", true),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SymlinkFileGridHighlightedPreview() {
    SymlinkFileGrid(
        item = MockDataProvider.createMockSymbolicLink("new_link", "/home/user/target"),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false,
        isHighlighted = true,
    )
}