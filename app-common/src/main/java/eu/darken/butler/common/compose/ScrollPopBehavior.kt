package eu.darken.butler.common.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * Configuration for scroll-reactive pop animation behavior
 */
data class ScrollPopConfig(
    val hideOnScrollDown: Boolean = true,
    val showAnimationSpec: AnimationSpec<Float> = spring(
        dampingRatio = 0.6f,
        stiffness = 800f
    ),
    val hideAnimationSpec: AnimationSpec<Float> = spring(
        dampingRatio = 0.8f,
        stiffness = 1000f
    ),
    val scaleWhenHidden: Float = 0f,
    val scaleWhenVisible: Float = 1f,
    val scalePopEffect: Float = 1.1f, // Brief overshoot when showing
    val rotationWhenHidden: Float = -15f,
    val alphaWhenHidden: Float = 0f,
    val scrollThreshold: Float = 5f // Minimum scroll to trigger
)

/**
 * A composable modifier that applies playful pop animation based on scroll behavior.
 * The wrapped content will "pop out" when scrolling down and "pop in" when scrolling up
 * with customizable spring animations.
 *
 * @param config Configuration for the pop animation behavior
 */
@Composable
fun Modifier.scrollPopBehavior(
    config: ScrollPopConfig = ScrollPopConfig()
): Modifier {
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var isVisible by remember { mutableFloatStateOf(1f) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                scrollOffset += delta

                // Only trigger changes if scroll exceeds threshold
                if (kotlin.math.abs(delta) > config.scrollThreshold) {
                    when {
                        config.hideOnScrollDown && delta < 0 -> {
                            // Scrolling down - hide
                            isVisible = 0f
                        }
                        delta > 0 -> {
                            // Scrolling up - show
                            isVisible = 1f
                        }
                    }
                }

                return Offset.Zero
            }
        }
    }

    return this.nestedScroll(nestedScrollConnection)
}

/**
 * Wraps content with scroll-reactive pop animation.
 * The content will animate with scale, rotation, and alpha effects based on external scroll state.
 *
 * @param isVisible Whether the content should be visible (controlled externally)
 * @param config Configuration for the pop animation
 * @param content The content to wrap with pop animation
 */
@Composable
fun ScrollPopWrapper(
    isVisible: Boolean,
    config: ScrollPopConfig = ScrollPopConfig(),
    content: @Composable () -> Unit
) {
    val targetVisibility = if (isVisible) 1f else 0f

    // Animated values for smooth transitions
    val animatedScale by animateFloatAsState(
        targetValue = if (isVisible) config.scaleWhenVisible else config.scaleWhenHidden,
        animationSpec = if (isVisible) config.showAnimationSpec else config.hideAnimationSpec,
        label = "scale"
    )

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else config.alphaWhenHidden,
        animationSpec = if (isVisible) config.showAnimationSpec else config.hideAnimationSpec,
        label = "alpha"
    )

    val animatedRotation by animateFloatAsState(
        targetValue = if (isVisible) 0f else config.rotationWhenHidden,
        animationSpec = if (isVisible) config.showAnimationSpec else config.hideAnimationSpec,
        label = "rotation"
    )

    // Pop effect: briefly scale beyond target when showing
    val popScale = if (isVisible && animatedScale > config.scaleWhenVisible * 0.8f) {
        config.scalePopEffect
    } else {
        animatedScale
    }

    val finalScale by animateFloatAsState(
        targetValue = popScale,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 1200f),
        label = "popScale"
    )

    Box(
        modifier = Modifier
            .scale(finalScale)
            .alpha(animatedAlpha)
            .rotate(animatedRotation)
    ) {
        content()
    }
}

/**
 * Convenience composable that wraps content with scroll pop behavior.
 * This version requires external scroll state management.
 */
@Composable
fun ScrollPop(
    isVisible: Boolean,
    hideOnScrollDown: Boolean = true,
    animationSpec: AnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f),
    content: @Composable () -> Unit
) {
    ScrollPopWrapper(
        isVisible = isVisible,
        config = ScrollPopConfig(
            hideOnScrollDown = hideOnScrollDown,
            showAnimationSpec = animationSpec,
            hideAnimationSpec = animationSpec
        )
    ) {
        content()
    }
}