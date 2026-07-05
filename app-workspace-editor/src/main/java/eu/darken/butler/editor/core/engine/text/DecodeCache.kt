package eu.darken.butler.editor.core.engine.text

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * LRU cache of decoded original-file blocks. Pure cache, never authoritative — edits live in
 * the piece table's add buffer, so eviction is always safe. Concurrent misses for the same
 * block share a single load via an in-flight registry; loads run outside the lock so distinct
 * blocks decode in parallel. A failed or cancelled load deregisters itself and waiters retry
 * (possibly becoming the loader), so transient errors never poison the cache. The loader must
 * never re-enter the cache.
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
    private val inFlight = mutableMapOf<Int, CompletableDeferred<String>>()

    suspend fun get(blockIndex: Int): String {
        while (true) {
            var owned: CompletableDeferred<String>? = null
            val deferred = mutex.withLock {
                cache[blockIndex]?.let { return it }
                inFlight[blockIndex] ?: CompletableDeferred<String>().also {
                    inFlight[blockIndex] = it
                    owned = it
                }
            }

            if (owned == null) {
                try {
                    return deferred.await()
                } catch (e: CancellationException) {
                    // Distinguish our own cancellation from the loading coroutine's
                    coroutineContext.ensureActive()
                    continue
                } catch (e: Exception) {
                    continue
                }
            }

            try {
                val text = loader(blockIndex)
                withContext(NonCancellable) {
                    mutex.withLock {
                        // Skip insertion if clear() ran mid-load; waiters still receive the text
                        if (inFlight[blockIndex] === deferred) {
                            inFlight.remove(blockIndex)
                            cache[blockIndex] = text
                            if (cache.size > maxBlocks) cache.remove(cache.keys.first())
                        }
                    }
                }
                deferred.complete(text)
                return text
            } catch (e: Throwable) {
                // NonCancellable: a cancelled load must still deregister and release its waiters
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlight[blockIndex] === deferred) inFlight.remove(blockIndex)
                    }
                }
                deferred.completeExceptionally(e)
                throw e
            }
        }
    }

    suspend fun clear() = mutex.withLock {
        cache.clear()
        inFlight.clear()
    }

    companion object {
        const val DEFAULT_MAX_BLOCKS = 16
    }
}
