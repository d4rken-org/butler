package eu.darken.butler.explorer.core.engine

import eu.darken.butler.explorer.core.ExplorerNavigation

/**
 * The user cancelled a load that had nothing to fall back to.
 *
 * A marker, not a failure: the UI answers it with the aborted dialog (offering a retry of [target])
 * instead of the generic navigation error card.
 */
class BrowsingAbortedException(
    val target: ExplorerNavigation.Target,
) : Exception("Browsing aborted by user")
