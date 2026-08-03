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
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun SymlinkFileRow(
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
    val primaryText = item.displayName.get(LocalContext.current)
    val hasProblematicChars = primaryText.trim { it.isProblematicInvisible() } != primaryText

    FileRowBase(
        item = item,
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
                modifier = Modifier.size(32.dp)
            )
        },
        primaryText = primaryText,
        hasProblematicChars = hasProblematicChars,
        secondaryText = listOfNotNull(
            item.targetPath?.let { "→ $it" },
            stringResource(R.string.explorer_file_broken_link_label).takeIf { item.isBroken },
        ).joinToString(" • ").takeIf { it.isNotEmpty() },
        secondaryEndText = item.lookup.modifiedAt?.let { formatDateTime(it, DateTimeStyle.FULL) },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SymlinkFileRowPreview() {
    SymlinkFileRow(
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
private fun SymlinkFileRowBrokenPreview() {
    SymlinkFileRow(
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
private fun SymlinkFileRowLeadingWhitespacePreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink(" link_to_docs", "/home/user/documents"),
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
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false,
        isHighlighted = true,
    )
}