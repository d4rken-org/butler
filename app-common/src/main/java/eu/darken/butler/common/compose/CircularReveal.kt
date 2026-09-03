package eu.darken.butler.common.compose

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.hypot
import kotlin.math.max

/** Material's emphasized decelerate curve, for content entering the screen. */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

/** Material's emphasized accelerate curve, for content leaving the screen. */
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

/**
 * Distance from [origin] to the farthest corner of a rect of [size] — the radius at which a circle
 * around [origin] covers that rect completely.
 */
fun maxRevealRadius(origin: Offset, size: Size): Float {
    val left = origin.x
    val top = origin.y
    val right = size.width - origin.x
    val bottom = size.height - origin.y
    return max(
        max(hypot(left, top), hypot(right, top)),
        max(hypot(left, bottom), hypot(right, bottom)),
    )
}

/**
 * Circle around [origin] that grows from nothing at [progress] `0` to the whole layer at `1`.
 * A null [origin] centres the circle.
 *
 * A uniform round rect whose corner radius is half its side is a circle and stays on the outline
 * fast path, which an [Outline.Generic] path would not.
 */
@Immutable
class CircularRevealShape(
    private val progress: Float,
    private val origin: Offset?,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (progress >= 1f) return Outline.Rectangle(size.toRect())
        val centre = origin ?: size.center
        val radius = maxRevealRadius(centre, size) * progress
        return Outline.Rounded(
            RoundRect(
                left = centre.x - radius,
                top = centre.y - radius,
                right = centre.x + radius,
                bottom = centre.y + radius,
                cornerRadius = CornerRadius(radius),
            ),
        )
    }
}

/**
 * Clips the content to a [CircularRevealShape]. Both parameters are lambdas and the layer block is
 * deferred, so an animated progress stays in the draw phase instead of recomposing the content.
 *
 * [origin] is in the coordinates of the node this is applied to.
 */
fun Modifier.circularReveal(
    progress: () -> Float,
    origin: () -> Offset?,
): Modifier = graphicsLayer {
    clip = true
    shape = CircularRevealShape(progress(), origin())
}
