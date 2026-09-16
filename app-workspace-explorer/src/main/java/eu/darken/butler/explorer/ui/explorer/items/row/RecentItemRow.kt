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
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.formatSmartTime
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.ItemDecorations
import eu.darken.butler.explorer.ui.explorer.items.rowIconSize
import eu.darken.butler.explorer.ui.explorer.items.usesShortFileSize
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import kotlin.time.Instant

/**
 * A file row for the Recent listing: the folder it sits in matters as much as its name, and the
 * end of the second line says when the index first saw it rather than when it was last written.
 */
@Composable
internal fun RecentItemRow(
    modifier: Modifier = Modifier,
    item: ExplorerItem.File,
    indexedAt: Instant?,
    density: ExplorerViewStyle.Density,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
    isEnabled: Boolean = true,
    isHighlighted: Boolean = false,
    decorations: ItemDecorations = ItemDecorations(),
) {
    val context = LocalContext.current

    FileRowBase(
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
        leadingContent = {
            TintedAsyncImage(
                model = item.lookup,
                contentDescription = stringResource(R.string.explorer_file_regular_content_desc),
                modifier = Modifier.size(density.rowIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        primaryText = item.displayName.get(context),
        secondaryText = listOfNotNull(
            item.lookup.parent?.userReadablePath?.get(context),
            item.lookup.size?.let { formatFileSize(it, shortFormat = density.usesShortFileSize) },
        ).joinToString(" • ").takeIf { it.isNotEmpty() },
        secondaryEndText = indexedAt?.let { formatSmartTime(it, absoluteStyle = DateTimeStyle.COMPACT) },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RecentItemRowPreview() {
    RecentItemRow(
        item = MockDataProvider.createMockRecentFile(),
        indexedAt = MockDataProvider.MockTimes.hoursAgo(2),
        density = ExplorerViewStyle.Density.COMFORTABLE,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RecentItemRowCompactPreview() {
    RecentItemRow(
        item = MockDataProvider.createMockRecentFile(),
        indexedAt = MockDataProvider.MockTimes.daysAgo(12),
        density = ExplorerViewStyle.Density.COMPACT,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RecentItemRowSelectedPreview() {
    RecentItemRow(
        item = MockDataProvider.createMockRecentFile(name = "holiday.jpg", directory = "/storage/emulated/0/DCIM"),
        indexedAt = MockDataProvider.MockTimes.minutesAgo(5),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        isSelected = true,
        showSelection = true,
    )
}

/** A deep folder and a long relative time in a narrow row: the second line's worst case. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RecentItemRowNarrowPreview() {
    RecentItemRow(
        modifier = Modifier.width(220.dp),
        item = MockDataProvider.createMockRecentFile(
            name = "quarterly_report_final.pdf",
            directory = "/storage/emulated/0/Download/work/reports",
        ),
        indexedAt = MockDataProvider.MockTimes.daysAgo(21),
        density = ExplorerViewStyle.Density.COMFORTABLE,
    )
}

/** A row the index never stamped: the listing places it by its modification time and says nothing. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RecentItemRowWithoutIndexTimePreview() {
    RecentItemRow(
        item = MockDataProvider.createMockRecentFile(),
        indexedAt = null,
        density = ExplorerViewStyle.Density.COMFORTABLE,
    )
}
