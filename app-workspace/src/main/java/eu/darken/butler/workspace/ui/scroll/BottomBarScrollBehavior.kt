package eu.darken.butler.workspace.ui.scroll

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FloatDecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import eu.darken.butler.workspace.ui.scroll.BottomBarScrollState.Companion.Saver
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/**
 * A scroll behavior for bottom bars that mimics the behavior of Material3's top bar scroll behaviors.
 * The bar will hide when scrolling down and show when scrolling up, similar to exitAlwaysScrollBehavior.
 */
@Stable
class BottomBarScrollBehavior(
    val state: BottomBarScrollState = BottomBarScrollState(),
    val snapAnimationSpec: AnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow),
    val flingAnimationSpec: FloatDecayAnimationSpec? = null
) {
    val nestedScrollConnection = object : NestedScrollConnection {

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            state.heightOffsetLimit = -state.height

            // Immediate response to scroll direction
            val delta = available.y
            return if (abs(delta) > 5f) { // Small sensitivity threshold
                when {
                    delta < 0 -> {
                        // Scrolling down -> immediately hide
                        state.heightOffset = state.heightOffsetLimit
                        Offset(0f, 0f)
                    }
                    delta > 0 -> {
                        // Scrolling up -> immediately show
                        state.heightOffset = 0f
                        Offset(0f, 0f)
                    }
                    else -> Offset.Zero
                }
            } else {
                Offset.Zero
            }
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            return Offset(0f, state.dispatchRawDelta(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val toFling = available.y
            when {
                toFling < 0 -> {
                    // Any downward fling -> immediately hide
                    state.heightOffset = state.heightOffsetLimit
                }
                toFling > 0 -> {
                    // Any upward fling -> immediately show
                    state.heightOffset = 0f
                }
            }
            // Don't consume fling velocity - let the list handle it for smooth scrolling
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val flingConsumed = when {
                available.y > 0 && state.collapsedFraction > 0.01f -> {
                    state.animateToExpanded(flingAnimationSpec)
                    available.y
                }
                available.y < 0 && state.collapsedFraction < 0.99f -> {
                    state.animateToCollapsed(flingAnimationSpec)
                    available.y
                }
                else -> 0f
            }
            return Velocity(0f, flingConsumed)
        }
    }
}

/**
 * State for managing the scroll behavior of a bottom bar.
 */
@Stable
class BottomBarScrollState(
    initialHeightOffsetLimit: Float = -Float.MAX_VALUE,
    initialHeightOffset: Float = 0f,
    initialContentHeight: Float = 0f
) {
    /**
     * The bottom bar's height in pixels.
     */
    var height: Float by mutableFloatStateOf(initialContentHeight)

    /**
     * The bottom bar's current height offset in pixels. This value is always <= 0, and represents
     * how much the bar is currently offset upwards to hide it.
     * 0 represents a fully shown bottom bar, while negative values represent a hidden bar.
     */
    var heightOffset: Float by mutableFloatStateOf(initialHeightOffset)

    /**
     * The limit for the bottom bar's height offset.
     */
    var heightOffsetLimit by mutableFloatStateOf(initialHeightOffsetLimit)

    /**
     * Whether the bottom bar can scroll (i.e., is not completely collapsed/expanded and has room to scroll).
     */
    val canScroll: Boolean
        get() = heightOffset > heightOffsetLimit && heightOffset < 0

    /**
     * A value that represents how much the bottom bar is collapsed.
     * 0.0 represents a fully expanded bar, and 1.0 represents a fully collapsed bar.
     */
    val collapsedFraction: Float
        get() = if (heightOffsetLimit != 0f) {
            (abs(heightOffset) / abs(heightOffsetLimit)).coerceIn(0f, 1f)
        } else 0f

    companion object {
        /**
         * The default [Saver] implementation for [BottomBarScrollState].
         */
        val Saver: Saver<BottomBarScrollState, *> = listSaver(
            save = { listOf(it.heightOffsetLimit, it.heightOffset, it.height) },
            restore = {
                BottomBarScrollState(
                    initialHeightOffsetLimit = it[0],
                    initialHeightOffset = it[1],
                    initialContentHeight = it[2]
                )
            }
        )
    }

    /**
     * Dispatches a raw delta value to the state, updating the height offset within bounds.
     * Returns the amount of delta consumed.
     */
    fun dispatchRawDelta(delta: Float): Float {
        val newOffset = heightOffset + delta
        val coercedOffset = newOffset.coerceIn(heightOffsetLimit, 0f)
        val consumed = coercedOffset - heightOffset
        heightOffset = coercedOffset
        return consumed
    }

    /**
     * Animates to the collapsed state.
     */
    suspend fun animateToCollapsed(
        animationSpec: FloatDecayAnimationSpec? = null
    ) {
        if (animationSpec != null) {
            var remainingVelocity = 0f
            animate(
                initialValue = heightOffset,
                targetValue = heightOffsetLimit,
                initialVelocity = remainingVelocity
            ) { value, velocity ->
                remainingVelocity = velocity
                heightOffset = value
            }
        } else {
            animateToCollapsed(spring<Float>(stiffness = Spring.StiffnessMediumLow))
        }
    }

    /**
     * Animates to the expanded state.
     */
    suspend fun animateToExpanded(
        animationSpec: FloatDecayAnimationSpec? = null
    ) {
        if (animationSpec != null) {
            try {
                var remainingVelocity = 0f
                animateDecay(
                    initialValue = heightOffset,
                    initialVelocity = remainingVelocity,
                    animationSpec = animationSpec
                ) { value, velocity ->
                    remainingVelocity = velocity
                    val coercedValue = value.coerceAtLeast(heightOffsetLimit)
                    heightOffset = coercedValue.coerceAtMost(0f)

                    if (coercedValue == 0f) {
                        throw CancellationException()
                    }
                }
            } catch (_: CancellationException) {
                // Animation was cancelled, which is expected when we reach the target
            }
        } else {
            animateToExpanded(spring<Float>(stiffness = Spring.StiffnessMediumLow))
        }
    }

    private suspend fun animateToCollapsed(animationSpec: AnimationSpec<Float>) {
        animate(
            initialValue = heightOffset,
            targetValue = heightOffsetLimit,
            animationSpec = animationSpec
        ) { value, _ ->
            heightOffset = value
        }
    }

    private suspend fun animateToExpanded(animationSpec: AnimationSpec<Float>) {
        animate(
            initialValue = heightOffset,
            targetValue = 0f,
            animationSpec = animationSpec
        ) { value, _ ->
            heightOffset = value
        }
    }
}

/**
 * Create and remember a [BottomBarScrollBehavior] with the default parameters.
 */
@Composable
fun rememberBottomBarScrollBehavior(
    state: BottomBarScrollState = rememberBottomBarScrollState(),
    snapAnimationSpec: AnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow),
    flingAnimationSpec: FloatDecayAnimationSpec? = null
): BottomBarScrollBehavior = remember(state, snapAnimationSpec, flingAnimationSpec) {
    BottomBarScrollBehavior(state, snapAnimationSpec, flingAnimationSpec)
}

/**
 * Create and remember a [BottomBarScrollState].
 */
@Composable
fun rememberBottomBarScrollState(): BottomBarScrollState {
    return rememberSaveable(saver = Saver) {
        BottomBarScrollState()
    }
}

/**
 * Returns the height of the bottom bar in dp for the given scroll state.
 */
@Composable
fun BottomBarScrollState.getHeightDp(): Dp {
    val density = LocalDensity.current
    return with(density) { height.toDp() }
}

/**
 * Sets the height of the bottom bar for the scroll state.
 */
@Composable
fun BottomBarScrollState.setHeight(heightDp: Dp) {
    val density = LocalDensity.current
    val heightPx = with(density) { heightDp.toPx() }

    SideEffect {
        if (height != heightPx) {
            height = heightPx
            heightOffsetLimit = -heightPx
        }
    }
}