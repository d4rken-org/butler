package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Defines how a [FloatingBar] responds to scroll events.
 */
sealed interface BarScrollBehavior {

    /**
     * Bar does not respond to scroll events. Always visible at full height.
     */
    data object Static : BarScrollBehavior

    /**
     * Bar collapses to a smaller height when scrolling down, expands when scrolling up.
     *
     * @param collapsedHeight The minimum height when fully collapsed. Use 0.dp to fully hide.
     */
    data class CollapseOnScroll(
        val collapsedHeight: Dp = 0.dp,
    ) : BarScrollBehavior

    /**
     * Bar completely hides when scrolling down, shows when scrolling up.
     */
    data object HideOnScroll : BarScrollBehavior
}
