package eu.darken.butler.workspace.ui.common

import android.graphics.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Creates a shape with a rectangular cutout in the top-right corner.
 * All corners (both outer and inner cutout corners) are rounded.
 *
 * The notch's bottom edge ends in a convex arc that sweeps out to the trailing edge, and that arc
 * consumes [transitionCornerRadius] of horizontal room. It therefore imposes a *minimum* cutout
 * width, which is why it is separate from [cornerRadius]: a small notch on a strongly rounded card
 * (the navigation rail entry) is only expressible when the transition is allowed to be tighter than
 * the card's own corners.
 *
 * @param cutoutWidth Width of the cutout notch
 * @param cutoutHeight Height of the cutout notch
 * @param cornerRadius Radius for the outer corners of the shape
 * @param cutoutCornerRadius Radius for the inner corners of the cutout
 * @param transitionCornerRadius Radius of the convex corner where the cutout meets the trailing
 *                               edge. Defaults to [cornerRadius], which reproduces the geometry
 *                               from before this parameter existed.
 */
class CutoutTopRightCornerShape(
    private val cutoutWidth: Dp,
    private val cutoutHeight: Dp,
    private val cornerRadius: Dp = 12.dp,
    private val cutoutCornerRadius: Dp = 8.dp,
    private val transitionCornerRadius: Dp = cornerRadius,
) : Shape {

    init {
        // Size-independent, so a misconfiguration is caught at construction instead of drawing a
        // self-intersecting path. The size-dependent limits are handled in createOutline, where a
        // too-small canvas degrades to a plain rounded rect rather than throwing mid-layout.
        require(cutoutWidth >= cutoutCornerRadius + transitionCornerRadius) {
            "cutoutWidth ($cutoutWidth) < cutoutCornerRadius ($cutoutCornerRadius) + transitionCornerRadius ($transitionCornerRadius)"
        }
        require(cutoutHeight >= cutoutCornerRadius * 2) {
            "cutoutHeight ($cutoutHeight) < 2 * cutoutCornerRadius ($cutoutCornerRadius)"
        }
    }

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val cutoutW = with(density) { cutoutWidth.toPx() }
        val cutoutH = with(density) { cutoutHeight.toPx() }
        val outerR = with(density) { cornerRadius.toPx() }
        val innerR = with(density) { cutoutCornerRadius.toPx() }
        val transR = with(density) { transitionCornerRadius.toPx() }

        val fits = size.width >= outerR * 2 &&
            size.height >= outerR * 2 &&
            cutoutW <= size.width - outerR - innerR &&
            cutoutH <= size.height - outerR - transR
        if (!fits) {
            return RoundedCornerShape(cornerRadius).createOutline(size, layoutDirection, density)
        }

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
            lineTo(size.width - transR, cutoutH)

            // Outer corner where cutout meets right edge
            arcTo(
                rect = Rect(
                    size.width - transR * 2,
                    cutoutH,
                    size.width,
                    cutoutH + transR * 2,
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

        if (layoutDirection == LayoutDirection.Rtl) {
            val matrix = Matrix()
            matrix.setScale(-1f, 1f, size.width / 2f, 0f)
            path.asAndroidPath().transform(matrix)
        }

        return Outline.Generic(path)
    }
}

/**
 * Robolectric cannot draw, so the notch geometry is only verifiable here. Left pair: the toolbar
 * card proportions. Right pair: the navigation rail entry, where the small notch on a 16dp-rounded
 * card only closes because [CutoutTopRightCornerShape.transitionCornerRadius] is tighter than the
 * card's own corners.
 */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CutoutTopRightCornerShapePreview() {
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CutoutTopRightCornerShape(
                        cutoutWidth = 56.dp,
                        cutoutHeight = 56.dp,
                        cornerRadius = 12.dp,
                        cutoutCornerRadius = 8.dp,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RailNotchShape,
                ),
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RailNotchShape,
                    ),
            )
        }
    }
}

private val RailNotchShape = CutoutTopRightCornerShape(
    cutoutWidth = 15.dp,
    cutoutHeight = 14.dp,
    cornerRadius = 16.dp,
    cutoutCornerRadius = 4.dp,
    transitionCornerRadius = 4.dp,
)
