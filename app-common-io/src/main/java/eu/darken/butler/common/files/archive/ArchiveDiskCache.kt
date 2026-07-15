package eu.darken.butler.common.files.archive

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk LRU cache for materialized archive data: whole containers (when random access over a
 * gateway isn't possible, e.g. non-seekable SAF providers or zip4j decryption) and single entries
 * (when a seekable [okio.FileHandle] is required, e.g. Editor/preview on compressed entries).
 *
 * Files are produced atomically (temp `.part` + rename) under a per-key lock. Eviction unlinks by
 * oldest access time; POSIX semantics keep already-open handles on evicted files valid.
 */
@Singleton
class ArchiveDiskCache @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val cacheDir by lazy {
        File(context.cacheDir, CACHE_DIRNAME).apply {
            if (!mkdirs() && !exists()) log(TAG, WARN) { "Failed to create cache dir $this" }
        }
    }
    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    private val evictionMutex = Mutex()

    // Single, memoized sweep of crash-partials and any decrypted plaintext left by a previous session.
    // Every materialize() awaits it, so a cached file can never be read back before the sweep has
    // actually finished deleting stale decrypted content (which would otherwise be served without the
    // password being re-entered). Started eagerly at construction so it runs even if no read follows.
    private val sweepOnce: Deferred<Unit> = appScope.async(
        dispatcherProvider.IO,
        start = CoroutineStart.LAZY,
    ) {
        sweepStaleFiles()
    }

    init {
        sweepOnce.start()
    }

    /**
     * Returns the cached file for [key], producing it via [producer] on miss.
     * [keyPrefix] groups related content for targeted purges (e.g. decrypted data).
     */
    suspend fun materialize(
        keyPrefix: String,
        key: String,
        producer: suspend (File) -> Unit,
    ): File = withContext(dispatcherProvider.IO) {
        // Barrier: block until the startup sweep has finished removing stale decrypted plaintext, so a
        // leftover file from a previous session can never be returned by the fast path below.
        sweepOnce.await()
        val fileName = "$keyPrefix-${key.sha256()}"
        val target = File(cacheDir, fileName)
        keyLocks.getOrPut(fileName) { Mutex() }.withLock {
            if (target.exists() && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            val part = File(cacheDir, "$fileName$PART_SUFFIX")
            try {
                producer(part)
                if (!part.renameTo(target)) throw java.io.IOException("Failed to commit $part")
                target
            } finally {
                withContext(NonCancellable + dispatcherProvider.IO) {
                    if (part.exists() && !part.delete()) log(TAG, WARN) { "Failed to delete partial $part" }
                }
            }
        }.also { evictIfOverCap(keep = it) }
    }

    private suspend fun evictIfOverCap(keep: File) = evictionMutex.withLock {
        try {
            val files = cacheDir.listFiles()?.filter { !it.name.endsWith(PART_SUFFIX) } ?: return@withLock
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return@withLock
            val now = System.currentTimeMillis()
            files.sortedBy { it.lastModified() }.forEach { candidate ->
                if (total <= MAX_CACHE_BYTES) return@withLock
                if (candidate == keep) return@forEach
                // Don't evict a file another materialize just committed but whose caller hasn't opened yet.
                if (now - candidate.lastModified() < EVICT_GRACE_MS) return@forEach
                val size = candidate.length()
                if (candidate.delete()) {
                    total -= size
                    log(TAG) { "Evicted ${candidate.name} ($size bytes)" }
                }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Cache eviction failed: ${e.asLog()}" }
        }
    }

    private fun sweepStaleFiles() {
        try {
            cacheDir.listFiles()
                // Partial writes from a crashed run, AND any decrypted plaintext left from a previous
                // session - decrypted archive content must never survive a process restart (it would be
                // readable without re-entering the password).
                ?.filter { it.name.endsWith(PART_SUFFIX) || it.name.startsWith("$PREFIX_EPHEMERAL_DECRYPTED-") }
                ?.forEach { if (!it.delete()) log(TAG, WARN) { "Failed to delete stale ${it.name}" } }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to sweep stale files: ${e.asLog()}" }
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val TAG = logTag("Gateway", "Archive", "DiskCache")
        private const val CACHE_DIRNAME = "archives"
        private const val PART_SUFFIX = ".part"
        private const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        private const val EVICT_GRACE_MS = 30_000L

        /**
         * Key prefix for decrypted archive-entry plaintext. Swept on startup so it never survives a
         * process restart. [ArchiveService] must use this prefix for decrypted materializations.
         */
        const val PREFIX_EPHEMERAL_DECRYPTED = "entrydec"
    }
}
