package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.core.favorites.FavoriteFeedback
import eu.darken.butler.explorer.core.favorites.FavoriteItem
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
 * Feedback machinery for favorite mutations: each add/remove raises a transient bar so the user
 * sees what happened, offering undo for removals and "show me" for additions.
 *
 * Latest-wins: a new mutation supersedes any pending feedback. The id field guards against
 * stale-timer clobbering. The atomic [ExplorerFavoritesRepo.removeAllForUndo] captures the
 * original indices inside a single DataStore transaction so position restoration via
 * [onFeedbackAction] is race-free.
 */
class ExplorerFavoritesController(
    private val favoritesRepo: ExplorerFavoritesRepo,
    private val scope: CoroutineScope,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val isPickerActive: () -> Boolean,
    private val revealFavorite: suspend (APath<*>) -> Unit,
    private val tag: String,
) {

    private val feedbackFlow = MutableStateFlow<FavoriteFeedback?>(null)
    val feedback: StateFlow<FavoriteFeedback?> = feedbackFlow
    private var timeoutJob: Job? = null
    private var timeoutFeedbackId: Long? = null
    private val feedbackIdGen = AtomicLong(0L)

    /** Remove a favorite via the Home X button, queueing an undo prompt for [FEEDBACK_TIMEOUT]. */
    fun removeFromHome(fav: FavoriteItem) = doLaunch {
        log(tag) { "removeFromHome($fav)" }
        if (suppressedByPicker("removeFromHome")) return@doLaunch
        val displayName = when (val s = fav.state) {
            is FavoriteItem.State.Available -> s.item.displayName
            else -> fav.path.userReadableName
        }
        removeAndPublish(listOf(fav.path), displayName)
    }

    /** Add [paths] to the favorites, confirming what actually landed in storage. */
    fun addAll(paths: List<APath<*>>) = doLaunch {
        log(tag) { "addAll(${paths.map { it.path }})" }
        if (suppressedByPicker("addAll")) return@doLaunch
        supersedePending()

        val added = favoritesRepo.addAll(paths)
        if (added.isEmpty()) {
            log(tag) { "addAll: every path was already a favorite, nothing to confirm" }
            // The superseded feedback's timeout was just cancelled — without a fresh timeout the
            // bar would stay visible forever. Clear it explicitly.
            feedbackFlow.value = null
            return@doLaunch
        }

        publish { id ->
            FavoriteFeedback.Added(
                id = id,
                displayName = added.first().userReadableName,
                paths = added,
            )
        }
    }

    /** Remove [paths] from the favorites, queueing an undo prompt for [FEEDBACK_TIMEOUT]. */
    fun removeAll(paths: List<APath<*>>) = doLaunch {
        log(tag) { "removeAll(${paths.map { it.path }})" }
        if (suppressedByPicker("removeAll")) return@doLaunch
        removeAndPublish(paths, displayName = null)
    }

    /** Toggle the currently-viewed folder's favorite state, confirming the resulting direction. */
    fun toggleCurrent(path: APath<*>) = doLaunch {
        log(tag) { "toggleCurrent(${path.path})" }
        if (suppressedByPicker("toggleCurrent")) return@doLaunch
        supersedePending()

        when (val result = favoritesRepo.toggle(path)) {
            is ExplorerFavoritesRepo.ToggleResult.Added -> publish { id ->
                FavoriteFeedback.Added(
                    id = id,
                    displayName = result.path.userReadableName,
                    paths = listOf(result.path),
                )
            }
            is ExplorerFavoritesRepo.ToggleResult.Removed -> publish { id ->
                FavoriteFeedback.Removed(
                    id = id,
                    displayName = result.entry.path.userReadableName,
                    removed = listOf(result.entry),
                )
            }
        }
    }

    /** Act on the current feedback: restore the removed favorites, or reveal the added one. */
    fun onFeedbackAction() = doLaunch {
        when (val current = feedbackFlow.value) {
            null -> return@doLaunch
            is FavoriteFeedback.Removed -> {
                log(tag) { "onFeedbackAction(): undo ${current.removed.map { it.path.path }}" }
                try {
                    favoritesRepo.addAllAt(current.removed)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to restore favorites: ${e.asLog()}" }
                    // fall through and clear regardless — leaving the bar visible without a
                    // restored entry is worse than the user discovering the failure via no-bar.
                }
                // Cancel the timeout AFTER the restore attempt so we don't leak a stuck bar if it
                // throws partway. Id-checked clear avoids clobbering newer feedback.
                cancelTimeout(onlyFor = current.id)
                clearIfCurrent(current.id)
            }
            is FavoriteFeedback.Added -> {
                log(tag) { "onFeedbackAction(): reveal ${current.paths.first().path}" }
                cancelTimeout(onlyFor = current.id)
                clearIfCurrent(current.id)
                revealFavorite(current.paths.first())
            }
        }
    }

    /**
     * Drop any pending feedback without acting on it. Called when the picker becomes active:
     * the bar is hidden there, and without this a stale bar would resurface when returning to
     * non-picker mode within the feedback window.
     */
    fun clearFeedback() {
        if (feedbackFlow.value == null) return
        supersedePending()
        feedbackFlow.value = null
    }

    private suspend fun removeAndPublish(paths: List<APath<*>>, displayName: CaString?) {
        supersedePending()

        val removed = favoritesRepo.removeAllForUndo(paths)
        if (removed.isEmpty()) {
            log(tag) { "remove: no path was present, nothing to undo" }
            // See addAll(): the superseded timeout is gone, so clear explicitly.
            feedbackFlow.value = null
            return
        }

        publish { id ->
            FavoriteFeedback.Removed(
                id = id,
                displayName = displayName ?: removed.first().path.userReadableName,
                removed = removed,
            )
        }
    }

    /** Favorites are neither shown nor mutable while a picker is active. */
    private fun suppressedByPicker(caller: String): Boolean {
        if (!isPickerActive()) return false
        log(tag, WARN) { "$caller called in picker mode; ignoring" }
        return true
    }

    /** Latest-wins: the previous feedback's action window ends here. */
    private fun supersedePending() = cancelTimeout()

    /**
     * @param onlyFor when set, keeps the timer unless it belongs to that feedback. Acting on a bar
     *        involves a suspending storage write, during which newer feedback may already have
     *        started its own window — cancelling that one would leave its bar up forever.
     */
    private fun cancelTimeout(onlyFor: Long? = null) {
        if (onlyFor != null && timeoutFeedbackId != onlyFor) return
        timeoutJob?.cancel()
        timeoutJob = null
        timeoutFeedbackId = null
    }

    private fun publish(build: (Long) -> FavoriteFeedback) {
        val id = feedbackIdGen.incrementAndGet()
        feedbackFlow.value = build(id)
        timeoutFeedbackId = id
        timeoutJob = scope.launch {
            delay(FEEDBACK_TIMEOUT)
            clearIfCurrent(id)
        }
    }

    /** Only clears while [id] is still the active feedback; newer feedback must survive. */
    private fun clearIfCurrent(id: Long) {
        feedbackFlow.update { current -> if (current?.id == id) null else current }
    }

    companion object {
        /** Window during which a favorite mutation can still be acted on. */
        internal val FEEDBACK_TIMEOUT = 5.seconds
    }
}
