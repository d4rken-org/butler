package eu.darken.butler.explorer.core.favorites

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.engine.ExplorerItem

/**
 * A favorite entry resolved (or in the process of resolving) against the gateway.
 *
 * Deliberately not part of the [ExplorerItem] sealed hierarchy — favorites are
 * shown as a dedicated section on the Home screen (where unavailable favorites
 * also surface for management). In directory listings the favorite is rendered
 * inline as a regular item with a bookmark badge, sorted to the top of its
 * parent's list. Keeping `FavoriteItem` outside the `ExplorerItem` hierarchy
 * means sort/filter/picker/selection logic never needs a special case for it.
 */
data class FavoriteItem(
    val path: APath<*>,
    val state: State,
) {
    val isAvailable: Boolean get() = state is State.Available
    val isResolving: Boolean get() = state is State.Resolving
    val isUnavailable: Boolean get() = state is State.Unavailable

    val isDirectory: Boolean
        get() = state is State.Available && state.item is ExplorerItem.Directory
    val isFile: Boolean
        get() = state is State.Available && state.item is ExplorerItem.File

    sealed interface State {
        /** Lookup is in flight; render neutrally (no dim, no error). */
        data object Resolving : State

        /** Lookup succeeded; [item] is a classified [ExplorerItem.Lookup]. */
        data class Available(val item: ExplorerItem.Lookup) : State

        /** Lookup failed; the path is currently inaccessible. */
        data class Unavailable(val reason: Throwable) : State
    }
}
