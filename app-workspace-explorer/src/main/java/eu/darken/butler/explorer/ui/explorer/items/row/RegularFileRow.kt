package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.isProblematicInvisible
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.rowDateStyle
import eu.darken.butler.explorer.ui.explorer.items.showsDatesOnOwnLine
import eu.darken.butler.explorer.ui.explorer.items.rowIconSize
import eu.darken.butler.explorer.ui.explorer.items.showsFileAttributes
import eu.darken.butler.explorer.ui.explorer.items.usesShortFileSize
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun RegularFileRow(
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
                contentDescription = stringResource(R.string.explorer_file_regular_content_desc),
                modifier = Modifier.size(density.rowIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        primaryText = primaryText,
        hasProblematicChars = hasProblematicChars,
        secondaryText = listOfNotNull(
            item.lookup.size?.let { formatFileSize(it, shortFormat = density.usesShortFileSize) } ?: "?",
            item.permissions?.toReadableString().takeIf { density.showsFileAttributes },
            item.ownership
                ?.let { "${it.userName ?: it.userId} | ${it.groupName ?: it.groupId}" }
                .takeIf { density.showsFileAttributes },
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
private fun RegularFileRowPreview() {
    RegularFileRow(
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
private fun RegularFileRowNarrowPreview() {
    RegularFileRow(
        density = ExplorerViewStyle.Density.COMFORTABLE,
        modifier = Modifier.width(220.dp),
        item = MockDataProvider.createMockRegularFile(
            name = "quarterly_report_final.pdf",
            modifiedAt = MockDataProvider.MockTimes.hoursAgo(3),
        ),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RegularFileRowCompactPreview() {
    RegularFileRow(
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
private fun RegularFileRowDetailedPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile(),
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
private fun RegularFileRowSelectedPreview() {
    RegularFileRow(
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
private fun RegularFileRowLeadingWhitespacePreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile(" document.txt"),
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
private fun RegularFileRowTrailingWhitespaceSelectedPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile("report.pdf "),
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
private fun RegularFileRowHighlightedPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile("new_file.txt"),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false,
        isHighlighted = true,
    )
}