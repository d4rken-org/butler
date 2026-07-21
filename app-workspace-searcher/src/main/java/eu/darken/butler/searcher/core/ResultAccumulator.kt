package eu.darken.butler.searcher.core

import eu.darken.butler.searcher.core.engine.backend.SearchBackend

/**
 * Accumulates streamed backend results into a display list, resolving path-duplicates by source
 * rank: overlapping targets (e.g. a folder target plus a MediaStore target) can surface the same
 * file, and the higher-ranked source (filesystem lookup, fresh metadata) must win regardless of
 * arrival order. Replacements keep the item's list position.
 *
 * Rank preference is best-effort once the result cap cancels the scanners: a replacement that
 * would have arrived after cancellation is simply lost — capped result sets are already an
 * arbitrary truncation.
 *
 * Not thread-safe; the caller collects from a single coroutine.
 */
internal class ResultAccumulator {

    sealed interface Outcome {
        data object Added : Outcome
        data object Replaced : Outcome
        data object Ignored : Outcome
    }

    private class Entry(val index: Int, val sourceRank: Int)

    private val entriesByKey = HashMap<String, Entry>()
    private val items = mutableListOf<SearchItem>()

    /** Number of unique paths accepted so far (replacements don't change it). */
    val uniqueCount: Int get() = items.size

    fun add(result: SearchBackend.BackendResult): Outcome {
        val key = ResultPathKeys.keyOf(result.item.path)
        val existing = entriesByKey[key]
        return when {
            existing == null -> {
                entriesByKey[key] = Entry(items.size, result.sourceRank)
                items.add(result.item)
                Outcome.Added
            }
            result.sourceRank > existing.sourceRank -> {
                items[existing.index] = result.item
                entriesByKey[key] = Entry(existing.index, result.sourceRank)
                Outcome.Replaced
            }
            else -> Outcome.Ignored
        }
    }

    fun removeLast(): SearchItem {
        val removed = items.removeAt(items.size - 1)
        entriesByKey.remove(ResultPathKeys.keyOf(removed.path))
        return removed
    }

    /** Immutable snapshot — always a new list instance so identity-keyed caches invalidate. */
    fun snapshot(limit: Int? = null): List<SearchItem> = when {
        limit != null && items.size > limit -> items.take(limit)
        else -> items.toList()
    }
}
