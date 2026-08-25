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
import androidx.compose.ui.unit.dp

data class InfoEntry(
    val label: String,
    val value: String,
    val pairable: Boolean,
    val valueMaxLines: Int = 2,
)

/** Groups consecutive pairable entries two-per-row; non-pairable entries get a row of their own. */
fun groupInfoEntries(entries: List<InfoEntry>): List<List<InfoEntry>> {
    val rows = mutableListOf<List<InfoEntry>>()
    var pending = mutableListOf<InfoEntry>()
    entries.forEach { entry ->
        if (entry.pairable) {
            pending.add(entry)
            if (pending.size == 2) {
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
        val value: @Composable () -> Unit = {
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                // Plain ellipsis: MiddleEllipsis degrades to clipping on multiline Android text.
                maxLines = entry.valueMaxLines,
                overflow = TextOverflow.Ellipsis,
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
