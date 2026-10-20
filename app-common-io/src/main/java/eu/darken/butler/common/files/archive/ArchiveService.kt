package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.ArchivePath

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.COPY_BUFFER_SIZE
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.MAX_ENTRIES
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Central archive backend. Owns entry indexing (index cache, single-flight builds, stat
 * fingerprints and write generations), password handling, entry materialization, write-entry
 * validation and output commit locks. [ArchiveGateway] is a thin adapter over this; Explorer
 * operations orchestrate it. Reading, writing and password checks for a concrete format are
 * delegated to its [ArchiveHandler]: [ZipArchiveHandler], [TarArchiveHandler],
 * [SevenZArchiveHandler]. No commons-compress/zip4j types leak out of this package.
 */
@Singleton
class ArchiveService @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val gatewaySwitchLazy: dagger.Lazy<GatewaySwitch>,
    private val diskCache: ArchiveDiskCache,
    private val passwordStore: ArchivePasswordStore,
) {

    private val gatewaySwitch: GatewaySwitch get() = gatewaySwitchLazy.get()

    private val handlerContext = ArchiveHandlerContext(gatewaySwitchLazy, passwordStore)
    private val tarHandler = TarArchiveHandler(handlerContext)
    private val sevenZHandler = SevenZArchiveHandler(handlerContext)
    private val zipHandler = ZipArchiveHandler(handlerContext)

    private fun handlerFor(format: ArchiveFormat): ArchiveHandler = when (format) {
        ArchiveFormat.ZIP -> zipHandler
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2 -> tarHandler
        ArchiveFormat.SEVEN_Z -> sevenZHandler
    }

    private val cacheMutex = Mutex()
    private val indexCache = object : LinkedHashMap<APath<*>, ArchiveIndex>(MAX_CACHED_INDEXES, 0.75f, true) {}
    private val buildLocks = ConcurrentHashMap<APath<*>, Mutex>()

    // Per-container write generation folded into the stat fingerprint. Bumped by [invalidate] so a
    // same-size/coarse-mtime overwrite yields a fresh fingerprint, forcing index + disk-cache
    // (container and per-entry) keys to change instead of serving stale content.
    private val generations = ConcurrentHashMap<APath<*>, Int>()

    // Serializes the read-modify-write commit of a compressed archive per output path, so two
    // operations targeting the same name can't interleave delete/move/seed. Striped (fixed size) so
    // the map can't grow without bound over a long-lived process; unrelated paths may share a stripe.
    private val commitLockStripes = Array(COMMIT_LOCK_STRIPES) { Mutex() }

    fun detectFormat(container: APath<*>): ArchiveFormat? = ArchiveFormat.fromFileName(container.name)

    /**
     * Returns the (cached) entry index for [container], rebuilding when the container's
     * stat fingerprint changed. Single-flight per container.
     */
    suspend fun index(container: APath<*>): ArchiveIndex {
        val format = requireSupported(container)
        val stat = statContainer(container)
        cacheMutex.withLock { indexCache[container] }
            ?.takeIf { it.isServable(stat) }
            ?.let { return it }

        return buildLocks.getOrPut(container) { Mutex() }.withLock {
            cacheMutex.withLock { indexCache[container] }
                ?.takeIf { it.isServable(stat) }
                ?.let { return@withLock it }

            var built = buildIndex(container, format, stat)
            // The container may have changed while we were scanning it.
            val after = statContainer(container)
            if (after.fingerprint != stat.fingerprint) {
                log(TAG, WARN) { "index($container): changed during scan, rebuilding" }
                built = buildIndex(container, format, after)
            }
            cacheMutex.withLock {
                indexCache[container] = built
                while (indexCache.size > MAX_CACHED_INDEXES) {
                    indexCache.remove(indexCache.keys.first())
                }
            }
            built
        }
    }

    /** A listing that only a password could produce is served only while that same password is cached. */
    private fun ArchiveIndex.isServable(stat: ContainerStat): Boolean =
        fingerprint == stat.fingerprint &&
            (!isHeaderEncrypted || (passwordGeneration == passwordStore.generation(container) && hasPassword(container)))

    private fun hasPassword(container: APath<*>): Boolean =
        passwordStore.get(container)?.also { it.fill(Char.MIN_VALUE) } != null

    /**
     * Drops cached state for [container] after it was (over)written. Needed because a replaced
     * archive with an unchanged stat fingerprint (equal size, coarse/null mtime) would otherwise
     * keep serving the old index and the old materialized container from cache.
     */
    suspend fun invalidate(container: APath<*>) {
        // Bump the generation so every future index/container/entry cache key for this container
        // differs from the pre-overwrite keys; stale disk entries are simply never served again
        // (and reclaimed by the disk cache's LRU). Also drop the in-memory index eagerly.
        generations.merge(container, 1) { current, delta -> current + delta }
        cacheMutex.withLock { indexCache.remove(container) }
    }

    /**
     * Serializes commit of an archive at [output] against other compress operations targeting the
     * same path, so their delete/move/seed steps can't interleave.
     */
    suspend fun withOutputCommitLock(output: APath<*>, block: suspend () -> Unit) {
        val stripe = commitLockStripes[(output.path.hashCode() and Int.MAX_VALUE) % COMMIT_LOCK_STRIPES]
        stripe.withLock { block() }
    }

    /**
     * Strict existence of the container itself, for callers that have to tell a deleted archive
     * apart from an unreadable one. Routed through here because [ArchiveGateway] only holds this
     * service - the gateway switch is behind a Lazy to break the cycle between the two.
     */
    suspend fun containerExistsStrict(container: APath<*>): Existence = gatewaySwitch.existsStrict(container)

    /** Container stat used both as cache fingerprint and for archive-root lookups. */
    suspend fun statContainer(container: APath<*>): ContainerStat {
        val lookup = gatewaySwitch.lookup(container, LookupOptions(fetchSize = true, fetchModifiedAt = true))
        return ContainerStat(size = lookup.size, modifiedAt = lookup.modifiedAt, generation = generations[container] ?: 0)
    }

    /**
     * Returns the index after re-verifying the container hasn't changed since it was cached. Used
     * right before entry reads so reported metadata (size/date) matches the bytes about to be read,
     * even if the container was replaced between browsing and reading.
     */
    private suspend fun freshIndex(container: APath<*>): ArchiveIndex {
        val idx = index(container)
        val recheck = statContainer(container)
        return if (recheck.fingerprint != idx.fingerprint) index(container) else idx
    }

    /**
     * Opens a streaming read for a single entry. The returned stream owns all underlying
     * resources (channel/zip handle/decompressor chain) and releases them on close.
     */
    suspend fun openEntryStream(path: ArchivePath): InputStream = withContext(dispatcherProvider.IO) {
        val index = freshIndex(path.container)
        val meta = index.entriesBySegments[path.segments]
            ?: throw ReadException("Entry not found in archive", path)
        when {
            meta.isDirectory -> throw ReadException("Entry is a directory", path)
            meta.isSymlink -> throw ReadException("Symlink entries cannot be read", path)
        }
        handlerFor(index.format).openEntry(path.container, index, meta)
    }

    /**
     * Streams multiple entries with shared handles: one zip open (plus one zip4j open if
     * encrypted entries are requested), or a SINGLE sequential pass for tar-family formats and 7z.
     * Tar and 7z entries are delivered in archive order, not request order. The streams passed to
     * [action] are only valid inside that invocation.
     */
    suspend fun useEntryStreams(
        container: APath<*>,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    ) = withContext(dispatcherProvider.IO) {
        val index = freshIndex(container)
        val files = entries.filter { !it.isDirectory && !it.isSymlink }
        handlerFor(index.format).useEntryStreams(container, index, files, action)
    }

    /**
     * Materializes a single entry to a local scratch file (for seekable access like Editor/preview).
     * Decrypted content is cached under a purgeable prefix.
     */
    suspend fun materializeEntry(path: ArchivePath): File {
        val index = freshIndex(path.container)
        val meta = index.entriesBySegments[path.segments] ?: throw ReadException("Entry not found", path)
        // A cache hit must not hand out plaintext the current credentials could not decrypt.
        if (meta.isEncrypted && !hasPassword(path.container)) throw ArchivePasswordRequiredException(path.container)
        // A format that probes encryption per archive may decrypt entries it did not flag, so any of its
        // reads with a cached password may be decrypted content.
        val decrypted = meta.isEncrypted ||
            (handlerFor(index.format).mayDecryptUnflaggedEntries && hasPassword(path.container))
        val prefix = if (decrypted) PREFIX_ENTRY_DECRYPTED else PREFIX_ENTRY
        val credential = if (decrypted) ":g${passwordStore.generation(path.container)}" else ""
        val key = "${path.container}:${index.fingerprint}:${path.segments.joinToString("/")}$credential"
        return diskCache.materialize(prefix, key) { part ->
            openEntryStream(path).use { input -> part.copyFromStream(input) }
        }
    }

    /**
     * Sequential whole-archive extraction for forward-only backends (zip only - tar formats go
     * through the regular index/stream path, which is already sequential). Streams the container
     * once, delivering every safe file entry in archive order.
     *
     * - [processedOrdinals]: entry ordinals already handled in a previous pass (password restart);
     *   they are not delivered again.
     * - [expectedFingerprint]: fingerprint from the first pass; a container swapped between
     *   passes aborts instead of mixing content from different archive versions.
     * - [onContainerProgress]: raw container bytes read so far, against the container size if known.
     * - A missing password surfaces as [ArchivePasswordRequiredException]; a wrong cached password
     *   is evicted and surfaces with attemptFailed=true. A pass that cannot continue safely aborts
     *   with [SequentialAbortException].
     */
    suspend fun extractZipSequential(
        container: APath<*>,
        processedOrdinals: Set<Int> = emptySet(),
        expectedFingerprint: String? = null,
        onContainerProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        action: suspend (SequentialEntry, InputStream) -> SequentialOutcome,
    ): SequentialResult = withContext(dispatcherProvider.IO) {
        val extractor = handlerFor(requireSupported(container)).sequentialExtractor
            ?: throw ReadException("Sequential extraction only supports zip archives", container)
        val stat = statContainer(container)
        if (expectedFingerprint != null && stat.fingerprint != expectedFingerprint) {
            throw SequentialAbortException("Archive changed between extraction passes", container, 0, 0)
        }
        extractor.extract(container, stat, processedOrdinals, onContainerProgress, action)
    }

    /**
     * True if the archive holds encrypted entries and no currently-cached password decrypts them.
     * Used by operations to prompt before streaming rather than mid-stream.
     */
    suspend fun requiresPassword(container: APath<*>): Boolean = withContext(dispatcherProvider.IO) {
        val index = index(container)
        if (!index.isEncrypted) return@withContext false
        var required: Boolean? = null
        while (required == null) {
            val snapshot = passwordStore.snapshot(container)
            val cached = snapshot.password ?: return@withContext true
            try {
                required = when {
                    verifyPassword(container, cached) -> false
                    passwordStore.evictIfGeneration(container, snapshot.generation) -> true
                    // A password set while this one was being checked gets its own check.
                    else -> null
                }
            } finally {
                cached.fill(Char.MIN_VALUE)
            }
        }
        required
    }

    /**
     * Checks [password] against the archive without caching it. True if entry content decrypts.
     */
    suspend fun verifyPassword(container: APath<*>, password: CharArray): Boolean =
        withContext(dispatcherProvider.IO) {
            handlerFor(requireSupported(container)).verifyPassword(container, password) { index(container) }
        }

    /**
     * One file to write into a new archive.
     *
     * @param openStream When set, the entry's bytes come from this instead of [source] read through
     * the gateway, for content the gateway cannot reach (e.g. a ContentResolver URI). [source] then
     * only names the entry in errors.
     */
    data class WriteEntry(
        val name: String,
        val source: APath<*>,
        val isDirectory: Boolean,
        val size: Long?,
        val openStream: (suspend () -> InputStream)? = null,
    )

    /**
     * Writes [entries] into a new archive at [destination] per [options]. Sources are read through
     * the gateway (any backend) unless an entry brings its own stream. [onEntry] reports bytes as
     * each file completes.
     *
     * Entry names and count are validated against the same policy [index] enforces on read, so a
     * created archive is never one this service refuses or silently reinterprets.
     */
    suspend fun compress(
        options: ArchiveWriteOptions,
        destination: APath<*>,
        entries: List<WriteEntry>,
        onEntry: suspend (WriteEntry, Long) -> Unit,
    ) = withContext(dispatcherProvider.IO) {
        validateWriteEntries(entries, destination)
        // ArchiveWriteOptions refuses read-only formats, so no output is ever opened for one.
        val writer = handlerFor(options.format).writer
            ?: throw IllegalArgumentException("${options.format} archives cannot be created")
        gatewaySwitch.openOutputStream(destination).use { rawOut ->
            writer.write(rawOut, options, entries, onEntry)
        }
    }

    private fun validateWriteEntries(entries: List<WriteEntry>, destination: APath<*>) {
        if (entries.size > MAX_ENTRIES) {
            throw WriteException("Too many entries (${entries.size}, limit $MAX_ENTRIES)", destination)
        }
        entries.forEach { entry ->
            // The reader must parse the written name back to exactly the segments we intend;
            // anything it would drop (NUL, "..", depth) or reinterpret (backslashes) is rejected.
            val intended = entry.name.split('/')
            if (ArchiveEntrySafety.parseEntryName(entry.name) != intended) {
                throw WriteException("Entry name is not archive-safe: ${entry.name}", entry.source)
            }
        }
    }

    // region index building

    private fun requireSupported(container: APath<*>): ArchiveFormat {
        if (container is ArchivePath) throw ReadException("Nested archives are not supported", container)
        return detectFormat(container) ?: throw ReadException("Not a supported archive", container)
    }

    private suspend fun buildIndex(
        container: APath<*>,
        format: ArchiveFormat,
        stat: ContainerStat,
    ): ArchiveIndex = withContext(dispatcherProvider.IO) {
        log(TAG) { "buildIndex($container, $format)" }
        val collected = handlerFor(format).scan(container, format)
        val (bySegments, children) = buildIndexMaps(collected.entries)
        ArchiveIndex(
            container = container,
            format = format,
            fingerprint = stat.fingerprint,
            entriesBySegments = bySegments,
            childrenBySegments = children,
            skippedUnsafe = collected.skippedUnsafe,
            skippedSpecial = collected.skippedSpecial,
            isHeaderEncrypted = collected.headerEncrypted,
            passwordGeneration = collected.passwordGeneration,
        ).also {
            log(TAG, INFO) {
                "buildIndex($container): ${it.entriesBySegments.size} entries" +
                    ", skipped unsafe=${it.skippedUnsafe} special=${it.skippedSpecial}"
            }
        }
    }

    // endregion

    // region container access

    private suspend fun File.copyFromStream(input: InputStream) {
        outputStream().use { output ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
            output.flush()
        }
    }

    // endregion

    data class ContainerStat(
        val size: Long?,
        val modifiedAt: Instant?,
        val generation: Int = 0,
    ) {
        // Null mtimes make change detection unreliable; the size-only fingerprint is still
        // process-stable, and index() re-stats around every build. [generation] disambiguates
        // same-size/coarse-mtime overwrites (bumped by invalidate()).
        val fingerprint: String = "${size ?: "?"}:${modifiedAt?.toEpochMilliseconds() ?: "?"}:$generation"
    }

    companion object {
        private val TAG = logTag("Gateway", "Archive", "Service")
        private const val MAX_CACHED_INDEXES = 8
        private const val COMMIT_LOCK_STRIPES = 16
        // Prefixes live in ArchiveDiskCache, which sweeps them all on startup so no materialized
        // archive cache survives a process restart (stale content or decrypted plaintext).
        private val PREFIX_ENTRY = ArchiveDiskCache.PREFIX_ENTRY
        private val PREFIX_ENTRY_DECRYPTED = ArchiveDiskCache.PREFIX_EPHEMERAL_DECRYPTED
    }
}
