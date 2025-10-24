package eu.darken.butler.common.files.metadata

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry of all available [MetadataExtractor] implementations.
 *
 * Engines (SearchEngine, BrowsingEngine) use this to find and invoke
 * appropriate extractors during item creation.
 *
 * Extractors are injected via Dagger's multi-binding (@IntoSet).
 *
 * Includes LRU cache for performance:
 * - Content-aware keys (path + modifiedAt + size) for automatic invalidation
 * - LRU eviction with configurable size limit (default 1000 entries)
 * - Thread-safe for concurrent access from multiple engines
 */
@Singleton
class MetadataRepo @Inject constructor(
    private val extractors: Set<@JvmSuppressWildcards MetadataExtractor<*>>
) {

    private val tag = logTag("IO", "Metadata", "Repo")

    /**
     * Cache key combining path with file attributes for automatic invalidation.
     * When file changes (different modifiedAt/size), cache automatically invalidates.
     */
    private data class CacheKey(
        val path: APath<*>,
        val modifiedAt: Long?,  // Epoch millis, null if unavailable
        val size: Long?,        // Bytes, null if unavailable
    )

    /**
     * LRU cache with automatic eviction when max size exceeded.
     * Thread-safe via synchronized methods.
     */
    private class LruCache<K, V>(
        private val maxSize: Int = 500
    ) : LinkedHashMap<K, V>(16, 0.75f, true) {  // accessOrder=true for LRU

        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            return size > maxSize
        }

        @Synchronized
        fun getSafe(key: K): V? = this[key]

        @Synchronized
        fun putSafe(key: K, value: V): V? = put(key, value)

        @Synchronized
        fun clearSafe() = clear()

        @Synchronized
        fun sizeSafe(): Int = size

        @Synchronized
        fun removeIf(predicate: (K) -> Boolean) {
            val keysToRemove = keys.filter(predicate)
            keysToRemove.forEach { remove(it) }
        }
    }

    private val cache = LruCache<CacheKey, FileMetadata>(maxSize = 1000)

    /**
     * Find the best extractor for a given file.
     *
     * Returns the highest priority extractor that can handle the file.
     *
     * @param lookup The file to find an extractor for
     * @return The best matching extractor, or null if none can handle this file
     */
    fun findExtractor(lookup: APathLookup<*>): MetadataExtractor<*>? = extractors
        .filter { it.canHandle(lookup) }
        .maxByOrNull { it.priority }

    /**
     * Extract metadata for a file with caching.
     *
     * Uses content-aware LRU cache for performance:
     * - Cache hit if same path + modifiedAt + size (file unchanged)
     * - Cache miss if file changed or first extraction
     * - Automatically evicts least recently used entries when cache full
     *
     * @param lookup The file to extract metadata from
     * @return Extracted metadata, or null if unavailable or extraction failed
     */
    suspend fun extract(lookup: APathLookup<*>): FileMetadata? {
        val key = CacheKey(
            path = lookup.lookedUp,
            modifiedAt = lookup.modifiedAt?.toEpochMilliseconds(),
            size = lookup.size
        )

        // Try cache first
        cache.getSafe(key)?.let { cached ->
            log(tag, VERBOSE) { "Cache hit: ${lookup.name}" }
            return cached
        }

        // Cache miss
        log(tag, VERBOSE) { "Cache miss: ${lookup.path} (modifiedAt=${lookup.modifiedAt}, size=${lookup.size})" }

        val extractor = findExtractor(lookup)
        if (extractor == null) {
            log(tag, VERBOSE) { "No extractor for: ${lookup.name}" }
            return null
        }

        log(tag, DEBUG) { "Extracting metadata: ${lookup.name} using ${extractor::class.simpleName}" }

        val result = extractor.extract(lookup)
        val metadata = result.getOrElse { error ->
            log(tag, WARN) { "Extraction failed for ${lookup.path}: ${error.asLog()}" }
            return null
        }

        log(tag, DEBUG) { "Extracted ${metadata::class.simpleName} for: ${lookup.name}" }
        cache.putSafe(key, metadata)

        return metadata
    }

    /**
     * Check if any extractor can handle this file.
     *
     * @param lookup The file to check
     * @return true if at least one extractor can handle this file
     */
    fun canExtract(lookup: APathLookup<*>): Boolean = extractors.any { it.canHandle(lookup) }

    /**
     * Clear all cached metadata.
     *
     * Use when you want to force re-extraction for all files.
     */
    fun clearCache() {
        val sizeBefore = cache.sizeSafe()
        cache.clearSafe()
        log(tag, DEBUG) { "Cache cleared: $sizeBefore entries removed" }
    }

    /**
     * Get current cache size (number of entries).
     *
     * @return Number of cached metadata entries
     */
    fun getCacheSize(): Int = cache.sizeSafe()

    /**
     * Invalidate cache entries for a specific path.
     *
     * Removes all cached metadata for the given path (any modifiedAt/size).
     * Use when you know a specific file changed but don't have updated lookup.
     *
     * @param path The file path to invalidate
     */
    fun invalidate(path: APath<*>) {
        val sizeBefore = cache.sizeSafe()
        cache.removeIf { it.path == path }
        val removed = sizeBefore - cache.sizeSafe()
        log(tag, DEBUG) { "Invalidated path: ${path.path} ($removed entries removed)" }
    }
}
