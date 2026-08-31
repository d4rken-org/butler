package eu.darken.butler.developer.ui

/**
 * Floating bar keys for the Developer workspace.
 *
 * These are persisted in the workspace session's UI state blob as part of the bar collapse
 * fractions: renaming one orphans the stored fraction for that bar, so it comes back expanded while
 * the rest of its stack stays as it was.
 */
internal object DeveloperBarKeys {
    const val OPERATIONS = "operations"
}
