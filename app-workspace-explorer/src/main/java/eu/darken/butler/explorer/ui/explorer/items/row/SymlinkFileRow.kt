package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.ui.explorer.items.ItemDecorations
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.isProblematicInvisible
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.rowDateStyle
import eu.darken.butler.explorer.ui.explorer.items.rowIconSize
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun SymlinkFileRow(
    modifier: Modifier = Modifier,
    item: ExplorerItem.SymbolicLink,
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
            Icon(
                imageVector = Icons.TwoTone.Link,
                contentDescription = stringResource(R.string.explorer_file_symlink_content_desc),
                tint = if (item.isBroken) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(density.rowIconSize)
            )
        },
        primaryText = primaryText,
        hasProblematicChars = hasProblematicChars,
        secondaryText = listOfNotNull(
            item.targetPath?.let { "→ $it" },
            stringResource(R.string.explorer_file_broken_link_label).takeIf { item.isBroken },
        ).joinToString(" • ").takeIf { it.isNotEmpty() },
        secondaryEndText = item.lookup.modifiedAt?.let { formatDateTime(it, density.rowDateStyle) },
        tertiaryText = item.createdAt
            ?.takeIf { density == ExplorerViewStyle.Density.DETAILED }
            ?.let { stringResource(R.string.explorer_view_detail_created_label, formatDateTime(it, DateTimeStyle.FULL)) },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SymlinkFileRowPreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink(),
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
private fun SymlinkFileRowCompactPreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink(),
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
private fun SymlinkFileRowDetailedPreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink(),
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
private fun SymlinkFileRowBrokenPreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink("broken_link", "/path/to/missing/file", true),
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
private fun SymlinkFileRowLeadingWhitespacePreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink(" link_to_docs", "/home/user/documents"),
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
private fun SymlinkFileRowWhitespaceSelectedPreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink("my_link ", "/opt/data/shared"),
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
private fun SymlinkFileRowHighlightedPreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink("new_link", "/home/user/target"),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false,
        isHighlighted = true,
    )
}