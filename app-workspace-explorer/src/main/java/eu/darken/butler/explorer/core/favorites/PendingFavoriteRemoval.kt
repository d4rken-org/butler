package eu.darken.butler.explorer.core.favorites

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath

/**
 * UI-state record for a recently-removed favorite that the user can still undo.
 *
 * Holds only the minimal data needed to render the undo prompt and to restore the
 * favorite at its original position — deliberately does NOT carry a [FavoriteItem]
 * (which can hold an [ExplorerItem.Lookup] or `Throwable`) to keep UI state slim
 * and avoid pinning resolver outputs in memory while the prompt is shown.
 *
 * @param id Monotonically-increasing id used by the timeout coroutine to verify
 *           that this removal is still the active one before clearing — avoids
 *           a stale timer from a superseded removal clobbering a fresh one.
 * @param path The exact [APath] that was removed (already gone from storage).
 * @param displayName Human-readable name used in the "Removed X" message.
 * @param originalIndex Index the favorite occupied prior to removal; passed
 *           to [ExplorerFavoritesRepo.addAt] so undo restores its position.
 */
data class PendingFavoriteRemoval(
    val id: Long,
    val path: APath<*>,
    val displayName: CaString,
    val originalIndex: Int,
)
