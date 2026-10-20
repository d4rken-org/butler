package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.ArchivePath

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.COPY_BUFFER_SIZE
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.MAX_ENTRIES
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.SPECIAL_UNIX_TYPES
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.UNIX_TYPE_MASK
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.UNIX_TYPE_SYMLINK
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.VERIFY_DRAIN_LIMIT
import eu.darken.butler.common.files.errors.ReadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import eu.darken.butler.common.files.errors.WriteException
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.util.PasswordCallback
import net.lingala.zip4j.io.inputstream.ZipInputStream as Zip4jStreamInput
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Central archive backend: entry indexing, streaming entry reads, password handling and
 * container/entry materialization. [ArchiveGateway] is a thin adapter over this; Explorer
 * operations orchestrate it. No commons-compress/zip4j types leak out of this package.
 *
 * Zip reads require random access: a [SeekableByteChannel] over `gatewaySwitch.file()` for
 * commons-compress, or positioned zip4j streams ([Zip4jPositionedStream]) for encrypted entries.
 * Forward-only backends throw [ArchiveNotSeekableException] - there is deliberately NO hidden
 * whole-container copy; callers offer explicit extraction or an explicit local copy instead.
 * Tar family formats are scanned/streamed sequentially and work on any backend. 7z needs random
 * access like zip, and an encrypted 7z entry list needs the password before anything is indexed.
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
        when (index.format) {
            ArchiveFormat.ZIP ->
                if (meta.isEncrypted) openEncryptedZipEntry(path.container, meta)
                else openZipEntry(path.container, meta)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2 ->
                tarHandler.openEntry(path.container, index, meta)
            ArchiveFormat.SEVEN_Z -> openSevenZEntry(path.container, index, meta)
        }
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
        when (index.format) {
            ArchiveFormat.ZIP -> useZipEntryStreams(container, files, action)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2 ->
                tarHandler.useEntryStreams(container, index, files, action)
            ArchiveFormat.SEVEN_Z -> useSevenZEntryStreams(container, index, files, action)
        }
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
        // 7z encryption is probed per archive, so any 7z read with a password may be decrypted content.
        val decrypted = meta.isEncrypted || (index.format == ArchiveFormat.SEVEN_Z && hasPassword(path.container))
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
     *   they are drained but not delivered again. Ordinals are the only stable identity - raw
     *   names may legally repeat within an archive.
     * - [expectedFingerprint]: fingerprint from the first pass; a container swapped between
     *   passes aborts instead of mixing content from different archive versions.
     * - A missing password surfaces as [ArchivePasswordRequiredException] from inside
     *   `getNextEntry` (zip4j's PasswordCallback) before any entry data is consumed; a wrong
     *   cached password evicts and re-prompts with attemptFailed=true.
     * - Aborts ([SequentialAbortException]) on unsupported effective compression methods and on
     *   streamed STORE entries with data descriptors - zip4j cannot safely skip past either.
     * - Limitations vs the indexed path: zip symlinks are NOT detectable sequentially (unix mode
     *   lives in the central directory) and extract as regular files; [onContainerProgress] is
     *   invoked from the reading thread (non-suspending, keep it cheap) and reports raw container
     *   bytes, not decompressed output.
     */
    suspend fun extractZipSequential(
        container: APath<*>,
        processedOrdinals: Set<Int> = emptySet(),
        expectedFingerprint: String? = null,
        onContainerProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        action: suspend (SequentialEntry, InputStream) -> SequentialOutcome,
    ): SequentialResult = withContext(dispatcherProvider.IO) {
        if (requireSupported(container) != ArchiveFormat.ZIP) {
            throw ReadException("Sequential extraction only supports zip archives", container)
        }
        val stat = statContainer(container)
        if (expectedFingerprint != null && stat.fingerprint != expectedFingerprint) {
            throw SequentialAbortException("Archive changed between extraction passes", container, 0, 0)
        }
        var extracted = 0
        var skippedUnsafe = 0
        var headersSeen = 0

        fun abort(message: String, cause: Throwable? = null): Nothing =
            throw SequentialAbortException(message, container, extracted, skippedUnsafe, cause)

        gatewaySwitch.openInputStream(container).use { raw ->
            val counting = object : FilterInputStream(raw) {
                private var count = 0L
                override fun read(): Int = super.read().also { if (it != -1) bump(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }

                override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) bump(it) }
                private fun bump(n: Long) {
                    count += n
                    onContainerProgress(count, stat.size)
                }
            }
            // zip4j retains this copy for per-entry cipher init across the whole pass; wiped
            // once the stream is closed.
            val cachedPassword = passwordStore.get(container)
            val zipIn = if (cachedPassword != null) {
                Zip4jStreamInput(counting, cachedPassword)
            } else {
                Zip4jStreamInput(counting, PasswordCallback { throw ArchivePasswordRequiredException(container) })
            }
            try {
                zipIn.use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val local = try {
                            zip.nextEntry
                        } catch (e: ArchivePasswordRequiredException) {
                            throw e
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ZipException) {
                            if (e.type == ZipException.Type.WRONG_PASSWORD) {
                                passwordStore.evict(container)
                                throw ArchivePasswordRequiredException(container, attemptFailed = true, cause = e)
                            }
                            abort("Archive stream failed: ${e.message}", e)
                        } catch (e: IOException) {
                            abort("Archive stream failed", e)
                        } ?: break

                        val ordinal = headersSeen
                        headersSeen++
                        if (headersSeen > MAX_ENTRIES) {
                            throw ReadException("Archive has too many entries (limit $MAX_ENTRIES)", container)
                        }
                        // Gates before anything that would require draining this entry: zip4j treats
                        // every effective non-DEFLATE method as STORE, so it cannot skip past entries
                        // it can't actually decompress.
                        val effective = local.aesExtraDataRecord?.compressionMethod ?: local.compressionMethod
                        if (effective != CompressionMethod.STORE && effective != CompressionMethod.DEFLATE) {
                            abort("Unsupported compression method $effective for ${local.fileName}")
                        }
                        if (local.isDataDescriptorExists && effective == CompressionMethod.STORE) {
                            abort("Streamed STORE entry with unknown size: ${local.fileName}")
                        }
                        if (local.isDirectory) continue
                        if (ordinal in processedOrdinals) continue
                        val segments = ArchiveEntrySafety.parseEntryName(local.fileName)
                        if (segments == null) {
                            skippedUnsafe++
                            continue
                        }
                        val entry = SequentialEntry(
                            ordinal = ordinal,
                            segments = segments,
                            rawName = local.fileName,
                            isEncrypted = local.isEncrypted,
                        )
                        val outcome = try {
                            // Shield: the shared zip stream continues to the next entry.
                            action(entry, object : FilterInputStream(zip) {
                                override fun close() = Unit
                            })
                        } catch (e: ZipException) {
                            // ZipCrypto reports a wrong password only via CRC mismatch at entry EOF.
                            if (e.type == ZipException.Type.WRONG_PASSWORD) {
                                passwordStore.evict(container)
                                throw ArchivePasswordRequiredException(container, attemptFailed = true, cause = e)
                            }
                            abort("Failed to read entry data: ${local.fileName}", e)
                        }
                        if (outcome == SequentialOutcome.EXTRACTED) extracted++
                    }
                    if (headersSeen == 0) {
                        throw ReadException("Not a streamable zip (preamble/SFX or no local headers)", container)
                    }
                }
            } finally {
                cachedPassword?.fill(Char.MIN_VALUE)
            }
        }
        SequentialResult(extracted = extracted, skippedUnsafe = skippedUnsafe)
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
     *
     * Probes the SMALLEST encrypted entry: drained fully when its compressed size is at most
     * [VERIFY_DRAIN_LIMIT] (full AES-MAC/CRC verification), otherwise a single byte is read (the
     * cipher's header verifier check). It does not detect archives whose entries use different
     * passwords - those surface as a failed read during extraction, after earlier entries were
     * already written.
     */
    suspend fun verifyPassword(container: APath<*>, password: CharArray): Boolean =
        withContext(dispatcherProvider.IO) {
            when (requireSupported(container)) {
                ArchiveFormat.SEVEN_Z -> return@withContext verifySevenZPassword(container, password)
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2 ->
                    return@withContext tarHandler.verifyPassword(container, password) { index(container) }
                ArchiveFormat.ZIP -> Unit
            }
            val index = index(container)
            val probe = index.entriesBySegments.values
                .filter { it.isEncrypted && !it.isDirectory && !it.isSymlink }
                .minByOrNull { it.compressedSize ?: it.size ?: Long.MAX_VALUE }
                ?: return@withContext true
            val handle = handlerContext.openSeekableHandle(container)
            try {
                Zip4jPositionedStream.open(handle, container, probe, password).use { stream ->
                    if ((probe.compressedSize ?: Long.MAX_VALUE) <= VERIFY_DRAIN_LIMIT) {
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (stream.read(buffer) != -1) currentCoroutineContext().ensureActive()
                    } else {
                        stream.read()
                    }
                }
                true
            } catch (e: ZipException) {
                if (e.type == ZipException.Type.WRONG_PASSWORD) false
                else throw ReadException("Failed to verify password", container, e)
            } finally {
                runCatching { handle.close() }
            }
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
        gatewaySwitch.openOutputStream(destination).use { rawOut ->
            when (options.format) {
                ArchiveFormat.ZIP -> writeZipEntries(rawOut, options, entries, onEntry)
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2 ->
                    tarHandler.writer.write(rawOut, options, entries, onEntry)
                // ArchiveWriteOptions refuses read-only formats, so no output is ever opened for one.
                ArchiveFormat.SEVEN_Z -> throw IllegalArgumentException("7z archives cannot be created")
            }
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

    private suspend fun writeZipEntries(
        rawOut: java.io.OutputStream,
        options: ArchiveWriteOptions,
        entries: List<WriteEntry>,
        onEntry: suspend (WriteEntry, Long) -> Unit,
    ) {
        val zipOut = options.password?.let { ZipOutputStream(rawOut, it) } ?: ZipOutputStream(rawOut)
        zipOut.use { zip ->
            entries.forEach { entry ->
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(buildZipParameters(entry, options))
                val written = if (entry.isDirectory) 0L else handlerContext.pumpSource(entry, zip)
                if (!entry.isDirectory) {
                    // zip4j doesn't verify the declared size against the bytes written, and derives
                    // the ZIP64 local layout from it up front. Abort (discarding the temp) rather than
                    // commit a malformed archive when a source changed under us or an unknown-size
                    // stream crossed the ZIP64 boundary without a reservation.
                    val declared = entry.size
                    if (declared != null && written != declared) {
                        throw WriteException("Source changed during compression: ${entry.name}", entry.source)
                    }
                    if (declared == null && written > ZIP64_SIZE_LIMIT) {
                        throw WriteException("Unknown-size entry exceeded the ZIP64 limit: ${entry.name}", entry.source)
                    }
                }
                zip.closeEntry()
                onEntry(entry, written)
            }
        }
    }

    private fun buildZipParameters(entry: WriteEntry, options: ArchiveWriteOptions) = ZipParameters().apply {
        // zip4j defaults to UTF-8 names with the language-encoding flag set; no charset config needed.
        fileNameInZip = entry.name + if (entry.isDirectory) "/" else ""
        if (entry.isDirectory) {
            compressionMethod = CompressionMethod.STORE
            entrySize = 0
        } else {
            compressionMethod = CompressionMethod.DEFLATE
            compressionLevel = options.preset.toZip4jLevel()
            // Declare the size when known so zip4j reserves ZIP64 local-header fields for entries
            // >= 4 GiB; it still writes a data descriptor with the real size. Left unset (-1) only
            // when the size is unknown, which caps such entries at the 32-bit descriptor layout.
            entry.size?.let { entrySize = it }
            if (options.password != null) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
    }

    private fun CompressionPreset.toZip4jLevel(): CompressionLevel = when (this) {
        CompressionPreset.FAST -> CompressionLevel.FASTEST
        // HIGHER is deflate 6, the java.util.zip default.
        CompressionPreset.NORMAL -> CompressionLevel.HIGHER
        CompressionPreset.BEST -> CompressionLevel.ULTRA
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
        val collected = when (format) {
            ArchiveFormat.ZIP -> scanZip(container)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2 -> tarHandler.scan(container, format)
            ArchiveFormat.SEVEN_Z -> scanSevenZ(container)
        }
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

    private suspend fun scanZip(container: APath<*>): ScanResult {
        var skippedUnsafe = 0
        var skippedSpecial = 0
        val metas = ArrayList<ArchiveEntryMeta>()
        handlerContext.openContainerChannel(container).use { channel ->
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                for (entry in zip.entries) {
                    currentCoroutineContext().ensureActive()
                    if (metas.size >= MAX_ENTRIES) {
                        throw ReadException("Archive has too many entries (limit $MAX_ENTRIES)", container)
                    }
                    // Split/multi-disk entries can't be read through a single-container channel;
                    // their local-header offsets point into a different volume.
                    if (entry.diskNumberStart > 0) {
                        skippedSpecial++
                        continue
                    }
                    val segments = ArchiveEntrySafety.parseEntryName(entry.name)
                    if (segments == null) {
                        skippedUnsafe++
                        continue
                    }
                    metas += entry.toMeta(segments)
                }
            }
        }
        return ScanResult(metas, skippedUnsafe, skippedSpecial)
    }

    private fun ZipArchiveEntry.toMeta(segments: List<String>): ArchiveEntryMeta {
        val isLink = (unixMode and UNIX_TYPE_MASK) == UNIX_TYPE_SYMLINK
        return ArchiveEntryMeta(
            segments = segments,
            rawName = name,
            isDirectory = isDirectory,
            size = size.takeIf { it >= 0 },
            modifiedAt = time.takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) },
            isEncrypted = generalPurposeBit.usesEncryption(),
            isSymlink = isLink && !isDirectory,
            localHeaderOffset = localHeaderOffset.takeIf { it >= 0 },
            compressedSize = compressedSize.takeIf { it >= 0 },
            crc = crc.takeIf { it >= 0 },
            rawMethod = method.takeIf { it >= 0 },
        )
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

    // region entry streams

    private suspend fun openZipEntry(container: APath<*>, meta: ArchiveEntryMeta): InputStream {
        val channel = handlerContext.openContainerChannel(container)
        val zip = try {
            ZipFile.builder().setSeekableByteChannel(channel).get()
        } catch (e: Exception) {
            runCatching { channel.close() }
            throw ReadException("Failed to open archive", container, e)
        }
        try {
            val entry = zip.getEntries(meta.rawName).lastOrNull()
                ?: throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
            if (!zip.canReadEntryData(entry)) {
                throw ReadException("Unsupported compression method for ${meta.rawName}", container)
            }
            val stream = zip.getInputStream(entry)
            return object : FilterInputStream(stream) {
                override fun close() {
                    super.close()
                    zip.close() // also closes the channel
                }
            }
        } catch (e: Exception) {
            runCatching { zip.close() }
            throw e
        }
    }

    /**
     * Maps zip4j failures on encrypted entry access: a wrong password evicts the cached one and
     * becomes a re-prompt, other zip4j errors become read errors. AES MAC failures arrive as plain
     * [java.io.IOException] and pass through as corruption - they must never trigger a password loop.
     */
    private fun mapEncryptedZipError(e: Exception, container: APath<*>): Exception = when {
        e is ArchivePasswordRequiredException || e is ReadException || e is CancellationException -> e
        e is ZipException && e.type == ZipException.Type.WRONG_PASSWORD -> {
            passwordStore.evict(container)
            ArchivePasswordRequiredException(container, attemptFailed = true, cause = e)
        }
        e is ZipException -> ReadException("Failed to read encrypted entry", container, e)
        else -> e
    }

    private suspend fun openEncryptedZipEntry(container: APath<*>, meta: ArchiveEntryMeta): InputStream {
        val password = passwordStore.get(container) ?: throw ArchivePasswordRequiredException(container)
        val handle = handlerContext.openSeekableHandle(container)
        val stream = try {
            // The adapter copies the password for zip4j; our store-derived copy is wiped either way.
            Zip4jPositionedStream.open(handle, container, meta, password)
        } catch (e: Exception) {
            runCatching { handle.close() }
            throw mapEncryptedZipError(e, container)
        } finally {
            password.fill(Char.MIN_VALUE)
        }
        // ZipCrypto reports a wrong password only via CRC mismatch at entry EOF, so reads need the
        // same mapping as stream setup. Closing releases the handle owned by this single-entry read.
        return object : FilterInputStream(stream) {
            override fun read(): Int = guarded { super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { super.read(b, off, len) }
            private inline fun <T> guarded(block: () -> T): T = try {
                block()
            } catch (e: ZipException) {
                throw mapEncryptedZipError(e, container)
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { handle.close() }
                }
            }
        }
    }

    private suspend fun useZipEntryStreams(
        container: APath<*>,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    ) {
        val (encrypted, plain) = entries.partition { it.isEncrypted }
        if (plain.isNotEmpty()) {
            handlerContext.openContainerChannel(container).use { channel ->
                ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                    plain.forEach { meta ->
                        currentCoroutineContext().ensureActive()
                        val entry = zip.getEntries(meta.rawName).lastOrNull()
                            ?: throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
                        if (!zip.canReadEntryData(entry)) {
                            throw ReadException("Unsupported compression method for ${meta.rawName}", container)
                        }
                        zip.getInputStream(entry).use { action(meta, it) }
                    }
                }
            }
        }
        if (encrypted.isNotEmpty()) {
            val password = passwordStore.get(container) ?: throw ArchivePasswordRequiredException(container)
            // One probed handle serves all encrypted entries; each entry gets its own positioned stream.
            val handle = handlerContext.openSeekableHandle(container)
            try {
                encrypted.forEach { meta ->
                    currentCoroutineContext().ensureActive()
                    try {
                        Zip4jPositionedStream.open(handle, container, meta, password).use { action(meta, it) }
                    } catch (e: Exception) {
                        throw mapEncryptedZipError(e, container)
                    }
                }
            } finally {
                runCatching { handle.close() }
                password.fill(Char.MIN_VALUE)
            }
        }
    }

    // endregion

    // region 7z

    /**
     * An open 7z reader plus the channel under it, so a failure can be attributed to storage.
     * [generation] is the [ArchivePasswordStore] generation of the password it was opened with.
     * [passwordProven] is set when that password decrypted the entry list, whose CRC confirms it.
     */
    private class SevenZHandle(
        val file: SevenZFile,
        val channel: OkioSeekableByteChannel,
        val encrypted: Boolean,
        val generation: Int,
        val passwordProven: Boolean,
    ) : Closeable {
        override fun close() = file.close() // also closes the channel
    }

    private suspend fun openSevenZ(
        container: APath<*>,
        password: CharArray?,
        generation: Int,
        headerEncrypted: Boolean = false,
        evictOnFailure: Boolean = true,
    ): SevenZHandle {
        val channel = OkioSeekableByteChannel(handlerContext.openSeekableHandle(container))
        try {
            val file = SevenZFile.builder()
                .setSeekableByteChannel(channel)
                .setMaxMemoryLimitKiB(sevenZMemoryLimitKiB())
                .apply { if (password != null) setPassword(password) }
                .get()
            return SevenZHandle(
                file = file,
                channel = channel,
                encrypted = password != null,
                generation = generation,
                passwordProven = headerEncrypted && password != null,
            )
        } catch (e: Exception) {
            runCatching { channel.close() }
            throw mapSevenZError(
                e = e,
                container = container,
                channel = channel,
                encrypted = password != null,
                generation = generation,
                passwordProven = false,
                evict = evictOnFailure,
            )
        }
    }

    /**
     * Opens with the cached password whenever there is one: encryption is probed on the first block
     * only, so an archive indexed as plain can still hold encrypted blocks further in.
     */
    private suspend fun openSevenZForRead(container: APath<*>, index: ArchiveIndex): SevenZHandle {
        val snapshot = passwordStore.snapshot(container)
        val password = snapshot.password
        if (index.isEncrypted && password == null) throw ArchivePasswordRequiredException(container)
        try {
            return openSevenZ(container, password, snapshot.generation, headerEncrypted = index.isHeaderEncrypted)
        } finally {
            password?.fill(Char.MIN_VALUE)
        }
    }

    private fun sevenZMemoryLimitKiB(): Int =
        (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(SEVEN_Z_MAX_MEMORY_BYTES).div(1024).toInt()

    private fun mapSevenZError(e: Exception, container: APath<*>, handle: SevenZHandle): Exception =
        mapSevenZError(e, container, handle.channel, handle.encrypted, handle.generation, handle.passwordProven)

    /**
     * Storage failures, memory limits and unsupported codecs keep their own error. With an unproven
     * password in play, any other failure becomes a re-prompt that evicts that password: 7z AES has no
     * password check value, so a wrong key only shows as data the decoder rejects or as a CRC mismatch
     * at the end of an entry, exactly like damage would. Once the entry list decrypted with it, such a
     * failure is reported as damage instead.
     */
    private fun mapSevenZError(
        e: Exception,
        container: APath<*>,
        channel: OkioSeekableByteChannel,
        encrypted: Boolean,
        generation: Int,
        passwordProven: Boolean,
        evict: Boolean = true,
    ): Exception {
        val storageFailure = channel.lastReadFailure
        return when {
            e is CancellationException || e is ReadException -> e
            e is PasswordRequiredException -> ArchivePasswordRequiredException(container, cause = e)
            storageFailure != null && e.causeChain.any { it === storageFailure } -> e
            e.causeChain.any { it is MemoryLimitException || it is org.tukaani.xz.MemoryLimitException } ->
                ReadException("Not enough memory to unpack this 7z archive", container, e)
            e.message?.startsWith(SEVEN_Z_UNSUPPORTED_METHOD) == true ->
                ReadException("Unsupported 7z compression method", container, e)
            encrypted && passwordProven && e is IOException -> ReadException("Damaged 7z entry", container, e)
            encrypted && e is IOException -> {
                if (evict) passwordStore.evictIfGeneration(container, generation)
                ArchivePasswordRequiredException(container, attemptFailed = true, mayBeDamaged = true, cause = e)
            }
            else -> e
        }
    }

    private suspend fun scanSevenZ(container: APath<*>): ScanResult {
        var headerEncrypted = false
        var passwordGeneration: Int? = null
        val handle = try {
            openSevenZ(container, password = null, generation = passwordStore.generation(container))
        } catch (e: ArchivePasswordRequiredException) {
            headerEncrypted = true
            val snapshot = passwordStore.snapshot(container)
            val password = snapshot.password ?: throw e
            passwordGeneration = snapshot.generation
            try {
                openSevenZ(container, password, snapshot.generation, headerEncrypted = true)
            } finally {
                password.fill(Char.MIN_VALUE)
            }
        }
        var skippedUnsafe = 0
        var skippedSpecial = 0
        val metas = ArrayList<ArchiveEntryMeta>()
        handle.use {
            // An encrypted entry list implies encrypted content; with a readable one, probe for it.
            val contentEncrypted = headerEncrypted || isSevenZContentEncrypted(handle)
            for ((ordinal, entry) in handle.file.entries.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (ordinal >= MAX_ENTRIES) {
                    throw ReadException("Archive has too many entries (limit $MAX_ENTRIES)", container)
                }
                val unixType = entry.unixFileType()
                if (entry.isAntiItem || (unixType != null && unixType in SPECIAL_UNIX_TYPES)) {
                    skippedSpecial++
                    continue
                }
                val name = entry.name
                val segments = name?.let { ArchiveEntrySafety.parseEntryName(it) }
                if (name == null || segments == null) {
                    skippedUnsafe++
                    continue
                }
                val isLink = unixType == UNIX_TYPE_SYMLINK && !entry.isDirectory
                metas += ArchiveEntryMeta(
                    segments = segments,
                    rawName = name,
                    isDirectory = entry.isDirectory,
                    size = entry.size.takeIf { !entry.isDirectory && !isLink },
                    modifiedAt = entry.takeIf { it.hasLastModifiedDate }
                        ?.lastModifiedTime?.toMillis()
                        ?.takeIf { it > 0 }
                        ?.let { Instant.fromEpochMilliseconds(it) },
                    isEncrypted = contentEncrypted && entry.hasStream(),
                    isSymlink = isLink,
                    ordinal = ordinal,
                )
            }
        }
        return ScanResult(metas, skippedUnsafe, skippedSpecial, headerEncrypted, passwordGeneration)
    }

    /**
     * The library reports an entry's coders only once its data is opened, so encryption is probed by
     * opening the first entry without a password, which fails before any decryption work. The first
     * entry starts the first solid block, so no other entry is decoded to reach it. Blocks added later
     * can still be encrypted (7-Zip appending with -p); reads cope by always passing a cached password.
     */
    private fun isSevenZContentEncrypted(handle: SevenZHandle): Boolean {
        val first = handle.file.entries.firstOrNull { it.hasStream() && it.size > 0 } ?: return false
        return try {
            handle.file.getInputStream(first).use { it.read() }
            false
        } catch (e: PasswordRequiredException) {
            true
        } catch (e: IOException) {
            if (handle.channel.lastReadFailure != null) throw e
            // A damaged first block fails the read that needs it, not the whole listing.
            log(TAG, WARN) { "isSevenZContentEncrypted(): probe failed: ${e.asLog()}" }
            false
        }
    }

    // p7zip convention: bit 15 flags a unix mode stored in the high 16 bits of the attributes.
    private fun SevenZArchiveEntry.unixFileType(): Int? {
        if (!hasWindowsAttributes) return null
        val attributes = windowsAttributes
        if (attributes and 0x8000 == 0) return null
        return (attributes ushr 16) and UNIX_TYPE_MASK
    }

    private fun SevenZHandle.entryAt(meta: ArchiveEntryMeta, container: APath<*>): SevenZArchiveEntry {
        val ordinal = meta.ordinal ?: throw ReadException("Entry has no 7z position: ${meta.rawName}", container)
        return file.entries.elementAtOrNull(ordinal)?.takeIf { it.name == meta.rawName }
            ?: throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
    }

    private suspend fun openSevenZEntry(
        container: APath<*>,
        index: ArchiveIndex,
        meta: ArchiveEntryMeta,
    ): InputStream {
        val handle = openSevenZForRead(container, index)
        try {
            val entry = handle.entryAt(meta, container)
            if (!entry.hasStream()) {
                handle.close()
                return ByteArrayInputStream(ByteArray(0))
            }
            val stream = handle.file.getInputStream(entry)
            // Closing releases the reader owned by this single-entry read.
            return object : FilterInputStream(stream) {
                override fun read(): Int = guarded { super.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { super.read(b, off, len) }
                override fun skip(n: Long): Long = guarded { super.skip(n) }
                private inline fun <T> guarded(block: () -> T): T = try {
                    block()
                } catch (e: IOException) {
                    throw mapSevenZError(e, container, handle)
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { handle.close() }
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { handle.close() }
            throw mapSevenZError(e, container, handle)
        }
    }

    /**
     * One reader walks the archive in order, so each solid block is decoded once no matter how many of
     * its entries are wanted. Entries are matched by ordinal, the only identity that survives
     * duplicate names.
     */
    private suspend fun useSevenZEntryStreams(
        container: APath<*>,
        index: ArchiveIndex,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    ) {
        val wanted = entries.associateBy {
            it.ordinal ?: throw ReadException("Entry has no 7z position: ${it.rawName}", container)
        }.toMutableMap()
        if (wanted.isEmpty()) return
        openSevenZForRead(container, index).use { handle ->
            var ordinal = -1
            while (wanted.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val entry = try {
                    handle.file.nextEntry
                } catch (e: IOException) {
                    throw mapSevenZError(e, container, handle)
                } ?: break
                ordinal++
                val meta = wanted.remove(ordinal) ?: continue
                if (entry.name != meta.rawName) {
                    throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
                }
                if (!entry.hasStream()) {
                    action(meta, ByteArrayInputStream(ByteArray(0)))
                    continue
                }
                // Shield: reads go through the shared reader, whose current entry ends at EOF.
                action(meta, object : InputStream() {
                    override fun read(): Int = guarded { handle.file.read() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { handle.file.read(b, off, len) }
                    private inline fun <T> guarded(block: () -> T): T = try {
                        block()
                    } catch (e: IOException) {
                        throw mapSevenZError(e, container, handle)
                    }
                })
            }
        }
        if (wanted.isNotEmpty()) {
            throw ReadException("Entries vanished from archive: ${wanted.values.take(3).map { it.rawName }}", container)
        }
    }

    /**
     * A password is proven once it decrypts an encrypted entry list. With a readable entry list it only
     * counts as not disproven: the first entry is drained up to [VERIFY_DRAIN_LIMIT], so a wrong key on
     * a larger or later-encrypted entry still passes here and fails at the actual read instead.
     */
    private suspend fun verifySevenZPassword(container: APath<*>, password: CharArray): Boolean {
        val generation = passwordStore.generation(container)
        val headerEncrypted = try {
            openSevenZ(container, password = null, generation = generation).close()
            false
        } catch (e: ArchivePasswordRequiredException) {
            true
        }
        val handle = try {
            openSevenZ(container, password, generation, headerEncrypted = headerEncrypted, evictOnFailure = false)
        } catch (e: ArchivePasswordRequiredException) {
            return false
        }
        handle.use {
            if (handle.passwordProven) return true
            val first = handle.file.entries.firstOrNull { it.hasStream() && it.size > 0 } ?: return true
            return try {
                handle.file.getInputStream(first).use { stream ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var total = 0L
                    while (total < VERIFY_DRAIN_LIMIT) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer)
                        if (read == -1) break
                        total += read
                    }
                }
                true
            } catch (e: IOException) {
                val mapped = mapSevenZError(
                    e = e,
                    container = container,
                    channel = handle.channel,
                    encrypted = true,
                    generation = generation,
                    passwordProven = false,
                    evict = false,
                )
                if (mapped is ArchivePasswordRequiredException) false else throw mapped
            }
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
        private const val ZIP64_SIZE_LIMIT = 0xFFFFFFFFL
        // commons-compress 1.28 reports an unknown 7z coder (e.g. PPMd) only through this message.
        private const val SEVEN_Z_UNSUPPORTED_METHOD = "Unsupported compression method"
        // Per-reader decoder budget; larger dictionaries fail as "not enough memory" instead of an OOM.
        private const val SEVEN_Z_MAX_MEMORY_BYTES = 256L * 1024 * 1024
        // Prefixes live in ArchiveDiskCache, which sweeps them all on startup so no materialized
        // archive cache survives a process restart (stale content or decrypted plaintext).
        private val PREFIX_ENTRY = ArchiveDiskCache.PREFIX_ENTRY
        private val PREFIX_ENTRY_DECRYPTED = ArchiveDiskCache.PREFIX_EPHEMERAL_DECRYPTED
    }
}
