package eu.darken.butler.workspace.ui.scroll

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FloatDecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3Api
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
import eu.darken.butler.workspace.ui.scroll.TopToolbarScrollState.Companion.Saver
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/**
 * A scroll behavior for top toolbars that collapses to a minimal state when scrolling down
 * and expands when scrolling up. Provides snap behavior on scroll direction change.
 */
@ExperimentalMaterial3Api
@Stable
class TopToolbarScrollBehavior(
    val state: TopToolbarScrollState = TopToolbarScrollState(),
    val snapAnimationSpec: AnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow),
    val flingAnimationSpec: FloatDecayAnimationSpec? = null
) {
    val nestedScrollConnection = object : NestedScrollConnection {

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            state.heightOffsetLimit = -state.collapsibleHeight

            // Immediate snap response to scroll direction
            val delta = available.y
            return if (abs(delta) > 5f) { // Small sensitivity threshold
                when {
                    delta < 0 -> {
                        // Scrolling down → collapse to minimal state
                        state.heightOffset = state.heightOffsetLimit
                        Offset(0f, 0f)
                    }
                    delta > 0 -> {
                        // Scrolling up → expand to full state
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
                    // Downward fling → collapse
                    state.heightOffset = state.heightOffsetLimit
                }
                toFling > 0 -> {
                    // Upward fling → expand
                    state.heightOffset = 0f
                }
            }
            // Don't consume fling velocity - let the list handle it
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
 * State for managing the scroll behavior of a collapsing top toolbar.
 */
@ExperimentalMaterial3Api
@Stable
class TopToolbarScrollState(
    initialHeightOffsetLimit: Float = -Float.MAX_VALUE,
    initialHeightOffset: Float = 0f,
    initialExpandedHeight: Float = 0f,
    initialCollapsedHeight: Float = 0f
) {
    /**
     * The toolbar's expanded height in pixels (when fully shown).
     */
    var expandedHeight: Float by mutableFloatStateOf(initialExpandedHeight)

    /**
     * The toolbar's collapsed (minimal) height in pixels.
     */
    var collapsedHeight: Float by mutableFloatStateOf(initialCollapsedHeight)

    /**
     * The collapsible portion of the toolbar height (expandedHeight - collapsedHeight).
     */
    val collapsibleHeight: Float
        get() = expandedHeight - collapsedHeight

    /**
     * The toolbar's current height offset in pixels. This value is always <= 0.
     * 0 represents fully expanded, negative values represent collapsed toward minimal state.
     */
    var heightOffset: Float by mutableFloatStateOf(initialHeightOffset)

    /**
     * The limit for the toolbar's height offset (should be -collapsibleHeight).
     */
    var heightOffsetLimit by mutableFloatStateOf(initialHeightOffsetLimit)

    /**
     * Whether the toolbar can scroll (i.e., is not completely collapsed/expanded).
     */
    val canScroll: Boolean
        get() = heightOffset > heightOffsetLimit && heightOffset < 0

    /**
     * A value that represents how much the toolbar is collapsed.
     * 0.0 = fully expanded, 1.0 = fully collapsed to minimal state.
     */
    val collapsedFraction: Float
        get() = if (collapsibleHeight != 0f) {
            (abs(heightOffset) / collapsibleHeight).coerceIn(0f, 1f)
        } else 0f

    /**
     * The current height of the toolbar, interpolated between expanded and collapsed states.
     */
    val currentHeight: Float
        get() = expandedHeight + heightOffset

    companion object {
        /**
         * The default [Saver] implementation for [TopToolbarScrollState].
         */
        val Saver: Saver<TopToolbarScrollState, *> = listSaver(
            save = {
                listOf(
                    it.heightOffsetLimit,
                    it.heightOffset,
                    it.expandedHeight,
                    it.collapsedHeight
                )
            },
            restore = {
                TopToolbarScrollState(
                    initialHeightOffsetLimit = it[0],
                    initialHeightOffset = it[1],
                    initialExpandedHeight = it[2],
                    initialCollapsedHeight = it[3]
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
 * Create and remember a [TopToolbarScrollBehavior] with the default parameters.
 */
@ExperimentalMaterial3Api
@Composable
fun rememberTopToolbarScrollBehavior(
    state: TopToolbarScrollState = rememberTopToolbarScrollState(),
    snapAnimationSpec: AnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow),
    flingAnimationSpec: FloatDecayAnimationSpec? = null
): TopToolbarScrollBehavior = remember(state, snapAnimationSpec, flingAnimationSpec) {
    TopToolbarScrollBehavior(state, snapAnimationSpec, flingAnimationSpec)
}

/**
 * Create and remember a [TopToolbarScrollState].
 */
@ExperimentalMaterial3Api
@Composable
fun rememberTopToolbarScrollState(): TopToolbarScrollState {
    return rememberSaveable(saver = Saver) {
        TopToolbarScrollState()
    }
}

/**
 * Returns the current height of the toolbar in dp.
 */
@ExperimentalMaterial3Api
@Composable
fun TopToolbarScrollState.getCurrentHeightDp(): Dp {
    val density = LocalDensity.current
    return with(density) { currentHeight.toDp() }
}

/**
 * Sets the expanded and collapsed heights for the toolbar scroll state.
 */
@ExperimentalMaterial3Api
@Composable
fun TopToolbarScrollState.setHeights(expandedHeightDp: Dp, collapsedHeightDp: Dp) {
    val density = LocalDensity.current
    val expandedPx = with(density) { expandedHeightDp.toPx() }
    val collapsedPx = with(density) { collapsedHeightDp.toPx() }

    SideEffect {
        if (expandedHeight != expandedPx || collapsedHeight != collapsedPx) {
            expandedHeight = expandedPx
            collapsedHeight = collapsedPx
            heightOffsetLimit = -(expandedPx - collapsedPx)
        }
    }
}
