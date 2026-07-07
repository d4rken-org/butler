package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.core.favorites.FavoriteItem
import eu.darken.butler.explorer.core.favorites.PendingFavoriteRemoval
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Undo machinery for removing Home favorites via the "X" button.
 *
 * Latest-wins: a new removal supersedes any pending undo. The id field guards against
 * stale-timer clobbering. The atomic [ExplorerFavoritesRepo.removeForUndo] captures the
 * original index inside a single DataStore transaction so position restoration via [undo]
 * is race-free.
 */
class ExplorerFavoritesController(
    private val favoritesRepo: ExplorerFavoritesRepo,
    private val scope: CoroutineScope,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val isPickerActive: () -> Boolean,
    private val tag: String,
) {

    private val pendingRemovalFlow = MutableStateFlow<PendingFavoriteRemoval?>(null)
    val pendingRemoval: StateFlow<PendingFavoriteRemoval?> = pendingRemovalFlow
    private var pendingRemovalJob: Job? = null
    private val removalIdGen = AtomicLong(0L)

    /** Remove a favorite via the Home X button, queueing an undo prompt for [UNDO_TIMEOUT]. */
    fun remove(fav: FavoriteItem) = doLaunch {
        log(tag) { "onFavoriteRemove($fav)" }
        // Don't surface undo in picker mode — the favorites section isn't even visible there.
        if (isPickerActive()) {
            log(tag, WARN) { "onFavoriteRemove called in picker mode; ignoring" }
            return@doLaunch
        }
        // Latest-wins: previous pending removal becomes permanent.
        pendingRemovalJob?.cancel()
        pendingRemovalJob = null

        val removed = favoritesRepo.removeForUndo(fav.path) ?: run {
            log(tag) { "onFavoriteRemove: path not present, nothing to undo" }
            // Previous pending removal's timeout was just cancelled — without a fresh
            // timeout the bar would stay visible forever. Clear it explicitly.
            pendingRemovalFlow.value = null
            return@doLaunch
        }

        val displayName = when (val s = fav.state) {
            is FavoriteItem.State.Available -> s.item.displayName
            else -> fav.path.userReadableName
        }
        val id = removalIdGen.incrementAndGet()
        pendingRemovalFlow.value = PendingFavoriteRemoval(
            id = id,
            path = removed.path,
            displayName = displayName,
            originalIndex = removed.originalIndex,
        )

        pendingRemovalJob = scope.launch {
            delay(UNDO_TIMEOUT)
            // Only clear if THIS removal is still pending; a newer removal must not be wiped.
            pendingRemovalFlow.update { current -> if (current?.id == id) null else current }
        }
    }

    /** Restore the last-removed favorite at its original position. */
    fun undo() = doLaunch {
        val pending = pendingRemovalFlow.value ?: return@doLaunch
        log(tag) { "undoFavoriteRemoval(${pending.path.path})" }
        try {
            favoritesRepo.addAt(pending.path, pending.originalIndex)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to restore favorite ${pending.path.path}: ${e.asLog()}" }
            // fall through and clear regardless — leaving the bar visible without a
            // restored entry is worse than the user discovering the failure via no-bar.
        }
        // Cancel the timeout job AFTER the addAt attempt so we don't leak a stuck bar
        // if addAt throws partway. Id-checked clear avoids clobbering a newer removal.
        pendingRemovalJob?.cancel()
        pendingRemovalJob = null
        pendingRemovalFlow.update { current -> if (current?.id == pending.id) null else current }
    }

    /**
     * Finalize any pending removal without undo. Called when the picker becomes active:
     * the undo bar is hidden there, and without this a stale bar would resurface when
     * returning to non-picker mode within the undo window.
     */
    fun finalizePendingRemoval() {
        if (pendingRemovalFlow.value == null) return
        pendingRemovalJob?.cancel()
        pendingRemovalJob = null
        pendingRemovalFlow.value = null
    }

    companion object {
        /** Window during which a Home favorite removal can still be undone. */
        internal val UNDO_TIMEOUT = 5.seconds
    }
}
