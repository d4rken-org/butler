package eu.darken.butler.explorer.ui.explorer

/**
 * What brings rows back to a listing that filtering emptied.
 *
 * Classified from what each combination actually yields, never from which settings happen to be
 * active: a listing can hide entries and filter entries and still be emptied by only one of them,
 * and an offer that changes nothing visible is worse than no offer at all.
 */
enum class EmptyRecovery {
    /** Revealing the hidden entries is enough. */
    SHOW_HIDDEN,

    /** Clearing this tab's filters is enough. */
    RESET_FILTERS,

    /** Either one on its own brings rows back. */
    EITHER,

    /** Neither on its own does; only both together. */
    SHOW_ALL,
}
