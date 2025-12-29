package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

/**
 * State holder for an individual floating bar within a [FloatingBarStack].
 *
 * Tracks the bar's measured height, visibility, and animated height for smooth transitions.
 */
@Stable
class FloatingBarState(
    val id: String = Uuid.random().toString(),
    scrollBehavior: BarScrollBehavior = BarScrollBehavior.Static,
    val animation: BarAnimation = BarAnimation.Slide(),
    initialVisible: Boolean = true,
    estimatedHeightPx: Float = 0f,
) {
    /**
     * How the bar responds to scroll events. Can be updated dynamically.
     */
    var scrollBehavior: BarScrollBehavior by mutableStateOf(scrollBehavior)

    /**
     * The bar's natural/measured height in pixels.
     * Initialized with estimated height for correct first-frame padding calculation.
     */
    var measuredHeight: Float by mutableFloatStateOf(estimatedHeightPx)

    /**
     * Whether the bar should be visible.
     */
    var visible: Boolean by mutableStateOf(initialVisible)
        internal set

    /**
     * The collapsed height for [BarScrollBehavior.CollapseOnScroll], in pixels.
     */
    var collapsedHeightPx: Float by mutableFloatStateOf(0f)

    /**
     * Internal animatable for smooth scroll collapse transitions.
     * 0.0 = fully expanded, 1.0 = fully collapsed/hidden.
     */
    internal val scrollCollapseAnimatable = Animatable(0f)

    /**
     * Current animated collapse fraction from scroll behavior.
     */
    val scrollCollapsedFraction: Float
        get() = scrollCollapseAnimatable.value

    /**
     * Tracks whether the hide animation has reached its target (fraction >= 1).
     */
    private var _hasReachedHideTarget = false

    /**
     * Clamped fraction for edge bar translation.
     * Only prevents bounce-back when HIDING (to avoid peek past screen edge).
     * Allows full bounce when SHOWING (bar bounces into view naturally).
     */
    val edgeClampedFraction: Float
        get() {
            val raw = scrollCollapsedFraction
            val target = scrollCollapseAnimatable.targetValue

            // SHOWING (target = 0): Allow full bounce
            if (target == 0f) {
                _hasReachedHideTarget = false
                return raw  // No clamping, full bounce when appearing
            }

            // HIDING (target = 1): Prevent bounce-back peek
            if (raw >= 1f) {
                _hasReachedHideTarget = true
            }

            return if (_hasReachedHideTarget) {
                1f  // Snap to fully hidden, no peek
            } else {
                raw  // Animate normally toward hidden
            }
        }

    /**
     * Internal animatable for smooth height transitions when visibility changes.
     */
    internal val visibilityAnimatable = Animatable(if (initialVisible) 1f else 0f)

    /**
     * The current visibility multiplier (0.0 to 1.0) after animation.
     */
    val visibilityFraction: Float
        get() = visibilityAnimatable.value

    /**
     * The effective height considering both visibility and scroll collapse.
     * This is the height that should be used for layout and padding calculations.
     *
     * Note: Fraction is clamped to [0, 1] for layout stability.
     * This allows visual bounce animations to overshoot without breaking bar positioning.
     */
    val effectiveHeight: Float
        get() {
            val baseHeight = when (scrollBehavior) {
                is BarScrollBehavior.Static -> measuredHeight
                // For CollapseOnScroll, the content handles its own collapse animation
                // via collapsedFraction. Use collapsedHeightPx as a minimum floor for safety.
                is BarScrollBehavior.CollapseOnScroll -> measuredHeight.coerceAtLeast(collapsedHeightPx)
                is BarScrollBehavior.HideOnScroll,
                is BarScrollBehavior.VanishOnScroll -> {
                    val clampedFraction = scrollCollapsedFraction.coerceIn(0f, 1f)
                    measuredHeight * (1f - clampedFraction)
                }
            }
            return baseHeight * visibilityFraction
        }

    /**
     * Animates scroll collapse to target value.
     */
    suspend fun animateScrollCollapse(targetFraction: Float, animationSpec: AnimationSpec<Float>) {
        scrollCollapseAnimatable.animateTo(
            targetValue = targetFraction,
            animationSpec = animationSpec,
        )
    }

    /**
     * Triggers animated scroll collapse in the given scope.
     */
    fun triggerScrollCollapse(scope: CoroutineScope, targetFraction: Float) {
        scope.launch {
            scrollCollapseAnimatable.animateTo(
                targetValue = targetFraction,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    /**
     * Animates visibility change.
     */
    suspend fun animateVisibility(targetVisible: Boolean, animationSpec: AnimationSpec<Float>) {
        visible = targetVisible
        visibilityAnimatable.animateTo(
            targetValue = if (targetVisible) 1f else 0f,
            animationSpec = animationSpec,
        )
    }

    /**
     * Immediately sets visibility without animation.
     */
    fun setVisibilityImmediate(targetVisible: Boolean) {
        visible = targetVisible
        runBlocking {
            visibilityAnimatable.snapTo(if (targetVisible) 1f else 0f)
        }
    }
}
