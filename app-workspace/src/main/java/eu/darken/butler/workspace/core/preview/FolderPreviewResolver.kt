package eu.darken.butler.workspace.core.preview

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.isFile
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

typealias FolderPreviewObserver = (APath<*>) -> Flow<List<APathLookup<*>>>

/**
 * Resolves which media children represent a directory as a grid tile collage.
 *
 * Resolution runs in the collector's scope (scrolling a tile away cancels its lookup), on the IO
 * dispatcher, and is bounded by [semaphore] so scroll thrash can't fan out unbounded listings
 * against a slow gateway.
 *
 * Staleness is tracked via monotonic stamps: invalidations evict matching cache entries and bump
 * [revision] so active observers re-check. An in-flight resolve that overlaps ANY invalidation is
 * never cached (its guarding stamp records could be LRU-evicted before it completes) — the result
 * is still emitted, and the concurrent [revision] bump makes the observer resolve again.
 */
@Singleton
class FolderPreviewResolver @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    fileSystemHinter: FileSystemHinter,
    @AppScope appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    workspaceSettings: WorkspaceSettings,
) {

    /** [observe] gated by the global folder-preview setting; what workspace pages should use. */
    val settingsGatedObserver: FolderPreviewObserver = { dir ->
        workspaceSettings.showFolderMediaPreviews.flow.flatMapLatest { enabled ->
            if (enabled) observe(dir) else flowOf(emptyList())
        }
    }

    private data class Entry(
        val children: List<APathLookup<*>>,
        val stamp: Long,
    )

    private val lock = Any()
    private var counter = 0L
    private val cache = lruMap<APath<*>, Entry>(MAX_CACHED_DIRS)
    private val invalidatedDirs = lruMap<APath<*>, Long>(MAX_STAMP_RECORDS)
    private val invalidatedParents = lruMap<APath<*>, Long>(MAX_STAMP_RECORDS)
    private val revision = MutableStateFlow(0L)
    private val semaphore = Semaphore(MAX_CONCURRENT_LOOKUPS)

    init {
        fileSystemHinter.events
            .onEach { onFileSystemEvent(it) }
            .launchIn(appScope)
    }

    fun observe(dir: APath<*>): Flow<List<APathLookup<*>>> = revision
        .map { cachedOrResolve(dir) }
        .distinctUntilChanged()

    /**
     * Marks [dir]'s own selection and the selections of all its direct child directories as stale.
     * Called on every actual directory load, so navigation and manual refresh both produce fresh
     * collages — matching how the child-count badge behaves.
     */
    fun invalidateFor(dir: APath<*>) {
        synchronized(lock) {
            val stamp = ++counter
            invalidatedDirs[dir] = stamp
            invalidatedParents[dir] = stamp
            cache.remove(dir)
            cache.entries.removeAll { it.key.parent == dir }
        }
        revision.update { it + 1 }
    }

    /**
     * Marks the given directories' own selections as stale, e.g. directory search results that
     * should resolve fresh for this search. One revision bump for the whole batch.
     */
    fun invalidateDirs(dirs: Collection<APath<*>>) {
        if (dirs.isEmpty()) return
        synchronized(lock) {
            val stamp = ++counter
            dirs.forEach { dir ->
                invalidatedDirs[dir] = stamp
                cache.remove(dir)
            }
        }
        revision.update { it + 1 }
    }

    private fun onFileSystemEvent(event: FileSystemEvent) {
        val paths = when (event) {
            is FileSystemEvent.Added -> event.paths
            is FileSystemEvent.Removed -> event.paths
            is FileSystemEvent.Modified -> event.paths
        }
        if (paths.isEmpty()) return

        val staleDirs = buildSet {
            paths.forEach { lookup ->
                // The containing directory's selection may have changed
                lookup.lookedUp.parent?.let { add(it) }
                // A moved/removed directory's own selection is void
                if (lookup.isDirectory) add(lookup.lookedUp)
            }
        }

        invalidateDirs(staleDirs)
    }

    private fun stampForLocked(dir: APath<*>): Long = maxOf(
        invalidatedDirs[dir] ?: 0L,
        dir.parent?.let { invalidatedParents[it] } ?: 0L,
    )

    private suspend fun cachedOrResolve(dir: APath<*>): List<APathLookup<*>> {
        val (cached, stamp, startCounter) = synchronized(lock) {
            val current = stampForLocked(dir)
            Triple(cache[dir]?.takeIf { it.stamp >= current }, current, counter)
        }
        if (cached != null) return cached.children

        // Archive entries never render thumbnails (no implicit decompression)
        if (dir is ArchivePath) return emptyList()

        // Debug-only timing (gated on Bugs.isTrace): separates permit-wait from lookup, and logs
        // the raw enumerated entry count vs the capped selection so scroll cost can be attributed.
        val trace = Bugs.isTrace
        val waitStart = if (trace) TimeSource.Monotonic.markNow() else null
        var permitWait: Duration? = null
        var lookupTime: Duration? = null
        var rawCount = -1

        val children = try {
            semaphore.withPermit {
                if (trace) permitWait = waitStart?.elapsedNow()
                val lookupStart = if (trace) TimeSource.Monotonic.markNow() else null
                val result = withContext(dispatcherProvider.IO) {
                    val all = gatewaySwitch.lookupFiles(
                        dir,
                        LookupOptions(
                            continueOnError = true,
                            fallbackToUnknown = true,
                            fetchSize = true,
                            fetchModifiedAt = true,
                        ),
                    )
                    if (trace) rawCount = all.size
                    all.asSequence()
                        .filter { it.isFile }
                        // The preview fetcher renders exactly-0-byte files as generic icons;
                        // unknown (null) sizes may still decode, so only exclude confirmed-empty.
                        .filter { it.size != 0L }
                        .filter { MimeInfo.fromFileName(it.name).let { mime -> mime.isImage || mime.isVideo } }
                        .sortedWith(
                            compareByDescending<APathLookup<*>> { it.modifiedAt ?: Instant.DISTANT_PAST }
                                .thenBy { it.name }
                        )
                        .take(MAX_PREVIEW_CHILDREN)
                        .toList()
                }
                if (trace) lookupTime = lookupStart?.elapsedNow()
                result
            }.also {
                if (trace) {
                    log(TAG, VERBOSE) {
                        "resolve SUCCESS $dir: rawEntries=$rawCount selected=${it.size} " +
                            "permitWait=$permitWait lookup=$lookupTime"
                    }
                }
            }
        } catch (e: CancellationException) {
            if (trace) log(TAG, VERBOSE) { "resolve CANCELED $dir (elapsed=${waitStart?.elapsedNow()})" }
            throw e
        } catch (e: Exception) {
            // Gateways may wrap cancellation (e.g. SAF's ReadException); never cache that as empty
            currentCoroutineContext().ensureActive()
            log(TAG, WARN) { "resolve ERROR $dir: ${e.asLog()}" }
            emptyList()
        }

        synchronized(lock) {
            // If ANY invalidation landed while the lookup was in flight, this result may predate
            // it and the stamp records guarding against that can be LRU-evicted — so don't cache.
            // The observer resolves again anyway because the invalidation bumped [revision].
            if (counter == startCounter) {
                cache[dir] = Entry(children = children, stamp = stamp)
            }
        }
        return children
    }

    private fun <K, V> lruMap(maxSize: Int): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
        }

    companion object {
        private const val MAX_PREVIEW_CHILDREN = 4
        private const val MAX_CACHED_DIRS = 500
        private const val MAX_STAMP_RECORDS = 1024
        private const val MAX_CONCURRENT_LOOKUPS = 4
        private val TAG = logTag("Workspace", "Preview", "FolderResolver")
    }
}
