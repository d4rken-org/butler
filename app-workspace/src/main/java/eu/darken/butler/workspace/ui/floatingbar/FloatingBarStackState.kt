package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
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
 * @param initialImeExtraPx Extra bottom inset for the soft keyboard, over and above the nav bar
 *        (i.e. `max(0, ime - navBar)`). Non-zero only for IME-tracking BOTTOM stacks.
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
    initialImeExtraPx: Float = 0f,
    initialEstimatedContentPaddingPx: Float = 0f,
) {
    // Make these mutableState so derivedStateOf can observe changes when updateConfig() is called
    private var defaultSpacingPx by mutableFloatStateOf(initialDefaultSpacingPx)
    private var edgePaddingPx by mutableFloatStateOf(initialEdgePaddingPx)
    private var contentGapPx by mutableFloatStateOf(initialContentGapPx)
    private var systemBarInsetPx by mutableFloatStateOf(initialSystemBarInsetPx)
    private var imeExtraPx by mutableFloatStateOf(initialImeExtraPx)
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
        // Start with system bar inset (status bar for TOP, nav bar for BOTTOM) plus any IME extra
        var totalHeight = systemBarInsetPx + imeExtraPx + edgePaddingPx

        if (barStates.isEmpty()) {
            // Use estimate before bars register (first frame / screenshot rendering)
            return@derivedStateOf if (estimatedContentPaddingPx > 0f) estimatedContentPaddingPx else totalHeight
        }

        // Spacing only counts between visible bars; a hidden trailing bar must not leave a gap
        val lastPresentIndex = barStates.indexOfLast { it.visibilityFraction > 0f || it.visible }
        barStates.forEachIndexed { index, bar ->
            if (bar.visibilityFraction > 0f || bar.visible) {
                totalHeight += bar.effectiveHeight
                if (index < lastPresentIndex) {
                    totalHeight += defaultSpacingPx * bar.layoutPresence
                }
            }
        }
        // Add content gap after the last visible bar
        if (lastPresentIndex >= 0) {
            totalHeight += contentGapPx
        }
        totalHeight.coerceAtLeast(0f)
    }

    /**
     * Whether any bar has registered yet. Bars register during composition, so a stack is briefly
     * empty and its [contentPaddingPx] is an estimate until they do.
     */
    val hasRegisteredBars: Boolean
        get() = barStates.isNotEmpty()

    /**
     * The stack's scroll-collapse state as a single fraction: 0 = expanded, 1 = collapsed.
     *
     * Reads the animation *target* rather than the current value, so it is the settled intent
     * (always 0 or 1) instead of an animation frame - persisting a half-collapsed 0.6 would restore
     * a permanently half-collapsed bar. Non-static bars share one target by construction (see the
     * nested scroll connection below), so the max is representative; it only differs transiently for
     * a bar that was invisible while the others collapsed, and that one is snapped back to 0 when it
     * becomes visible again.
     */
    val collapseTarget: Float by derivedStateOf {
        barStates
            .filter { it.scrollBehavior !is BarScrollBehavior.Static }
            .maxOfOrNull { it.scrollCollapseAnimatable.targetValue }
            ?: 0f
    }

    /**
     * Applies a restored collapse [fraction] to every non-static bar without animating: the list has
     * already drawn with the matching content padding, so an animation would visibly re-collapse the
     * bar after the fact.
     */
    suspend fun applyCollapse(fraction: Float) {
        // Copy first: snapTo suspends, and bars can register or unregister while it does
        barStates.toList().forEach { barState ->
            if (barState.scrollBehavior is BarScrollBehavior.Static) return@forEach
            barState.scrollCollapseAnimatable.snapTo(fraction)
        }
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
        imeExtraPx: Float,
        estimatedContentPaddingPx: Float = this.estimatedContentPaddingPx,
    ) {
        this.defaultSpacingPx = defaultSpacingPx
        this.edgePaddingPx = edgePaddingPx
        this.contentGapPx = contentGapPx
        this.systemBarInsetPx = systemBarInsetPx
        this.imeExtraPx = imeExtraPx
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
        // Start with system bar inset + IME extra + edge padding
        var offset = systemBarInsetPx + imeExtraPx + edgePaddingPx

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
                // systemBarInsetPx / imeExtraPx are not saved - recomputed from WindowInsets via updateConfig()
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
 * The soft-keyboard contribution to the bottom inset, expressed as an extra *over* the nav bar so
 * that `navBottomPx + imeInsetExtraPx(...) == max(navBottomPx, imeBottomPx)`. This avoids
 * double-counting the nav-bar region, which the IME inset already spans under 3-button navigation.
 */
internal fun imeInsetExtraPx(imeBottomPx: Float, navBottomPx: Float): Float =
    (imeBottomPx - navBottomPx).coerceAtLeast(0f)

/**
 * Creates and remembers a [FloatingBarStackState].
 *
 * @param position Whether this stack is at TOP or BOTTOM of the screen.
 * @param defaultSpacing Default spacing between bars.
 * @param edgePadding Padding from the screen edge to the first bar.
 * @param contentPadding Padding between the last bar and content.
 * @param includeSystemBarInset Whether to include the relevant system bar inset
 *        (status bar for TOP position, navigation bar for BOTTOM position).
 * @param includeImeInset Whether bars and content should rise above the soft keyboard. Only
 *        meaningful for a BOTTOM stack that also includes the system bar inset, and only stacks
 *        that host a text input which must stay above the keyboard (e.g. the editor) should opt
 *        in. Non-input action bars leave this `false` so a stale host IME inset (which can linger
 *        after a dialog's keyboard is dismissed) never pushes them up.
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
    includeImeInset: Boolean = false,
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

    // System bar inset based on position (status bar for TOP, nav bar for BOTTOM). No IME here.
    val systemBarInsetPx = if (includeSystemBarInset) {
        when (position) {
            BarPosition.TOP -> WindowInsets.statusBars.getTop(density).toFloat()
            BarPosition.BOTTOM -> WindowInsets.navigationBars.getBottom(density).toFloat()
        }
    } else {
        0f
    }

    // IME contribution as an extra over the nav bar so nav+extra == max(nav, ime) (no double
    // count when the IME inset already spans the nav bar region, e.g. 3-button navigation).
    // Only BOTTOM stacks that opt in track the keyboard; reading WindowInsets.ime is confined to
    // this branch so non-opted-in stacks never react to a stale host IME inset.
    val imeExtraPx = if (includeImeInset && includeSystemBarInset && position == BarPosition.BOTTOM) {
        imeInsetExtraPx(WindowInsets.ime.getBottom(density).toFloat(), systemBarInsetPx)
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
            initialImeExtraPx = imeExtraPx,
            initialEstimatedContentPaddingPx = estimatedContentPaddingPx,
        )
    }.also {
        it.updateConfig(defaultSpacingPx, edgePaddingPx, contentGapPx, systemBarInsetPx, imeExtraPx, estimatedContentPaddingPx)
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
