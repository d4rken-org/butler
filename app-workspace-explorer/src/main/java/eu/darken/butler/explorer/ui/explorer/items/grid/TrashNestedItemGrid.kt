package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
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
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.gridIconSize
import eu.darken.butler.explorer.ui.explorer.items.showsTileMetadata
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun TrashNestedItemGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Trash.Nested,
    density: ExplorerViewStyle.Density,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
) {
    val context = LocalContext.current

    FileGridBase(
        modifier = modifier,
        item = item,
        density = density,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        icon = {
            Icon(
                imageVector = if (item.isDirectory) Icons.TwoTone.Folder else Icons.TwoTone.Description,
                contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                tint = Color.White,
                modifier = Modifier.size(density.gridIconSize),
            )
        },
        primaryText = item.displayName.get(context),
        secondaryText = item.lookup.size
            ?.takeIf { density.showsTileMetadata }
            ?.let { formatFileSize(it) },
        tertiaryText = null,
        previewContent = {
            TintedAsyncImage(
                model = item.lookup,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemGridPreview() {
    TrashNestedItemGrid(
        item = MockDataProvider.createMockTrashNestedItem(),
        density = ExplorerViewStyle.Density.COMFORTABLE,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemGridCompactPreview() {
    TrashNestedItemGrid(
        item = MockDataProvider.createMockTrashNestedItem(),
        density = ExplorerViewStyle.Density.COMPACT,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemGridDetailedPreview() {
    TrashNestedItemGrid(
        item = MockDataProvider.createMockTrashNestedItem(),
        density = ExplorerViewStyle.Density.DETAILED,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemGridSelectedPreview() {
    TrashNestedItemGrid(
        item = MockDataProvider.createMockTrashNestedItem("photo.jpg"),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = true,
        showSelection = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemGridDirectoryPreview() {
    TrashNestedItemGrid(
        item = MockDataProvider.createMockTrashNestedDirectory(),
        density = ExplorerViewStyle.Density.COMFORTABLE,
    )
}
