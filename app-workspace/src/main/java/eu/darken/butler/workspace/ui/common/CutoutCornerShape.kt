package eu.darken.butler.workspace.ui.common

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Creates a shape with a rectangular cutout in the top-right corner.
 * All corners (both outer and inner cutout corners) are rounded.
 *
 * @param cutoutWidth Width of the cutout notch
 * @param cutoutHeight Height of the cutout notch
 * @param cornerRadius Radius for the outer corners of the shape
 * @param cutoutCornerRadius Radius for the inner corners of the cutout
 */
class CutoutTopRightCornerShape(
    private val cutoutWidth: Dp,
    private val cutoutHeight: Dp,
    private val cornerRadius: Dp = 12.dp,
    private val cutoutCornerRadius: Dp = 8.dp,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val cutoutW = with(density) { cutoutWidth.toPx() }
        val cutoutH = with(density) { cutoutHeight.toPx() }
        val outerR = with(density) { cornerRadius.toPx() }
        val innerR = with(density) { cutoutCornerRadius.toPx() }

        val path = Path().apply {
            // Start at top-left, after the corner arc
            moveTo(0f, outerR)

            // Top-left corner arc
            arcTo(
                rect = Rect(0f, 0f, outerR * 2, outerR * 2),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )

            // Top edge to where cutout begins
            lineTo(size.width - cutoutW - innerR, 0f)

            // Cutout top-left inner corner (curves into the cutout)
            arcTo(
                rect = Rect(
                    size.width - cutoutW - innerR * 2,
                    0f,
                    size.width - cutoutW,
                    innerR * 2,
                ),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )

            // Left edge of cutout (going down)
            lineTo(size.width - cutoutW, cutoutH - innerR)

            // Cutout bottom-left inner corner (curves out of the cutout)
            arcTo(
                rect = Rect(
                    size.width - cutoutW,
                    cutoutH - innerR * 2,
                    size.width - cutoutW + innerR * 2,
                    cutoutH,
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = -90f,
                forceMoveTo = false,
            )

            // Bottom edge of cutout toward right edge
            lineTo(size.width - outerR, cutoutH)

            // Outer corner where cutout meets right edge
            arcTo(
                rect = Rect(
                    size.width - outerR * 2,
                    cutoutH,
                    size.width,
                    cutoutH + outerR * 2,
                ),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )

            // Right edge down to bottom-right corner
            lineTo(size.width, size.height - outerR)

            // Bottom-right corner arc
            arcTo(
                rect = Rect(
                    size.width - outerR * 2,
                    size.height - outerR * 2,
                    size.width,
                    size.height,
                ),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )

            // Bottom edge
            lineTo(outerR, size.height)

            // Bottom-left corner arc
            arcTo(
                rect = Rect(0f, size.height - outerR * 2, outerR * 2, size.height),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )

            close()
        }

        return Outline.Generic(path)
    }
}
