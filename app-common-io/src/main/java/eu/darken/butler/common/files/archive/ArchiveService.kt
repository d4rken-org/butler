package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.ArchivePath

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Central archive backend: entry indexing, streaming entry reads, password handling and
 * container/entry materialization. [ArchiveGateway] is a thin adapter over this; Explorer
 * operations orchestrate it. No commons-compress/zip4j types leak out of this package.
 *
 * Reads work on any gateway backend: zip random access goes through a [SeekableByteChannel]
 * over `gatewaySwitch.file()` (falling back to a one-time local materialization when the
 * backend isn't seekable), tar family formats are scanned sequentially.
 */
@Singleton
class ArchiveService @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val gatewaySwitchLazy: dagger.Lazy<GatewaySwitch>,
    private val diskCache: ArchiveDiskCache,
    private val passwordStore: ArchivePasswordStore,
) {

    private val gatewaySwitch: GatewaySwitch get() = gatewaySwitchLazy.get()

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
            ?.takeIf { it.fingerprint == stat.fingerprint }
            ?.let { return it }

        return buildLocks.getOrPut(container) { Mutex() }.withLock {
            cacheMutex.withLock { indexCache[container] }
                ?.takeIf { it.fingerprint == stat.fingerprint }
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
                openTarEntry(path.container, index.format, meta)
        }
    }

    /**
     * Streams multiple entries with shared handles: one zip open (plus one zip4j open if
     * encrypted entries are requested), or a SINGLE sequential pass for tar-family formats.
     * Tar entries are delivered in archive order, not request order. The streams passed to
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
                useTarEntryStreams(container, index.format, files, action)
        }
    }

    /**
     * Materializes a single entry to a local scratch file (for seekable access like Editor/preview).
     * Decrypted content is cached under a purgeable prefix.
     */
    suspend fun materializeEntry(path: ArchivePath): File {
        val index = freshIndex(path.container)
        val meta = index.entriesBySegments[path.segments] ?: throw ReadException("Entry not found", path)
        val prefix = if (meta.isEncrypted) PREFIX_ENTRY_DECRYPTED else PREFIX_ENTRY
        val key = "${path.container}:${index.fingerprint}:${path.segments.joinToString("/")}"
        return diskCache.materialize(prefix, key) { part ->
            openEntryStream(path).use { input -> part.copyFromStream(input) }
        }
    }

    /**
     * True if the archive holds encrypted entries and no currently-cached password decrypts them.
     * Used by operations to prompt before streaming rather than mid-stream.
     */
    suspend fun requiresPassword(container: APath<*>): Boolean = withContext(dispatcherProvider.IO) {
        val index = index(container)
        if (!index.isEncrypted) return@withContext false
        val cached = passwordStore.get(container) ?: return@withContext true
        !verifyPassword(container, cached)
    }

    /**
     * Checks [password] against the archive without caching it. True if entry content decrypts.
     *
     * v1 limitation: this validates only the first encrypted entry (reads one byte). It does not
     * detect archives whose entries use different passwords, nor mid-stream CRC/MAC failures - those
     * surface as a failed read during extraction, after earlier entries were already written.
     */
    suspend fun verifyPassword(container: APath<*>, password: CharArray): Boolean =
        withContext(dispatcherProvider.IO) {
            val index = index(container)
            val probe = index.entriesBySegments.values.firstOrNull { it.isEncrypted && !it.isDirectory }
                ?: return@withContext true
            val file = directLocalFile(container) ?: materializeContainer(container)
            try {
                net.lingala.zip4j.ZipFile(file, password).use { zip4j ->
                    val header = zip4j.getFileHeader(probe.rawName) ?: return@withContext false
                    zip4j.getInputStream(header).use { it.read() }
                }
                true
            } catch (e: ZipException) {
                if (e.type == ZipException.Type.WRONG_PASSWORD) false
                else throw ReadException("Failed to verify password", container, e)
            }
        }

    /** One file to write into a new archive. */
    data class WriteEntry(
        val name: String,
        val source: APath<*>,
        val isDirectory: Boolean,
        val size: Long?,
    )

    /**
     * Writes [entries] into a new archive at [destination] per [options]. Sources are read through
     * the gateway (any backend). [onEntry] reports bytes as each file completes.
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
                    writeTarEntries(rawOut, options, entries, onEntry)
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
                val written = if (entry.isDirectory) 0L else pumpSource(entry.source, zip)
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

    private suspend fun writeTarEntries(
        rawOut: java.io.OutputStream,
        options: ArchiveWriteOptions,
        entries: List<WriteEntry>,
        onEntry: suspend (WriteEntry, Long) -> Unit,
    ) {
        val compressed = when (options.format) {
            ArchiveFormat.TAR_GZ -> GzipCompressorOutputStream(
                rawOut,
                GzipParameters().apply { compressionLevel = options.preset.toGzipLevel() },
            )
            ArchiveFormat.TAR_BZ2 -> BZip2CompressorOutputStream(rawOut, options.preset.toBzip2BlockSize())
            else -> rawOut
        }
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(compressed).use { tar ->
            tar.setLongFileMode(
                org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX
            )
            entries.forEach { entry ->
                currentCoroutineContext().ensureActive()
                val tarEntry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(
                    entry.name + if (entry.isDirectory) "/" else ""
                )
                if (!entry.isDirectory) {
                    // Tar headers need the exact size up front; resolve it now if enumeration didn't.
                    tarEntry.size = entry.size
                        ?: gatewaySwitch.lookup(entry.source, LookupOptions(fetchSize = true)).size
                        ?: throw WriteException("Size unknown for tar entry", entry.source)
                }
                tar.putArchiveEntry(tarEntry)
                val written = if (entry.isDirectory) 0L else pumpSource(entry.source, tar)
                tar.closeArchiveEntry()
                onEntry(entry, written)
            }
        }
    }

    private fun CompressionPreset.toZip4jLevel(): CompressionLevel = when (this) {
        CompressionPreset.FAST -> CompressionLevel.FASTEST
        // HIGHER is deflate 6, the java.util.zip default.
        CompressionPreset.NORMAL -> CompressionLevel.HIGHER
        CompressionPreset.BEST -> CompressionLevel.ULTRA
    }

    private fun CompressionPreset.toGzipLevel(): Int = when (this) {
        CompressionPreset.FAST -> 1
        CompressionPreset.NORMAL -> 6
        CompressionPreset.BEST -> 9
    }

    private fun CompressionPreset.toBzip2BlockSize(): Int = when (this) {
        CompressionPreset.FAST -> 1
        CompressionPreset.NORMAL -> 5
        CompressionPreset.BEST -> 9
    }

    private suspend fun pumpSource(source: APath<*>, out: java.io.OutputStream): Long {
        var count = 0L
        gatewaySwitch.openInputStream(source).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                count += read
            }
        }
        return count
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
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2 -> scanTar(container, format)
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
        ).also {
            log(TAG, INFO) {
                "buildIndex($container): ${it.entriesBySegments.size} entries" +
                    ", skipped unsafe=${it.skippedUnsafe} special=${it.skippedSpecial}"
            }
        }
    }

    private class ScanResult(
        val entries: List<ArchiveEntryMeta>,
        val skippedUnsafe: Int,
        val skippedSpecial: Int,
    )

    private suspend fun scanZip(container: APath<*>): ScanResult {
        var skippedUnsafe = 0
        val metas = ArrayList<ArchiveEntryMeta>()
        openContainerChannel(container).use { channel ->
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                for (entry in zip.entries) {
                    currentCoroutineContext().ensureActive()
                    if (metas.size >= MAX_ENTRIES) {
                        throw ReadException("Archive has too many entries (limit $MAX_ENTRIES)", container)
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
        return ScanResult(metas, skippedUnsafe, 0)
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
        )
    }

    private suspend fun scanTar(container: APath<*>, format: ArchiveFormat): ScanResult {
        var skippedUnsafe = 0
        var skippedSpecial = 0
        val metas = ArrayList<ArchiveEntryMeta>()
        // Tar is forward-only, so entry streaming (openTarEntry / useTarEntryStreams) resolves a
        // duplicate name to its FIRST occurrence. Keep the index consistent with that by taking the
        // first occurrence here too, so reported metadata (size/date) matches the bytes actually read.
        val seenNames = HashSet<String>()
        gatewaySwitch.openInputStream(container).use { raw ->
            val limited = LimitedInputStream(decompress(raw, format), MAX_TAR_SCAN_BYTES, container)
            TarArchiveInputStream(limited).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    if (metas.size >= MAX_ENTRIES) {
                        throw ReadException("Archive has too many entries (limit $MAX_ENTRIES)", container)
                    }
                    when {
                        entry.isLink || entry.isFIFO() || entry.isCharacterDevice || entry.isBlockDevice -> {
                            skippedSpecial++
                            continue
                        }
                    }
                    val segments = ArchiveEntrySafety.parseEntryName(entry.name)
                    if (segments == null) {
                        skippedUnsafe++
                        continue
                    }
                    if (!seenNames.add(entry.name)) continue
                    metas += ArchiveEntryMeta(
                        segments = segments,
                        rawName = entry.name,
                        isDirectory = entry.isDirectory,
                        size = entry.size.takeIf { it >= 0 && !entry.isSymbolicLink },
                        modifiedAt = entry.lastModifiedDate?.time
                            ?.takeIf { it > 0 }
                            ?.let { Instant.fromEpochMilliseconds(it) },
                        isSymlink = entry.isSymbolicLink,
                        linkTarget = entry.linkName.takeIf { entry.isSymbolicLink && it.isNotEmpty() },
                    )
                }
            }
        }
        return ScanResult(metas, skippedUnsafe, skippedSpecial)
    }

    private fun decompress(raw: InputStream, format: ArchiveFormat): InputStream = when (format) {
        ArchiveFormat.TAR -> raw
        ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(raw)
        ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw)
        ArchiveFormat.ZIP -> throw IllegalArgumentException("Zip is not stream-decompressed")
    }

    // endregion

    // region container access

    /**
     * Best random access to the container bytes: a channel over the gateway's [okio.FileHandle]
     * when the backend supports positioned reads (verified by an actual probe read), else a
     * channel over a one-time materialized local copy.
     */
    private suspend fun openContainerChannel(container: APath<*>): SeekableByteChannel {
        var channel: OkioSeekableByteChannel? = null
        try {
            val handle = gatewaySwitch.file(container, readWrite = false)
            channel = OkioSeekableByteChannel(handle)
            val size = try {
                channel.size()
            } catch (e: IOException) {
                -1L
            }
            if (size > 0) {
                val probe = ByteBuffer.allocate(1)
                channel.position(size - 1)
                if (channel.read(probe) == 1) {
                    channel.position(0)
                    return channel
                }
            }
            log(TAG) { "openContainerChannel($container): not seekable, falling back to materialization" }
        } catch (e: CancellationException) {
            runCatching { channel?.close() }
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "openContainerChannel($container): seekable access failed: ${e.asLog()}" }
        }
        // Reached only on non-seekable / failed probe - always release the probed handle.
        runCatching { channel?.close() }
        val file = materializeContainer(container)
        return Files.newByteChannel(file.toPath(), StandardOpenOption.READ)
    }

    private suspend fun materializeContainer(container: APath<*>): File {
        val stat = statContainer(container)
        return diskCache.materialize(PREFIX_CONTAINER, "$container:${stat.fingerprint}") { part ->
            gatewaySwitch.openInputStream(container).use { input -> part.copyFromStream(input) }
        }
    }

    /** The container's backing [File] when it is directly readable without privilege escalation. */
    private fun directLocalFile(container: APath<*>): File? =
        (container as? LocalPath)?.file?.takeIf { it.isFile && it.canRead() }

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
        val channel = openContainerChannel(container)
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

    private suspend fun openEncryptedZipEntry(container: APath<*>, meta: ArchiveEntryMeta): InputStream {
        val password = passwordStore.get(container) ?: throw ArchivePasswordRequiredException(container)
        val file = directLocalFile(container) ?: materializeContainer(container)
        val zip4j = net.lingala.zip4j.ZipFile(file, password)
        try {
            val header = zip4j.getFileHeader(meta.rawName)
                ?: throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
            val stream = try {
                zip4j.getInputStream(header)
            } catch (e: ZipException) {
                if (e.type == ZipException.Type.WRONG_PASSWORD) {
                    passwordStore.evict(container)
                    throw ArchivePasswordRequiredException(container, attemptFailed = true, cause = e)
                }
                throw ReadException("Failed to read encrypted entry", container, e)
            }
            return object : FilterInputStream(stream) {
                override fun close() {
                    super.close()
                    zip4j.close()
                }
            }
        } catch (e: Exception) {
            runCatching { zip4j.close() }
            throw e
        }
    }

    private suspend fun openTarEntry(
        container: APath<*>,
        format: ArchiveFormat,
        meta: ArchiveEntryMeta,
    ): InputStream {
        val raw = gatewaySwitch.openInputStream(container)
        try {
            val tar = TarArchiveInputStream(decompress(raw, format))
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                if (entry.name == meta.rawName && !entry.isDirectory) {
                    // TarArchiveInputStream scopes read() to the current entry (EOF at entry end).
                    return object : FilterInputStream(tar) {
                        override fun close() = tar.close()
                    }
                }
            }
            tar.close()
            throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    private suspend fun useZipEntryStreams(
        container: APath<*>,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    ) {
        val (encrypted, plain) = entries.partition { it.isEncrypted }
        if (plain.isNotEmpty()) {
            openContainerChannel(container).use { channel ->
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
            val file = directLocalFile(container) ?: materializeContainer(container)
            net.lingala.zip4j.ZipFile(file, password).use { zip4j ->
                encrypted.forEach { meta ->
                    currentCoroutineContext().ensureActive()
                    val header = zip4j.getFileHeader(meta.rawName)
                        ?: throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
                    val stream = try {
                        zip4j.getInputStream(header)
                    } catch (e: ZipException) {
                        if (e.type == ZipException.Type.WRONG_PASSWORD) {
                            passwordStore.evict(container)
                            throw ArchivePasswordRequiredException(container, attemptFailed = true, cause = e)
                        }
                        throw ReadException("Failed to read encrypted entry", container, e)
                    }
                    stream.use { action(meta, it) }
                }
            }
        }
    }

    private suspend fun useTarEntryStreams(
        container: APath<*>,
        format: ArchiveFormat,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    ) {
        val wanted = entries.associateBy { it.rawName }.toMutableMap()
        if (wanted.isEmpty()) return
        gatewaySwitch.openInputStream(container).use { raw ->
            TarArchiveInputStream(decompress(raw, format)).use { tar ->
                while (wanted.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val meta = wanted.remove(entry.name) ?: continue
                    if (entry.isDirectory) continue
                    action(meta, object : FilterInputStream(tar) {
                        override fun close() {
                            // Shield: the shared tar stream continues to the next entry.
                        }
                    })
                }
            }
        }
        if (wanted.isNotEmpty()) {
            throw ReadException("Entries vanished from archive: ${wanted.keys.take(3)}", container)
        }
    }

    // endregion

    /** Guards tar-family index scans against decompression bombs. */
    private class LimitedInputStream(
        wrapped: InputStream,
        private val limit: Long,
        private val container: APath<*>,
    ) : FilterInputStream(wrapped) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it != -1) bump(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) bump(it) }

        private fun bump(n: Long) {
            count += n
            if (count > limit) throw IOException("Archive scan limit exceeded ($limit bytes) for $container")
        }
    }

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
        private const val MAX_ENTRIES = 50_000
        private const val MAX_TAR_SCAN_BYTES = 4L * 1024 * 1024 * 1024
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val UNIX_TYPE_MASK = 0xF000
        private const val UNIX_TYPE_SYMLINK = 0xA000
        private const val PREFIX_CONTAINER = "container"
        private const val PREFIX_ENTRY = "entry"
        // Shared with ArchiveDiskCache, which sweeps this prefix on startup so decrypted
        // plaintext never survives a process restart.
        private val PREFIX_ENTRY_DECRYPTED = ArchiveDiskCache.PREFIX_EPHEMERAL_DECRYPTED
    }
}
