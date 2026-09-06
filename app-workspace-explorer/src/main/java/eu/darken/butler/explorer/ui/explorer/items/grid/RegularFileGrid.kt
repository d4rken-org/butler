package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.explorer.ui.explorer.items.ItemDecorations
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.gridIconSize
import eu.darken.butler.explorer.ui.explorer.items.showsTileMetadata
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun RegularFileGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.RegularFile,
    density: ExplorerViewStyle.Density,
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
        density = density,
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
                imageVector = Icons.TwoTone.Description,
                contentDescription = stringResource(R.string.explorer_file_regular_content_desc),
                tint = Color.White,
                modifier = Modifier.size(density.gridIconSize)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = (item.lookup.size?.let { formatFileSize(it) } ?: "?")
            .takeIf { density.showsTileMetadata },
        tertiaryText = item.lookup.modifiedAt
            ?.takeIf { density.showsTileMetadata }
            ?.let { formatDateTime(it, DateTimeStyle.COMPACT) },
        previewContent = {
            TintedAsyncImage(
                model = item.lookup,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RegularFileGridPreview() {
    RegularFileGrid(
        item = MockDataProvider.createMockRegularFile(),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RegularFileGridCompactPreview() {
    RegularFileGrid(
        item = MockDataProvider.createMockRegularFile(),
        density = ExplorerViewStyle.Density.COMPACT,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RegularFileGridDetailedPreview() {
    RegularFileGrid(
        item = MockDataProvider.createMockRegularFile("a_rather_long_file_name_here.txt"),
        density = ExplorerViewStyle.Density.DETAILED,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RegularFileGridSelectedPreview() {
    RegularFileGrid(
        item = MockDataProvider.createMockRegularFile("config.json"),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RegularFileGridHighlightedPreview() {
    RegularFileGrid(
        item = MockDataProvider.createMockRegularFile("new_file.txt"),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false,
        isHighlighted = true,
    )
}