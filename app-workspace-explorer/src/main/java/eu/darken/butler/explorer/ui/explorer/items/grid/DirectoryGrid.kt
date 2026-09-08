package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.explorer.ui.explorer.items.ItemDecorations
import eu.darken.butler.explorer.ui.explorer.items.SizeProportionBar
import eu.darken.butler.explorer.ui.explorer.items.directorySizeLabel
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.sizes.DirectorySize
import eu.darken.butler.explorer.ui.explorer.items.gridDateStyle
import eu.darken.butler.explorer.ui.explorer.items.gridIconSize
import eu.darken.butler.explorer.ui.explorer.items.showsTileMetadata
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.preview.FolderPreviewCollage
import eu.darken.butler.workspace.ui.preview.rememberFolderPreviewChildren

/** Default for previews/tests: previews are always considered "settled" (loaded immediately). */
internal val PREVIEWS_ALWAYS_SETTLED: State<Boolean> = mutableStateOf(true)

@Composable
internal fun DirectoryGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.RegularDirectory,
    density: ExplorerViewStyle.Density,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean,
    isEnabled: Boolean = true,
    isHighlighted: Boolean = false,
    decorations: ItemDecorations = ItemDecorations(),
    previewsSettled: State<Boolean> = PREVIEWS_ALWAYS_SETTLED,
    sizeFraction: Float? = null,
) {
    val sizeLabel = item.computedSize?.let { directorySizeLabel(it) }
    val countLabel = when (val count = item.childCount) {
        0 -> stringResource(R.string.explorer_file_empty)
        null -> null
        else -> stringResource(R.string.explorer_file_items_count, count)
    }.takeIf { density.showsTileMetadata }
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
        previewContent = {
            DirectoryPreviewBackground(
                dir = item.lookup.lookedUp,
                previewsSettled = previewsSettled,
            )
        },
        icon = {
            Icon(
                imageVector = Icons.TwoTone.Folder,
                contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                tint = Color.White,
                modifier = Modifier.size(density.gridIconSize)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        // The size is the one figure a compact tile keeps, so it is not gated on tile metadata.
        secondaryText = listOfNotNull(sizeLabel, countLabel).joinToString(" • ").takeIf { it.isNotEmpty() },
        tertiaryText = item.lookup.modifiedAt
            ?.takeIf { density.showsTileMetadata }
            ?.let { formatDateTime(it, density.gridDateStyle) },
        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        bottomContent = sizeFraction?.let { fraction ->
            {
                SizeProportionBar(
                    fraction = fraction,
                    isComplete = item.computedSize?.isComplete != false,
                )
            }
        },
    )
}

/**
 * Folder tile background: a collage of the folder's newest media, or nothing.
 *
 * The scroll-driven [previewsSettled] read is isolated here so scroll start/stop recomposes only
 * this child, never the surrounding tile chrome or file tiles.
 */
@Composable
private fun DirectoryPreviewBackground(
    modifier: Modifier = Modifier,
    dir: APath<*>,
    previewsSettled: State<Boolean>,
) {
    val children = rememberFolderPreviewChildren(dir, loadingEnabled = previewsSettled.value)
    if (children.isNotEmpty()) {
        FolderPreviewCollage(modifier = modifier, children = children)
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DirectoryGridPreview() {
    DirectoryGrid(
        item = MockDataProvider.createMockDirectory(),
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
private fun DirectoryGridCompactPreview() {
    DirectoryGrid(
        item = MockDataProvider.createMockDirectory(),
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
private fun DirectoryGridDetailedPreview() {
    DirectoryGrid(
        item = MockDataProvider.createMockDirectory("A folder with a long name", 12),
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
private fun DirectoryGridSelectedPreview() {
    DirectoryGrid(
        item = MockDataProvider.createMockDirectory("Downloads", 12),
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
private fun DirectoryGridSizedPreview() {
    DirectoryGrid(
        item = MockDataProvider.createMockDirectory(
            name = "Pictures",
            childCount = 128,
            computedSize = DirectorySize(bytes = MockDataProvider.MockSizes.gb(2), isComplete = true),
        ),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false,
        sizeFraction = 0.6f,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DirectoryGridPartialSizePreview() {
    DirectoryGrid(
        item = MockDataProvider.createMockDirectory(
            name = "Android",
            childCount = 4,
            computedSize = DirectorySize(bytes = MockDataProvider.MockSizes.mb(512), isComplete = false),
        ),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false,
        sizeFraction = 0.3f,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DirectoryGridHighlightedPreview() {
    DirectoryGrid(
        item = MockDataProvider.createMockDirectory("NewFolder", 0),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false,
        isHighlighted = true,
    )
}