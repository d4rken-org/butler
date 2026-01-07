package eu.darken.butler.workspace.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A Column-like layout that adjusts child width based on a top-right cutout.
 *
 * Children whose top edge falls within the cutout height are measured with reduced width
 * (full width minus cutout width). Children below the cutout get full width.
 *
 * This creates an L-shaped content area that flows around the cutout.
 *
 * @param cutoutWidth Width of the cutout area (subtracted from available width for affected children)
 * @param cutoutHeight Height of the cutout area (children starting above this get reduced width)
 * @param horizontalAlignment Horizontal alignment for children narrower than their constraints
 */
@Composable
fun CutoutAwareColumn(
    modifier: Modifier = Modifier,
    cutoutWidth: Dp = 0.dp,
    cutoutHeight: Dp = 0.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cutoutWidthPx = with(LocalDensity.current) { cutoutWidth.roundToPx() }
    val cutoutHeightPx = with(LocalDensity.current) { cutoutHeight.roundToPx() }

    Layout(
        content = { CutoutAwareColumnScopeInstance.content() },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = mutableListOf<Placeable>()
        var currentY = 0

        // Measure children sequentially, adjusting width based on Y position
        for (measurable in measurables) {
            val isInCutoutZone = currentY < cutoutHeightPx
            val availableWidth = if (isInCutoutZone && cutoutWidthPx > 0) {
                (constraints.maxWidth - cutoutWidthPx).coerceAtLeast(0)
            } else {
                constraints.maxWidth
            }

            val childConstraints = constraints.copy(
                minWidth = 0,
                maxWidth = availableWidth,
            )

            val placeable = measurable.measure(childConstraints)
            placeables.add(placeable)
            currentY += placeable.height
        }

        val totalHeight = placeables.sumOf { it.height }

        layout(constraints.maxWidth, totalHeight) {
            var y = 0
            for (placeable in placeables) {
                val x = when (horizontalAlignment) {
                    Alignment.Start -> 0
                    Alignment.End -> constraints.maxWidth - placeable.width
                    Alignment.CenterHorizontally -> (constraints.maxWidth - placeable.width) / 2
                    else -> 0
                }
                placeable.placeRelative(x, y)
                y += placeable.height
            }
        }
    }
}

private object CutoutAwareColumnScopeInstance : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
}
