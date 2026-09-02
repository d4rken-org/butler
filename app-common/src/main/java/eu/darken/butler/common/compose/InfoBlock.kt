package eu.darken.butler.common.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class InfoEntry(
    val label: String,
    val value: String,
    val pairable: Boolean,
    val valueMaxLines: Int = 2,
    val valueStyle: ValueStyle = ValueStyle.DEFAULT,
) {
    /** [PATH] pins the value to a single line so the trailing file name survives truncation. */
    enum class ValueStyle { DEFAULT, PATH }
}

/** Gap between the columns of a metadata grid. */
val InfoGridGutter = 12.dp

/**
 * Narrowest a metadata column may get before [infoGridColumns] drops one. Low enough that a card
 * inside a floating bar still pairs on a 320dp pane, which is the narrowest layout that pairs
 * today: 320dp less the bar's two 16dp insets and the card's two 12dp insets leaves 264dp.
 */
val InfoGridMinColumnWidth = 120.dp

/**
 * How many columns fit into [availableWidth] with every column at least [minColumnWidth] wide.
 *
 * At the default width and gutter: 264dp (a bar card in a 320dp pane) and 312dp (a content card
 * on a 360dp phone) both give 2, 700dp gives 5, 1150dp gives 8. Never fewer than one.
 */
fun infoGridColumns(
    availableWidth: Dp,
    minColumnWidth: Dp = InfoGridMinColumnWidth,
    gutter: Dp = InfoGridGutter,
): Int {
    if (availableWidth <= 0.dp || minColumnWidth <= 0.dp) return 1
    return ((availableWidth + gutter) / (minColumnWidth + gutter)).toInt().coerceAtLeast(1)
}

/** Groups consecutive pairable entries [columns]-per-row; non-pairable entries get a row of their own. */
fun groupInfoEntries(entries: List<InfoEntry>, columns: Int = 2): List<List<InfoEntry>> {
    val perRow = columns.coerceAtLeast(1)
    val rows = mutableListOf<List<InfoEntry>>()
    var pending = mutableListOf<InfoEntry>()
    entries.forEach { entry ->
        if (entry.pairable) {
            pending.add(entry)
            if (pending.size == perRow) {
                rows.add(pending)
                pending = mutableListOf()
            }
        } else {
            if (pending.isNotEmpty()) {
                rows.add(pending)
                pending = mutableListOf()
            }
            rows.add(listOf(entry))
        }
    }
    if (pending.isNotEmpty()) rows.add(pending)
    return rows
}

/** A small label stacked on top of its value, the building block of the metadata grids. */
@Composable
fun InfoBlock(
    modifier: Modifier = Modifier,
    entry: InfoEntry,
    valueLeading: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val isPath = entry.valueStyle == InfoEntry.ValueStyle.PATH
        val value: @Composable () -> Unit = {
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                // MiddleEllipsis degrades to clipping on multiline Android text, so it only ever
                // pairs with maxLines = 1 and [valueMaxLines] does not apply to it.
                maxLines = if (isPath) 1 else entry.valueMaxLines,
                overflow = if (isPath) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            )
        }
        if (valueLeading != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                valueLeading()
                value()
            }
        } else {
            value()
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun InfoBlockPreview() {
    InfoBlock(
        modifier = Modifier
            .width(180.dp)
            .padding(16.dp),
        entry = InfoEntry(label = "Size", value = "4.59 MB", pairable = true),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun InfoBlockWithLeadingPreview() {
    InfoBlock(
        modifier = Modifier
            .width(180.dp)
            .padding(16.dp),
        entry = InfoEntry(label = "Status", value = "Successful", pairable = true),
        valueLeading = {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun InfoBlockUncappedValuePreview() {
    InfoBlock(
        modifier = Modifier
            .width(320.dp)
            .padding(16.dp),
        entry = InfoEntry(
            label = "Result",
            value = "Moved 12 files, moved 3 folders, skipped 2 files, overwrote 1 file",
            pairable = false,
            valueMaxLines = Int.MAX_VALUE,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun InfoBlockPathValuePreview() {
    InfoBlock(
        modifier = Modifier
            .width(320.dp)
            .padding(16.dp),
        entry = InfoEntry(
            label = "Destination path",
            value = "/storage/emulated/0/Documents/Projects/Archive/2026/Quarter-01/reports/final-report.pdf",
            pairable = false,
            valueStyle = InfoEntry.ValueStyle.PATH,
        ),
    )
}
