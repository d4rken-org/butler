package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.elements.favoriteContentIndex

/**
 * Lazy index a reveal of [request] should scroll to in this emission.
 *
 * @return `null` while this emission cannot answer - the listing is still loading, it belongs to
 *         another location than the reveal was aimed at, or it does not hold the requested path.
 *
 * A destination-scoped reveal additionally refuses the loader's peek stage: peek rows carry the
 * destination's own location id, but they are raw listing order, so an index resolved from them
 * points at a different row than the one the final ordering puts the path at.
 */
internal fun ExplorerWorkspaceViewModel.State.revealIndexFor(
    request: ExplorerWorkspaceViewModel.RevealRequest,
): Int? {
    if (request.destination != null) {
        if (request.destination != listingLocationId) return null
        if (items?.any { it is ExplorerItem.Peek } == true) return null
    }
    val index = when (request.scope) {
        // Favorites live in a trailing section, not in `items`.
        ExplorerWorkspaceViewModel.RevealRequest.Scope.Favorites -> favoriteContentIndex(request.path)
        ExplorerWorkspaceViewModel.RevealRequest.Scope.Items -> items?.indexOfFirst { item ->
            when (item) {
                is ExplorerItem.Path -> item.path.path == request.path.path
                else -> false
            }
        }
    }
    return index?.takeIf { it >= 0 }
}
