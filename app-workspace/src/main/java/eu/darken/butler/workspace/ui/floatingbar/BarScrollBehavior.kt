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
     * The bar content receives [FloatingBarContentScope.collapsedFraction] to animate itself.
     * Use [FloatingBar.collapsedHeight] to set a minimum layout height floor.
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
