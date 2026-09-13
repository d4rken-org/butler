package eu.darken.butler.explorer.core.favorites

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the user-curated list of favorites and resolves them against the gateway.
 *
 * - Entries are persisted via [ExplorerSettings.favoriteEntries], each carrying the path and the
 *   optional name the user gave it. A list stored before labels existed still lives in
 *   [ExplorerSettings.favoritePaths] and is coalesced in until the first mutation rewrites it.
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

    /** Hot in-memory cache of the stored entries, with the pre-label list as the fallback. */
    val entries: StateFlow<List<FavoriteEntry>> = combine(
        settings.favoriteEntries.flow,
        settings.favoritePaths.flow,
    ) { entries, legacyPaths ->
        entries ?: legacyPaths.map { FavoriteEntry(it) }
    }.stateIn(appScope, SharingStarted.Eagerly, emptyList())

    /** Backs synchronous [isFavorite]. */
    val favoritePaths: StateFlow<List<APath<*>>> = entries
        .map { list -> list.map { it.path } }
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    /**
     * Resolved view of the favorites list. Emits placeholder [FavoriteItem.State.Resolving]
     * entries first, then the fully resolved list. Stale resolutions are cancelled when
     * entries change or [refresh] is called.
     */
    val favorites: StateFlow<List<FavoriteItem>> = combine(
        entries,
        refreshTrigger,
    ) { current, _ -> current }
        .flatMapLatest { current ->
            flow {
                emit(current.map { FavoriteItem(it.path, FavoriteItem.State.Resolving, it.label) })
                val resolved = supervisorScope {
                    current.map { entry ->
                        async(dispatcherProvider.IO) { resolveOne(entry) }
                    }.awaitAll()
                }
                emit(resolved)
            }
        }
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private suspend fun resolveOne(entry: FavoriteEntry): FavoriteItem = try {
        val lookup = gatewaySwitch.lookup(entry.path, LookupOptions.BASE)
        val classified = classifier.classify(lookup)
        FavoriteItem(entry.path, FavoriteItem.State.Available(classified), entry.label)
    } catch (e: CancellationException) {
        // Re-throw cancellation so flatMapLatest's stale-resolve cancellation works correctly.
        throw e
    } catch (e: ReadException) {
        // Use path.name (not path.path) to avoid leaking sensitive folder names in logs.
        log(TAG, WARN) { "Favorite path unavailable: ${entry.path.name} - ${e.asLog()}" }
        FavoriteItem(entry.path, FavoriteItem.State.Unavailable(e), entry.label)
    }

    /**
     * Single write path for the entry list.
     *
     * The DataStore update transform only sees its own key, so the pre-label list is
     * necessarily read before the transaction. That is safe because nothing writes
     * `explorer.favorites.paths` any more — it is read-only from here on. Reading it straight
     * from settings rather than from the [favoritePaths] cache is mandatory: that cache starts
     * empty and is itself derived from the entries, so a first mutation before it has emitted
     * would persist only the new entry and drop every pre-upgrade favorite for good.
     */
    private suspend fun mutate(transform: (List<FavoriteEntry>) -> List<FavoriteEntry>) {
        val legacyPaths = settings.favoritePaths.value()
        settings.favoriteEntries.update { current ->
            transform(current ?: legacyPaths.map { FavoriteEntry(it) })
        }
    }

    fun isFavorite(path: APath<*>): Boolean = favoritePaths.value.any { it.matches(path) }

    suspend fun add(path: APath<*>) = addAll(listOf(path))

    /** @return the paths that were actually added; entries already favorited are skipped. */
    suspend fun addAll(paths: List<APath<*>>): List<APath<*>> {
        var added = emptyList<APath<*>>()
        mutate { current ->
            // Dedupe against existing storage AND against earlier entries in this batch.
            val deduped = paths.fold(emptyList<APath<*>>()) { acc, incoming ->
                if (current.any { it.path.matches(incoming) } || acc.any { it.matches(incoming) }) acc
                else acc + incoming
            }
            added = deduped
            current + deduped.map { FavoriteEntry(it) }
        }
        log(TAG, INFO) { "Added ${added.size} favorite(s)." }
        return added
    }

    suspend fun remove(path: APath<*>) = removeAll(listOf(path))

    suspend fun removeAll(paths: List<APath<*>>) {
        mutate { current ->
            current.filterNot { existing -> paths.any { it.matches(existing.path) } }
        }
        log(TAG, INFO) { "Removed up to ${paths.size} favorite(s)." }
    }

    /**
     * Give a favorite its own display name. A blank [label] clears it back to the folder's name,
     * a path that is not favorited is a no-op — this never inserts.
     */
    suspend fun setLabel(path: APath<*>, label: String?) {
        val cleaned = label?.trim()?.takeIf { it.isNotEmpty() }
        var matched = false
        mutate { current ->
            val idx = current.indexOfFirst { it.path.matches(path) }
            // Assigned on every invocation, see removeAllForUndo.
            matched = idx >= 0
            if (idx < 0) {
                current
            } else {
                current.toMutableList().apply { this[idx] = this[idx].copy(label = cleaned) }
            }
        }
        if (matched) {
            log(TAG, INFO) { "Label of ${path.name} is now ${cleaned != null}." }
        } else {
            log(TAG, WARN) { "setLabel: ${path.name} is not a favorite." }
        }
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
        mutate { current ->
            val hits = current
                .mapIndexedNotNull { idx, existing ->
                    if (paths.any { it.matches(existing.path) }) RemovedFavorite(existing, idx) else null
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

    suspend fun addAt(path: APath<*>, index: Int) = addAllAt(listOf(RemovedFavorite(FavoriteEntry(path), index)))

    /**
     * Re-insert [entries] at their captured positions. Used by undo to restore
     * [removeAllForUndo]-removed entries. Insertion runs in ascending index order so each
     * entry lands at its original slot; out-of-range indices are clamped and duplicates
     * (per [APath.matches]) are skipped.
     */
    suspend fun addAllAt(entries: List<RemovedFavorite>) {
        if (entries.isEmpty()) return
        mutate { current ->
            val restored = current.toMutableList()
            entries.sortedBy { it.originalIndex }.forEach { entry ->
                if (restored.none { it.path.matches(entry.path) }) {
                    restored.add(entry.originalIndex.coerceIn(0, restored.size), entry.entry)
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
        mutate { current ->
            val idx = current.indexOfFirst { it.path.matches(path) }
            if (idx >= 0) {
                result = ToggleResult.Removed(RemovedFavorite(current[idx], idx))
                current.toMutableList().apply { removeAt(idx) }
            } else {
                result = ToggleResult.Added(path)
                current + FavoriteEntry(path)
            }
        }
        return result
    }

    /** Re-runs the resolver pass for the existing entries. */
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
     * via [addAllAt] at the original position — label included, so undo puts back what was there.
     */
    data class RemovedFavorite(val entry: FavoriteEntry, val originalIndex: Int) {
        val path: APath<*> get() = entry.path
    }

    companion object {
        private val TAG = logTag("Explorer", "FavoritesRepo")
    }
}
