package eu.darken.butler.editor.core.engine.text

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LRU cache of decoded original-file blocks. Pure cache, never authoritative — edits live in
 * the piece table's add buffer, so eviction is always safe. Loads run under the cache mutex,
 * serializing misses — acceptable because the document buffer serializes all ops behind one
 * mutex anyway; the loader must never re-enter the cache.
 */
class DecodeCache(
    private val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
    private val loader: suspend (blockIndex: Int) -> String,
) {

    init {
        require(maxBlocks >= 1) { "Cache needs at least one block slot" }
    }

    private val mutex = Mutex()
    private val cache = LinkedHashMap<Int, String>(maxBlocks, 0.75f, true)

    suspend fun get(blockIndex: Int): String = mutex.withLock {
        cache[blockIndex]?.let { return@withLock it }
        val text = loader(blockIndex)
        cache[blockIndex] = text
        if (cache.size > maxBlocks) {
            val eldest = cache.keys.first()
            cache.remove(eldest)
        }
        text
    }

    suspend fun clear() = mutex.withLock {
        cache.clear()
    }

    companion object {
        const val DEFAULT_MAX_BLOCKS = 16
    }
}
