package eu.darken.butler.workspace.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Default values for [CutoutCard].
 */
object CutoutCardDefaults {
    val CornerRadius = 12.dp
    val GapDistanceExpanded = 8.dp
    val GapDistanceCollapsed = 8.dp
    val ContentPadding = 16.dp
    val ElevationDp = 6.dp
}

/**
 * A Card with an optional cutout that automatically sizes to fit the cutout content.
 *
 * The cutout content is measured first, and the card shape is adjusted to create a notch
 * where the cutout content is positioned.
 *
 * Two cutout modes are supported:
 * - **Corner cutout** (default): Top-right corner cutout, content flows around it
 * - **Full-height cutout**: Right-edge cutout spanning full height, content is beside it
 *
 * @param cutoutContent Composable to render in the cutout area. If null, renders a regular card.
 * @param cutoutFullHeight If true, cutout spans full height of card (right-edge mode).
 *                         If false, cutout is in top-right corner.
 * @param gapDistance Gap between cutout content and card content. In corner mode, this gap is
 *                    applied to both the left and bottom edges of the cutout. In full-height mode,
 *                    only the horizontal gap (left of cutout) is applied since the cutout spans
 *                    the full height.
 * @param contentPadding Padding inside the card for the main content
 * @param elevation Card elevation
 * @param colors Card colors
 * @param cornerRadius Corner radius for the card (and cutout inner corners)
 * @param content Main card content
 */
@Composable
fun CutoutCard(
    modifier: Modifier = Modifier,
    cutoutContent: (@Composable () -> Unit)? = null,
    cutoutFullHeight: Boolean = false,
    gapDistance: Dp = CutoutCardDefaults.GapDistanceExpanded,
    contentPadding: Dp = CutoutCardDefaults.ContentPadding,
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = CutoutCardDefaults.ElevationDp),
    colors: CardColors = CardDefaults.cardColors(),
    cornerRadius: Dp = CutoutCardDefaults.CornerRadius,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (cutoutContent == null) {
        // No cutout - render regular card
        Card(
            modifier = modifier,
            elevation = elevation,
            shape = RoundedCornerShape(cornerRadius),
            colors = colors,
        ) {
            CutoutAwareColumn(
                modifier = Modifier.padding(contentPadding),
                cutoutWidth = 0.dp,
                cutoutHeight = 0.dp,
                content = content,
            )
        }
        return
    }

    val density = LocalDensity.current
    val gapDistancePx = with(density) { gapDistance.roundToPx() }

    SubcomposeLayout(modifier = modifier) { constraints ->
        // Phase 1: Measure cutout content to determine cutout size
        val cutoutPlaceable = subcompose("cutout") {
            cutoutContent()
        }.firstOrNull()?.measure(Constraints())

        val cutoutContentWidth = cutoutPlaceable?.width ?: 0
        val cutoutContentHeight = cutoutPlaceable?.height ?: 0

        // Calculate cutout width (content + gap on left side)
        val cutoutWidth = cutoutContentWidth + gapDistancePx
        val cutoutWidthDp = with(density) { cutoutWidth.toDp() }

        // Phase 2: Measure card with appropriate cutout shape
        val cardConstraints = if (cutoutFullHeight) {
            // Full-height mode: constrain card width to leave space for cutout
            val cardWidth = (constraints.maxWidth - cutoutWidth).coerceAtLeast(0)
            constraints.copy(minWidth = cardWidth, maxWidth = cardWidth)
        } else {
            constraints
        }

        val cardPlaceable = subcompose("card") {
            if (cutoutFullHeight) {
                // Full-height mode: simple rounded card, width constrained by layout
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = elevation,
                    shape = RoundedCornerShape(cornerRadius),
                    colors = colors,
                ) {
                    Column(modifier = Modifier.padding(contentPadding)) {
                        content()
                    }
                }
            } else {
                // Corner mode: cutout in top-right corner
                val cutoutHeight = cutoutContentHeight + gapDistancePx
                val cutoutHeightDp = with(density) { cutoutHeight.toDp() }

                Card(
                    elevation = elevation,
                    shape = CutoutTopRightCornerShape(
                        cutoutWidth = cutoutWidthDp,
                        cutoutHeight = cutoutHeightDp,
                        cornerRadius = cornerRadius,
                        cutoutCornerRadius = cornerRadius,
                    ),
                    colors = colors,
                ) {
                    CutoutAwareColumn(
                        modifier = Modifier.padding(contentPadding),
                        cutoutWidth = cutoutWidthDp,
                        cutoutHeight = cutoutHeightDp,
                        content = content,
                    )
                }
            }
        }.first().measure(cardConstraints)

        // Total width includes card + cutout area
        val totalWidth = if (cutoutFullHeight) constraints.maxWidth else cardPlaceable.width

        layout(totalWidth, cardPlaceable.height) {
            // Place the card
            cardPlaceable.placeRelative(0, 0)

            // Place cutout content
            cutoutPlaceable?.placeRelative(
                x = totalWidth - cutoutContentWidth,
                y = if (cutoutFullHeight) {
                    // Center vertically for full-height mode
                    (cardPlaceable.height - cutoutContentHeight) / 2
                } else {
                    // Flush with top for corner mode
                    0
                },
            )
        }
    }
}
