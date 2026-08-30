package eu.darken.butler.workspace.ui.floatingbar

/**
 * Defines the position of a [FloatingBarStack] relative to the screen edges.
 *
 * @param persistedKey The literal this position is stored as in the session blob's `barCollapse` map
 * and in [FloatingBarStackState.Saver]'s saved state.
 * It is spelled out instead of using the constant's name so that renaming a constant stays a rename:
 * with `name` as the key, `TOP` -> `TOP_EDGE` would quietly orphan every user's saved top bar state,
 * and nothing in the type system or the format tests would object. Changing a [persistedKey] value is
 * a format break and needs the same treatment as renaming a JSON field.
 */
enum class BarPosition(val persistedKey: String) {
    /**
     * Bars stack from the top edge downward.
     * First declared bar is closest to the top edge.
     */
    TOP("TOP"),

    /**
     * Bars stack from the bottom edge upward.
     * First declared bar is furthest from the bottom edge, last declared bar sits at it.
     */
    BOTTOM("BOTTOM"),
}
