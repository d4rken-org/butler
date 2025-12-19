package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

/**
 * State holder that coordinates all floating bars in a [FloatingBarStack].
 *
 * Manages:
 * - Bar registration and lifecycle
 * - Content padding calculation based on visible bars
 * - Scroll behavior coordination via [NestedScrollConnection]
 *
 * @param position Whether this stack is at TOP or BOTTOM of the screen.
 * @param defaultSpacing Default spacing between bars in pixels.
 * @param edgePadding Padding from screen edge in pixels.
 */
@Stable
class FloatingBarStackState(
    val position: BarPosition,
    private var defaultSpacingPx: Float = 0f,
    private var edgePaddingPx: Float = 0f,
) {
    internal val barStates = mutableStateListOf<FloatingBarState>()

    /**
     * Coroutine scope for animations. Set by [rememberFloatingBarStackState].
     */
    internal var animationScope: CoroutineScope? = null

    /**
     * Total content padding in pixels, calculated from all visible bars.
     */
    val contentPaddingPx: Float by derivedStateOf {
        if (barStates.isEmpty()) return@derivedStateOf edgePaddingPx

        var totalHeight = edgePaddingPx
        barStates.forEachIndexed { index, bar ->
            if (bar.visibilityFraction > 0f || bar.visible) {
                totalHeight += bar.effectiveHeight
                if (index < barStates.lastIndex) {
                    totalHeight += defaultSpacingPx * bar.visibilityFraction
                }
            }
        }
        totalHeight
    }

    /**
     * Nested scroll connection that coordinates scroll behavior across all bars.
     */
    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val delta = available.y
            if (abs(delta) <= SCROLL_THRESHOLD) return Offset.Zero

            val scope = animationScope ?: return Offset.Zero

            barStates.forEach { barState ->
                val targetFraction = when {
                    delta < 0 -> 1f // Scrolling down -> hide/collapse
                    delta > 0 -> 0f // Scrolling up -> show/expand
                    else -> null
                }

                if (targetFraction != null) {
                    when (barState.scrollBehavior) {
                        is BarScrollBehavior.HideOnScroll,
                        is BarScrollBehavior.CollapseOnScroll -> {
                            barState.triggerScrollCollapse(scope, targetFraction)
                        }
                        is BarScrollBehavior.Static -> {
                            // No response to scroll
                        }
                    }
                }
            }
            return Offset.Zero // Don't consume scroll
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset = Offset.Zero

        override suspend fun onPreFling(available: Velocity): Velocity {
            val velocity = available.y
            val scope = animationScope ?: return Velocity.Zero

            barStates.forEach { barState ->
                val targetFraction = when {
                    velocity < 0 -> 1f // Fling down -> hide/collapse
                    velocity > 0 -> 0f // Fling up -> show/expand
                    else -> null
                }

                if (targetFraction != null) {
                    when (barState.scrollBehavior) {
                        is BarScrollBehavior.HideOnScroll,
                        is BarScrollBehavior.CollapseOnScroll -> {
                            barState.triggerScrollCollapse(scope, targetFraction)
                        }
                        is BarScrollBehavior.Static -> {}
                    }
                }
            }
            return Velocity.Zero // Don't consume fling
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            return Velocity.Zero
        }
    }

    /**
     * Registers a bar with this stack.
     */
    internal fun registerBar(bar: FloatingBarState) {
        if (barStates.none { it.id == bar.id }) {
            barStates.add(bar)
        }
    }

    /**
     * Unregisters a bar from this stack.
     */
    internal fun unregisterBar(barId: String) {
        barStates.removeAll { it.id == barId }
    }

    /**
     * Updates spacing configuration.
     */
    internal fun updateConfig(defaultSpacingPx: Float, edgePaddingPx: Float) {
        this.defaultSpacingPx = defaultSpacingPx
        this.edgePaddingPx = edgePaddingPx
    }

    /**
     * Calculates the offset for a bar at the given index.
     * For TOP position: offset from top edge.
     * For BOTTOM position: offset from bottom edge.
     */
    internal fun getBarOffset(index: Int): Float {
        var offset = edgePaddingPx
        for (i in 0 until index) {
            val bar = barStates.getOrNull(i) ?: continue
            if (bar.visibilityFraction > 0f || bar.visible) {
                offset += bar.effectiveHeight + (defaultSpacingPx * bar.visibilityFraction)
            }
        }
        return offset
    }

    companion object {
        private const val SCROLL_THRESHOLD = 5f

        val Saver: Saver<FloatingBarStackState, *> = listSaver(
            save = { state ->
                listOf(
                    state.position.ordinal,
                    state.defaultSpacingPx,
                    state.edgePaddingPx,
                    state.barStates.map { bar ->
                        listOf(
                            bar.id,
                            bar.scrollBehavior.javaClass.simpleName,
                            bar.visible,
                            bar.scrollCollapsedFraction,
                        )
                    },
                )
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val position = BarPosition.entries[saved[0] as Int]
                val spacingPx = saved[1] as Float
                val edgePx = saved[2] as Float
                FloatingBarStackState(position, spacingPx, edgePx).also { state ->
                    // Bar states are restored when bars re-register during recomposition
                }
            },
        )
    }
}

/**
 * Creates and remembers a [FloatingBarStackState].
 */
@Composable
fun rememberFloatingBarStackState(
    position: BarPosition,
    defaultSpacing: Dp = 8.dp,
    edgePadding: Dp = 8.dp,
): FloatingBarStackState {
    val density = LocalDensity.current
    val defaultSpacingPx = with(density) { defaultSpacing.toPx() }
    val edgePaddingPx = with(density) { edgePadding.toPx() }
    val scope = rememberCoroutineScope()

    return rememberSaveable(saver = FloatingBarStackState.Saver) {
        FloatingBarStackState(position, defaultSpacingPx, edgePaddingPx)
    }.also {
        it.updateConfig(defaultSpacingPx, edgePaddingPx)
        it.animationScope = scope
    }
}

/**
 * Returns the content padding in Dp.
 */
@Composable
fun FloatingBarStackState.contentPaddingDp(): Dp {
    val density = LocalDensity.current
    return with(density) { contentPaddingPx.toDp() }
}
