package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import eu.darken.butler.common.isProblematicInvisible
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
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
            ?.let { stringResource(R.string.explorer_view_detail_created_label, formatDateTime(it, DateTimeStyle.FULL)) },
        tertiaryEndText = item.lookup.modifiedAt
            ?.takeIf { density.showsDatesOnOwnLine }
            ?.let { stringResource(R.string.explorer_view_detail_modified_label, formatDateTime(it, DateTimeStyle.FULL)) },
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