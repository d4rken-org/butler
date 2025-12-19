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
     * - For [BarPosition.BOTTOM]: first bar is closest to bottom edge
     *
     * @param modifier Modifier for the bar container.
     * @param visible Whether the bar should be visible. Animated transitions when changing.
     * @param scrollBehavior How the bar responds to scroll events.
     * @param animation Animation style for visibility changes.
     * @param collapsedHeight For [BarScrollBehavior.CollapseOnScroll], the minimum collapsed height.
     * @param content The bar content with access to [FloatingBarContentScope].
     */
    @Composable
    fun FloatingBar(
        modifier: Modifier = Modifier,
        visible: Boolean = true,
        scrollBehavior: BarScrollBehavior = BarScrollBehavior.Static,
        animation: BarAnimation = BarAnimation.Slide(),
        collapsedHeight: Dp = 0.dp,
        content: @Composable FloatingBarContentScope.() -> Unit,
    ) {
        FloatingBarImpl(modifier, visible, scrollBehavior, animation, collapsedHeight, content)
    }

    @Composable
    protected abstract fun FloatingBarImpl(
        modifier: Modifier,
        visible: Boolean,
        scrollBehavior: BarScrollBehavior,
        animation: BarAnimation,
        collapsedHeight: Dp,
        content: @Composable FloatingBarContentScope.() -> Unit,
    )
}
