package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * State holder that coordinates all floating bars in a [FloatingBarStack].
 *
 * Manages:
 * - Bar registration and lifecycle
 * - Content padding calculation based on visible bars
 * - Scroll behavior coordination via [NestedScrollConnection]
 * - System bar inset integration for edge-to-edge UI
 *
 * @param position Whether this stack is at TOP or BOTTOM of the screen.
 * @param initialDefaultSpacingPx Space between consecutive bars (updated via [updateConfig]).
 * @param initialEdgePaddingPx Space from screen edge to first bar (updated via [updateConfig]).
 * @param initialContentGapPx Space after last bar before content (updated via [updateConfig]).
 * @param initialSystemBarInsetPx System bar height - status bar for TOP, nav bar for BOTTOM.
 * @param initialEstimatedContentPaddingPx Estimated total content padding for first-frame rendering.
 *        Used when bars haven't registered yet (e.g. screenshot tests, first composition frame).
 *        Once bars register, the actual calculated padding takes over.
 */
@Stable
class FloatingBarStackState(
    val position: BarPosition,
    initialDefaultSpacingPx: Float = 0f,
    initialEdgePaddingPx: Float = 0f,
    initialContentGapPx: Float = 0f,
    initialSystemBarInsetPx: Float = 0f,
    initialEstimatedContentPaddingPx: Float = 0f,
) {
    // Make these mutableState so derivedStateOf can observe changes when updateConfig() is called
    private var defaultSpacingPx by mutableFloatStateOf(initialDefaultSpacingPx)
    private var edgePaddingPx by mutableFloatStateOf(initialEdgePaddingPx)
    private var contentGapPx by mutableFloatStateOf(initialContentGapPx)
    private var systemBarInsetPx by mutableFloatStateOf(initialSystemBarInsetPx)
    private var estimatedContentPaddingPx by mutableFloatStateOf(initialEstimatedContentPaddingPx)

    internal val barStates = mutableStateListOf<FloatingBarState>()

    /**
     * Coroutine scope for animations. Set by [rememberFloatingBarStackState].
     */
    internal var animationScope: CoroutineScope? = null

    /**
     * Total content padding in pixels, calculated from system bar inset + all visible bars.
     * Clamped to non-negative to handle bounce animation overshoot.
     */
    val contentPaddingPx: Float by derivedStateOf {
        // Start with system bar inset (status bar for TOP, nav bar for BOTTOM)
        var totalHeight = systemBarInsetPx + edgePaddingPx

        if (barStates.isEmpty()) {
            // Use estimate before bars register (first frame / screenshot rendering)
            return@derivedStateOf if (estimatedContentPaddingPx > 0f) estimatedContentPaddingPx else totalHeight
        }

        var hasVisibleBars = false
        barStates.forEachIndexed { index, bar ->
            if (bar.visibilityFraction > 0f || bar.visible) {
                hasVisibleBars = true
                totalHeight += bar.effectiveHeight
                if (index < barStates.lastIndex) {
                    totalHeight += defaultSpacingPx * bar.layoutPresence
                }
            }
        }
        // Add content gap after the last visible bar
        if (hasVisibleBars) {
            totalHeight += contentGapPx
        }
        totalHeight.coerceAtLeast(0f)
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
                // Invisible bars shouldn't accumulate scroll-collapse state
                if (!barState.visible && barState.visibilityFraction <= 0f) return@forEach

                val targetFraction = when {
                    delta < 0 -> 1f // Scrolling down -> hide/collapse
                    delta > 0 -> 0f // Scrolling up -> show/expand
                    else -> null
                }

                if (targetFraction != null) {
                    when (barState.scrollBehavior) {
                        is BarScrollBehavior.HideOnScroll,
                        is BarScrollBehavior.CollapseOnScroll,
                        is BarScrollBehavior.VanishOnScroll -> {
                            barState.triggerScrollCollapse(scope, targetFraction)
                        }
                        is BarScrollBehavior.Static -> {}
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
                if (!barState.visible && barState.visibilityFraction <= 0f) return@forEach

                val targetFraction = when {
                    velocity < 0 -> 1f // Fling down -> hide/collapse
                    velocity > 0 -> 0f // Fling up -> show/expand
                    else -> null
                }

                if (targetFraction != null) {
                    when (barState.scrollBehavior) {
                        is BarScrollBehavior.HideOnScroll,
                        is BarScrollBehavior.CollapseOnScroll,
                        is BarScrollBehavior.VanishOnScroll -> {
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
     * Resets every bar's scroll-collapse fraction to 0. Use when the scrollable content
     * underneath meaningfully changes (e.g. navigating to a new directory, opening a new file)
     * so bars don't remain scroll-hidden over fresh content the user hasn't scrolled yet.
     *
     * Static bars are not affected (their fraction is always 0 by construction).
     */
    fun resetScrollCollapse() {
        val scope = animationScope ?: return
        barStates.forEach { barState ->
            if (barState.scrollBehavior !is BarScrollBehavior.Static &&
                barState.scrollCollapsedFraction > 0f) {
                scope.launch { barState.scrollCollapseAnimatable.snapTo(0f) }
            }
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
     * Updates spacing configuration including system bar inset.
     */
    internal fun updateConfig(
        defaultSpacingPx: Float,
        edgePaddingPx: Float,
        contentGapPx: Float,
        systemBarInsetPx: Float,
        estimatedContentPaddingPx: Float = this.estimatedContentPaddingPx,
    ) {
        this.defaultSpacingPx = defaultSpacingPx
        this.edgePaddingPx = edgePaddingPx
        this.contentGapPx = contentGapPx
        this.systemBarInsetPx = systemBarInsetPx
        this.estimatedContentPaddingPx = estimatedContentPaddingPx
    }

    /**
     * Calculates the offset for a bar at the given index.
     * For TOP position: offset from top edge (sum heights of bars BEFORE this one).
     * For BOTTOM position: offset from bottom edge (sum heights of bars AFTER this one).
     *
     * Includes system bar inset to position bars below status bar (TOP) or above nav bar (BOTTOM).
     *
     * Bars are declared in visual top-to-bottom order for both positions:
     * - TOP: first bar is at top edge, subsequent bars stack downward
     * - BOTTOM: first bar is furthest from edge, last bar is at bottom edge
     */
    internal fun getBarOffset(index: Int): Float {
        // Start with system bar inset + edge padding
        var offset = systemBarInsetPx + edgePaddingPx

        // For TOP: sum bars BEFORE this one (closer to edge = lower index)
        // For BOTTOM: sum bars AFTER this one (closer to edge = higher index)
        val range = when (position) {
            BarPosition.TOP -> 0 until index
            BarPosition.BOTTOM -> (index + 1) until barStates.size
        }

        for (i in range) {
            val bar = barStates.getOrNull(i) ?: continue
            if (bar.visibilityFraction > 0f || bar.visible) {
                offset += bar.effectiveHeight + (defaultSpacingPx * bar.layoutPresence)
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
                    state.contentGapPx,
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
                val contentGapPx = saved[3] as Float
                // systemBarInsetPx is not saved - it's recomputed from WindowInsets via updateConfig()
                FloatingBarStackState(
                    position = position,
                    initialDefaultSpacingPx = spacingPx,
                    initialEdgePaddingPx = edgePx,
                    initialContentGapPx = contentGapPx,
                ).also { state ->
                    // Bar states are restored when bars re-register during recomposition
                }
            },
        )
    }
}

/**
 * Creates and remembers a [FloatingBarStackState].
 *
 * @param position Whether this stack is at TOP or BOTTOM of the screen.
 * @param defaultSpacing Default spacing between bars.
 * @param edgePadding Padding from the screen edge to the first bar.
 * @param contentPadding Padding between the last bar and content.
 * @param includeSystemBarInset Whether to include the relevant system bar inset
 *        (status bar for TOP position, navigation bar for BOTTOM position).
 * @param estimatedContentPadding Estimated total content padding for first-frame rendering.
 *        Used when bars haven't registered yet (e.g. screenshot tests, first composition frame).
 *        Once bars register, the actual calculated padding takes over.
 */
@Composable
fun rememberFloatingBarStackState(
    position: BarPosition,
    defaultSpacing: Dp = 8.dp,
    edgePadding: Dp = 8.dp,
    contentPadding: Dp = 0.dp,
    includeSystemBarInset: Boolean = true,
    estimatedContentPadding: Dp = Dp.Unspecified,
): FloatingBarStackState {
    val density = LocalDensity.current
    val defaultSpacingPx = with(density) { defaultSpacing.toPx() }
    val edgePaddingPx = with(density) { edgePadding.toPx() }
    val contentGapPx = with(density) { contentPadding.toPx() }
    val estimatedContentPaddingPx = if (estimatedContentPadding != Dp.Unspecified) {
        with(density) { estimatedContentPadding.toPx() }
    } else {
        0f
    }

    // Get system bar inset based on position (status bar for TOP, nav bar + IME for BOTTOM)
    val systemBarInsetPx = if (includeSystemBarInset) {
        when (position) {
            BarPosition.TOP -> WindowInsets.statusBars.getTop(density).toFloat()
            BarPosition.BOTTOM -> WindowInsets.navigationBars.union(WindowInsets.ime).getBottom(density).toFloat()
        }
    } else {
        0f
    }

    val scope = rememberCoroutineScope()

    return rememberSaveable(saver = FloatingBarStackState.Saver) {
        FloatingBarStackState(
            position = position,
            initialDefaultSpacingPx = defaultSpacingPx,
            initialEdgePaddingPx = edgePaddingPx,
            initialContentGapPx = contentGapPx,
            initialSystemBarInsetPx = systemBarInsetPx,
            initialEstimatedContentPaddingPx = estimatedContentPaddingPx,
        )
    }.also {
        it.updateConfig(defaultSpacingPx, edgePaddingPx, contentGapPx, systemBarInsetPx, estimatedContentPaddingPx)
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
