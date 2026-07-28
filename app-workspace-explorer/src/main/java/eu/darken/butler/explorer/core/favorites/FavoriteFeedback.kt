package eu.darken.butler.explorer.core.favorites

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath

/**
 * UI-state record for a favorite mutation the user should still get feedback on.
 *
 * Holds only the minimal data needed to render the confirmation bar and to act on it —
 * deliberately does NOT carry a [FavoriteItem] (which can hold an [ExplorerItem.Lookup]
 * or `Throwable`) to keep UI state slim and avoid pinning resolver outputs in memory
 * while the bar is shown.
 *
 * @param id Monotonically-increasing id used by the timeout coroutine to verify that this
 *           feedback is still the active one before clearing — avoids a stale timer from a
 *           superseded mutation clobbering a fresh one.
 * @param displayName Human-readable name of the single (or first) affected entry.
 * @param count Number of affected entries; picks the singular or plural message.
 */
sealed interface FavoriteFeedback {
    val id: Long
    val displayName: CaString
    val count: Int

    /** Paths were added; the bar offers to show them in the Home screen's favorites section. */
    data class Added(
        override val id: Long,
        override val displayName: CaString,
        val paths: List<APath<*>>,
    ) : FavoriteFeedback {
        override val count: Int get() = paths.size
    }

    /**
     * Paths were removed; the bar offers undo. [removed] carries the original indices so
     * [ExplorerFavoritesRepo.addAllAt] restores each entry at its previous position.
     */
    data class Removed(
        override val id: Long,
        override val displayName: CaString,
        val removed: List<ExplorerFavoritesRepo.RemovedFavorite>,
    ) : FavoriteFeedback {
        override val count: Int get() = removed.size
    }
}
