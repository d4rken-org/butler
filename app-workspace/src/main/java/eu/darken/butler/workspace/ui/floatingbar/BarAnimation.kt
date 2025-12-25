package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Defines the animation style for a [FloatingBar] when showing/hiding.
 */
sealed interface BarAnimation {

    /**
     * No animation - immediate state change.
     */
    data object Immediate : BarAnimation

    /**
     * Smooth slide animation with configurable duration.
     *
     * @param durationMillis Animation duration in milliseconds.
     */
    data class Slide(
        val durationMillis: Int = 150,
    ) : BarAnimation

    /**
     * Physics-based spring animation.
     *
     * @param dampingRatio Controls oscillation. Lower values = more bounce.
     * @param stiffness Controls speed. Higher values = faster animation.
     */
    data class Spring(
        val dampingRatio: Float = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
        val stiffness: Float = androidx.compose.animation.core.Spring.StiffnessMedium,
    ) : BarAnimation

    companion object {
        /**
         * Playful bouncy animation preset.
         */
        val Bouncy = Spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
        )
    }
}

/**
 * Converts [BarAnimation] to a Compose [AnimationSpec].
 */
fun BarAnimation.toAnimationSpec(): AnimationSpec<Float> = when (this) {
    is BarAnimation.Immediate -> tween(durationMillis = 0)
    is BarAnimation.Slide -> tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
    is BarAnimation.Spring -> spring(dampingRatio = dampingRatio, stiffness = stiffness)
}
