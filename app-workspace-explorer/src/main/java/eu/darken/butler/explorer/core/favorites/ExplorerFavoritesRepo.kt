package eu.darken.butler.explorer.core.favorites

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.FileTypeClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the user-curated list of favorite paths and resolves them against the gateway.
 *
 * - The raw path list is persisted via [ExplorerSettings.favoritePaths].
 * - [favorites] exposes the same list resolved into [FavoriteItem]s with
 *   `Resolving` / `Available` / `Unavailable` state.
 * - [favoritePaths] is the hot in-memory cache used by [isFavorite] for synchronous
 *   reads from action providers (which run synchronously and must not hit DataStore).
 * - Mutating operations are atomic via the DataStore update lambda — [toggle] reads
 *   the stored list inside the transaction so a stale [isFavorite] boolean cannot
 *   cause an "add when removing" race.
 */
@Singleton
class ExplorerFavoritesRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val settings: ExplorerSettings,
    private val gatewaySwitch: GatewaySwitch,
) {

    private val classifier = FileTypeClassifier()

    /** Hot in-memory cache of raw favorite paths. Backs synchronous [isFavorite]. */
    val favoritePaths: StateFlow<List<APath<*>>> = settings.favoritePaths.flow
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    /**
     * Resolved view of the favorites list. Emits placeholder [FavoriteItem.State.Resolving]
     * entries first, then the fully resolved list. Stale resolutions are cancelled when
     * paths change or [refresh] is called.
     */
    val favorites: StateFlow<List<FavoriteItem>> = combine(
        favoritePaths,
        refreshTrigger,
    ) { paths, _ -> paths }
        .flatMapLatest { paths ->
            flow {
                emit(paths.map { FavoriteItem(it, FavoriteItem.State.Resolving) })
                val resolved = supervisorScope {
                    paths.map { path ->
                        async(dispatcherProvider.IO) { resolveOne(path) }
                    }.awaitAll()
                }
                emit(resolved)
            }
        }
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private suspend fun resolveOne(path: APath<*>): FavoriteItem = try {
        val lookup = gatewaySwitch.lookup(path, LookupOptions.BASE)
        val classified = classifier.classify(lookup)
        FavoriteItem(path, FavoriteItem.State.Available(classified))
    } catch (e: CancellationException) {
        // Re-throw cancellation so flatMapLatest's stale-resolve cancellation works correctly.
        throw e
    } catch (e: ReadException) {
        // Use path.name (not path.path) to avoid leaking sensitive folder names in logs.
        log(TAG, WARN) { "Favorite path unavailable: ${path.name} - ${e.asLog()}" }
        FavoriteItem(path, FavoriteItem.State.Unavailable(e))
    }

    fun isFavorite(path: APath<*>): Boolean = favoritePaths.value.any { it.matches(path) }

    suspend fun add(path: APath<*>) = addAll(listOf(path))

    /** @return the paths that were actually added; entries already favorited are skipped. */
    suspend fun addAll(paths: List<APath<*>>): List<APath<*>> {
        var added = emptyList<APath<*>>()
        settings.favoritePaths.update { current ->
            // Dedupe against existing storage AND against earlier entries in this batch.
            val deduped = paths.fold(emptyList<APath<*>>()) { acc, incoming ->
                if (current.any { it.matches(incoming) } || acc.any { it.matches(incoming) }) acc
                else acc + incoming
            }
            added = deduped
            current + deduped
        }
        log(TAG, INFO) { "Added ${added.size} favorite(s)." }
        return added
    }

    suspend fun remove(path: APath<*>) = removeAll(listOf(path))

    suspend fun removeAll(paths: List<APath<*>>) {
        settings.favoritePaths.update { current ->
            current.filterNot { existing -> paths.any { it.matches(existing) } }
        }
        log(TAG, INFO) { "Removed up to ${paths.size} favorite(s)." }
    }

    suspend fun removeForUndo(path: APath<*>): RemovedFavorite? = removeAllForUndo(listOf(path)).firstOrNull()

    /**
     * Atomically remove [paths] AND return each removed entry's original index for undo.
     * The capture-and-remove happens inside a single DataStore update, so the indices
     * cannot be invalidated by a concurrent mutation.
     *
     * @return the removed entries in ascending original-index order; empty if nothing matched.
     */
    suspend fun removeAllForUndo(paths: List<APath<*>>): List<RemovedFavorite> {
        var removed = emptyList<RemovedFavorite>()
        settings.favoritePaths.update { current ->
            val hits = current
                .mapIndexedNotNull { idx, existing ->
                    if (paths.any { it.matches(existing) }) RemovedFavorite(existing, idx) else null
                }
            // Assigned on every invocation: the transform may run more than once, and only the
            // committed run's captures may survive as the undo payload.
            removed = hits
            if (hits.isEmpty()) {
                current
            } else {
                current.filterIndexed { idx, _ -> hits.none { it.originalIndex == idx } }
            }
        }
        if (removed.isNotEmpty()) {
            log(TAG, INFO) { "Removed ${removed.size} favorite(s) for undo at ${removed.map { it.originalIndex }}." }
        }
        return removed
    }

    suspend fun addAt(path: APath<*>, index: Int) = addAllAt(listOf(RemovedFavorite(path, index)))

    /**
     * Re-insert [entries] at their captured positions. Used by undo to restore
     * [removeAllForUndo]-removed entries. Insertion runs in ascending index order so each
     * entry lands at its original slot; out-of-range indices are clamped and duplicates
     * (per [APath.matches]) are skipped.
     */
    suspend fun addAllAt(entries: List<RemovedFavorite>) {
        if (entries.isEmpty()) return
        settings.favoritePaths.update { current ->
            val restored = current.toMutableList()
            entries.sortedBy { it.originalIndex }.forEach { entry ->
                if (restored.none { it.matches(entry.path) }) {
                    restored.add(entry.originalIndex.coerceIn(0, restored.size), entry.path)
                }
            }
            restored
        }
        log(TAG, INFO) { "Restored ${entries.size} favorite(s) at ${entries.map { it.originalIndex }}." }
    }

    /**
     * Atomically toggle a path's favorite state. Reads the current list inside the
     * DataStore update so the result reflects committed storage, not a UI snapshot.
     * A removal carries its original index so the caller can offer undo.
     */
    suspend fun toggle(path: APath<*>): ToggleResult {
        var result: ToggleResult = ToggleResult.Added(path)
        settings.favoritePaths.update { current ->
            val idx = current.indexOfFirst { it.matches(path) }
            if (idx >= 0) {
                result = ToggleResult.Removed(RemovedFavorite(current[idx], idx))
                current.toMutableList().apply { removeAt(idx) }
            } else {
                result = ToggleResult.Added(path)
                current + path
            }
        }
        return result
    }

    /** Re-runs the resolver pass for the existing path list. */
    suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.emit(Unit)
    }

    sealed interface ToggleResult {
        data class Added(val path: APath<*>) : ToggleResult
        data class Removed(val entry: RemovedFavorite) : ToggleResult
    }

    /**
     * Capture of a favorite that was removed via [removeAllForUndo], suitable for restoring
     * via [addAllAt] at the original position.
     */
    data class RemovedFavorite(val path: APath<*>, val originalIndex: Int)

    companion object {
        private val TAG = logTag("Explorer", "FavoritesRepo")
    }
}
