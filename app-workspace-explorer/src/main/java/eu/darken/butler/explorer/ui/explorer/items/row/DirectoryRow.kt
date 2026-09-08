package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.NoteAdd
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.explorer.ui.explorer.items.ItemDecorations
import eu.darken.butler.explorer.ui.explorer.items.SizeProportionBar
import eu.darken.butler.explorer.ui.explorer.items.directorySizeLabel
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.isProblematicInvisible
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.sizes.DirectorySize
import eu.darken.butler.explorer.ui.explorer.items.rowDateStyle
import eu.darken.butler.explorer.ui.explorer.items.showsDatesOnOwnLine
import eu.darken.butler.explorer.ui.explorer.items.rowIconSize
import eu.darken.butler.explorer.ui.explorer.items.showsFileAttributes
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun DirectoryRow(
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
    sizeFraction: Float? = null,
) {
    val primaryText = item.displayName.get(LocalContext.current)
    val hasProblematicChars = primaryText.trim { it.isProblematicInvisible() } != primaryText

    FileRowBase(
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
        modifier = modifier,
        leadingContent = {
            TintedAsyncImage(
                model = item.lookup,
                contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                modifier = Modifier.size(density.rowIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        primaryText = primaryText,
        hasProblematicChars = hasProblematicChars,
        secondaryText = listOfNotNull(
            item.computedSize?.let { directorySizeLabel(it) },
            when (val count = item.childCount) {
                0 -> stringResource(R.string.explorer_file_empty)
                null -> null
                else -> stringResource(R.string.explorer_file_items_count, count)
            },
            item.permissions?.toReadableString().takeIf { density.showsFileAttributes },
            item.ownership?.let { it.userName ?: it.userId.toString() }.takeIf { density.showsFileAttributes },
        ).joinToString(" • ").takeIf { it.isNotEmpty() },
        secondaryEndText = item.lookup.modifiedAt
            ?.takeUnless { density.showsDatesOnOwnLine }
            ?.let { formatDateTime(it, density.rowDateStyle) },
        tertiaryText = item.createdAt
            ?.takeIf { density.showsDatesOnOwnLine }
            ?.let { formatDateTime(it, DateTimeStyle.FULL) },
        tertiaryIcon = item.createdAt
            ?.takeIf { density.showsDatesOnOwnLine }
            ?.let {
                RowMetaIcon(
                    icon = Icons.AutoMirrored.TwoTone.NoteAdd,
                    contentDescription = stringResource(R.string.explorer_view_detail_created_label),
                )
            },
        tertiaryEndText = item.lookup.modifiedAt
            ?.takeIf { density.showsDatesOnOwnLine }
            ?.let { formatDateTime(it, DateTimeStyle.FULL) },
        tertiaryEndIcon = item.lookup.modifiedAt
            ?.takeIf { density.showsDatesOnOwnLine }
            ?.let {
                RowMetaIcon(
                    icon = Icons.TwoTone.DriveFileRenameOutline,
                    contentDescription = stringResource(R.string.explorer_view_detail_modified_label),
                )
            },
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DirectoryRowPreview() {
    DirectoryRow(
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
private fun DirectoryRowCompactPreview() {
    DirectoryRow(
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
private fun DirectoryRowDetailedPreview() {
    DirectoryRow(
        item = MockDataProvider.createMockDirectory(),
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
private fun DirectoryRowSelectedPreview() {
    DirectoryRow(
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
private fun DirectoryRowTrailingWhitespacePreview() {
    DirectoryRow(
        item = MockDataProvider.createMockDirectory("My Folder ", 24),
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
private fun DirectoryRowWhitespaceSelectedPreview() {
    DirectoryRow(
        item = MockDataProvider.createMockDirectory(" Important ", 8),
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
private fun DirectoryRowSizedPreview() {
    DirectoryRow(
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
private fun DirectoryRowPartialSizePreview() {
    DirectoryRow(
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
private fun DirectoryRowHighlightedPreview() {
    DirectoryRow(
        item = MockDataProvider.createMockDirectory("NewFolder", 0),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false,
        isHighlighted = true,
    )
}