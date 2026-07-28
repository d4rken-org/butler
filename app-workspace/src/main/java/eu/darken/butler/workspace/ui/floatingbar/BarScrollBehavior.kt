package eu.darken.butler.workspace.ui.floatingbar

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
     * The bar keeps its measured height and its content animates itself via
     * [FloatingBarContentScope.collapsedFraction]; there is no layout-height floor.
     *
     * A bar that must not shrink below some height enforces that on its own content (e.g.
     * `requiredHeightIn`), so the height the stack lays out against is always the height that was
     * actually measured.
     */
    data object CollapseOnScroll : BarScrollBehavior

    /**
     * Bar completely hides when scrolling down, shows when scrolling up.
     */
    data object HideOnScroll : BarScrollBehavior

    /**
     * Bar fades out and shrinks in place when scrolling.
     * Unlike [HideOnScroll], the bar does not slide off screen - it vanishes where it is.
     * Other bars animate to fill the gap.
     */
    data object VanishOnScroll : BarScrollBehavior
}
