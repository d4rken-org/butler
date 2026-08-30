package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Scope provided to bar content that exposes the bar's current state.
 */
@Stable
class FloatingBarContentScope(
    /**
     * The current scroll collapse fraction (0.0 = expanded, 1.0 = collapsed).
     * Use this for [BarScrollBehavior.CollapseOnScroll] to animate content.
     */
    val collapsedFraction: Float,
)

/**
 * Scope for declaring floating bars within a [FloatingBarStack].
 */
abstract class FloatingBarScope {

    /**
     * Declares a floating bar within the stack.
     *
     * Bars are rendered in declaration order:
     * - For [BarPosition.TOP]: first bar is closest to top edge
     * - For [BarPosition.BOTTOM]: first bar is furthest from bottom edge, last bar is at it
     *
     * @param modifier Modifier for the bar container.
     * @param key Stable identity of this bar within its stack, e.g. "toolbar". Required rather than
     *        defaulted: it is what lets a bar's collapse state be carried across compositions, and a
     *        forgotten key would silently degrade to "persistence quietly doesn't work here".
     *        Must be unique within its own stack (TOP and BOTTOM are separate, so both can hold a
     *        "toolbar"); a duplicate drops the second bar and fails loudly in debug builds.
     * @param visible Whether the bar should be visible. Animated transitions when changing.
     * @param scrollBehavior How the bar responds to scroll events.
     * @param animation Animation style for visibility changes.
     * @param estimatedHeight Estimated height for first-frame padding calculation before measurement.
     * @param revealOn Optional key whose change forces a scroll-collapse reset (re-reveals the bar).
     *        Use for bars whose [visible] stays true but whose content undergoes a meaningful mode
     *        change that should override a prior scroll-hide (e.g. entering selection mode).
     * @param content The bar content with access to [FloatingBarContentScope].
     */
    @Composable
    fun FloatingBar(
        modifier: Modifier = Modifier,
        key: String,
        visible: Boolean = true,
        scrollBehavior: BarScrollBehavior = BarScrollBehavior.Static,
        animation: BarAnimation = BarAnimation.Slide(),
        estimatedHeight: Dp = 0.dp,
        revealOn: Any? = null,
        content: @Composable FloatingBarContentScope.() -> Unit,
    ) {
        FloatingBarImpl(modifier, key, visible, scrollBehavior, animation, estimatedHeight, revealOn, content)
    }

    @Composable
    protected abstract fun FloatingBarImpl(
        modifier: Modifier,
        key: String,
        visible: Boolean,
        scrollBehavior: BarScrollBehavior,
        animation: BarAnimation,
        estimatedHeight: Dp,
        revealOn: Any?,
        content: @Composable FloatingBarContentScope.() -> Unit,
    )
}
