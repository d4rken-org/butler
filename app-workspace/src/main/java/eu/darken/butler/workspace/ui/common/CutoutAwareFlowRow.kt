package eu.darken.butler.workspace.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A FlowRow-like layout that adjusts row width based on a top-right cutout.
 *
 * Rows whose top edge falls within the cutout height use reduced width
 * (full width minus cutout width). Rows below the cutout get full width.
 *
 * This creates an L-shaped content area that flows around the cutout.
 *
 * @param cutoutWidth Width of the cutout area (subtracted from available width for affected rows)
 * @param cutoutHeight Height of the cutout area (rows starting above this get reduced width)
 * @param horizontalSpacing Horizontal spacing between items in a row
 * @param verticalSpacing Vertical spacing between rows
 */
@Composable
fun CutoutAwareFlowRow(
    modifier: Modifier = Modifier,
    cutoutWidth: Dp = 0.dp,
    cutoutHeight: Dp = 0.dp,
    horizontalSpacing: Dp = 0.dp,
    verticalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cutoutWidthPx = with(density) { cutoutWidth.roundToPx() }
    val cutoutHeightPx = with(density) { cutoutHeight.roundToPx() }
    val horizontalSpacingPx = with(density) { horizontalSpacing.roundToPx() }
    val verticalSpacingPx = with(density) { verticalSpacing.roundToPx() }

    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(0, 0) {}
        }

        // Phase 1: Measure all children with their intrinsic size
        val placeables = measurables.map { measurable ->
            measurable.measure(Constraints())
        }

        // Phase 2: Assign items to rows based on cutout-aware widths
        data class RowData(
            val items: MutableList<Placeable> = mutableListOf(),
            val startY: Int = 0,
        )

        val rows = mutableListOf<RowData>()
        var currentY = 0
        var currentRowWidth = 0
        var currentRow = RowData(startY = currentY)

        fun getAvailableWidth(y: Int): Int {
            return if (y < cutoutHeightPx && cutoutWidthPx > 0) {
                (constraints.maxWidth - cutoutWidthPx).coerceAtLeast(0)
            } else {
                constraints.maxWidth
            }
        }

        for (placeable in placeables) {
            val availableWidth = getAvailableWidth(currentY)
            val itemWidthWithSpacing = if (currentRow.items.isEmpty()) {
                placeable.width
            } else {
                placeable.width + horizontalSpacingPx
            }

            if (currentRowWidth + itemWidthWithSpacing > availableWidth && currentRow.items.isNotEmpty()) {
                // Start a new row
                rows.add(currentRow)
                val rowHeight = currentRow.items.maxOfOrNull { it.height } ?: 0
                currentY += rowHeight + verticalSpacingPx
                currentRow = RowData(startY = currentY)
                currentRowWidth = placeable.width
                currentRow.items.add(placeable)
            } else {
                // Add to current row
                currentRowWidth += itemWidthWithSpacing
                currentRow.items.add(placeable)
            }
        }

        // Add the last row
        if (currentRow.items.isNotEmpty()) {
            rows.add(currentRow)
        }

        // Phase 3: Calculate total dimensions
        val totalHeight = if (rows.isEmpty()) {
            0
        } else {
            rows.sumOf { row -> row.items.maxOfOrNull { it.height } ?: 0 } +
                (rows.size - 1).coerceAtLeast(0) * verticalSpacingPx
        }

        // Phase 4: Place items
        layout(constraints.maxWidth, totalHeight) {
            var y = 0
            for (row in rows) {
                var x = 0
                val rowHeight = row.items.maxOfOrNull { it.height } ?: 0

                for ((index, placeable) in row.items.withIndex()) {
                    if (index > 0) {
                        x += horizontalSpacingPx
                    }
                    // Center vertically within the row
                    val yOffset = (rowHeight - placeable.height) / 2
                    placeable.placeRelative(x, y + yOffset)
                    x += placeable.width
                }

                y += rowHeight + verticalSpacingPx
            }
        }
    }
}
