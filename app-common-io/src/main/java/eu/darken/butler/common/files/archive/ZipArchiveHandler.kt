package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.COPY_BUFFER_SIZE
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.MAX_ENTRIES
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.UNIX_TYPE_MASK
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.UNIX_TYPE_SYMLINK
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.VERIFY_DRAIN_LIMIT
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.util.PasswordCallback
import net.lingala.zip4j.io.inputstream.ZipInputStream as Zip4jStreamInput
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.time.Instant

/**
 * Zip reads require random access: a [java.nio.channels.SeekableByteChannel] over `gatewaySwitch.file()` for
 * commons-compress, or positioned zip4j streams ([Zip4jPositionedStream]) for encrypted entries.
 * Forward-only backends throw [ArchiveNotSeekableException] - there is deliberately NO hidden
 * whole-container copy; callers offer explicit extraction or an explicit local copy instead.
 */
internal class ZipArchiveHandler(private val context: ArchiveHandlerContext) : ArchiveHandler {

    private val gatewaySwitch: GatewaySwitch get() = context.gatewaySwitch

    private val passwordStore: ArchivePasswordStore get() = context.passwordStore

    override val capabilities: ArchiveCapabilities = CAPABILITIES

    override val mayDecryptUnflaggedEntries: Boolean = false

    override suspend fun scan(container: APath<*>, format: ArchiveFormat): ScanResult = scanZip(container)

    override suspend fun openEntry(container: APath<*>, index: ArchiveIndex, meta: ArchiveEntryMeta): InputStream =
        if (meta.isEncrypted) openEncryptedZipEntry(container, meta)
        else openZipEntry(container, meta)

    override suspend fun useEntryStreams(
        container: APath<*>,
        index: ArchiveIndex,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    ) = useZipEntryStreams(container, entries, action)

    /**
     * Probes the SMALLEST encrypted entry: drained fully when its compressed size is at most
     * [VERIFY_DRAIN_LIMIT] (full AES-MAC/CRC verification), otherwise a single byte is read (the
     * cipher's header verifier check). It does not detect archives whose entries use different
     * passwords - those surface as a failed read during extraction, after earlier entries were
     * already written.
     */
    override suspend fun verifyPassword(
        container: APath<*>,
        password: CharArray,
        index: suspend () -> ArchiveIndex,
    ): Boolean {
        val index = index()
        val probe = index.entriesBySegments.values
            .filter { it.isEncrypted && !it.isDirectory && !it.isSymlink }
            .minByOrNull { it.compressedSize ?: it.size ?: Long.MAX_VALUE }
            ?: return true
        val handle = context.openSeekableHandle(container)
        return try {
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

    override val writer: ArchiveWriter = ArchiveWriter { out, options, entries, onEntry ->
        writeZipEntries(out, options, entries, onEntry)
    }

    override val sequentialExtractor: SequentialExtractor =
        SequentialExtractor { container, stat, processedOrdinals, onContainerProgress, action ->
            extractSequential(container, stat, processedOrdinals, onContainerProgress, action)
        }

    /**
     * Streams the container once with zip4j's local-header reader.
     *
     * - [processedOrdinals] are drained but not delivered again. Ordinals are the only stable
     *   identity - raw names may legally repeat within an archive.
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
    private suspend fun extractSequential(
        container: APath<*>,
        stat: ArchiveService.ContainerStat,
        processedOrdinals: Set<Int>,
        onContainerProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
        action: suspend (SequentialEntry, InputStream) -> SequentialOutcome,
    ): SequentialResult {
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
        return SequentialResult(extracted = extracted, skippedUnsafe = skippedUnsafe)
    }

    private suspend fun writeZipEntries(
        rawOut: java.io.OutputStream,
        options: ArchiveWriteOptions,
        entries: List<ArchiveService.WriteEntry>,
        onEntry: suspend (ArchiveService.WriteEntry, Long) -> Unit,
    ) {
        val zipOut = options.password?.let { ZipOutputStream(rawOut, it) } ?: ZipOutputStream(rawOut)
        zipOut.use { zip ->
            entries.forEach { entry ->
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(buildZipParameters(entry, options))
                val written = if (entry.isDirectory) 0L else context.pumpSource(entry, zip)
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

    private fun buildZipParameters(entry: ArchiveService.WriteEntry, options: ArchiveWriteOptions) = ZipParameters().apply {
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

    private suspend fun scanZip(container: APath<*>): ScanResult {
        var skippedUnsafe = 0
        var skippedSpecial = 0
        val metas = ArrayList<ArchiveEntryMeta>()
        context.openContainerChannel(container).use { channel ->
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

    private suspend fun openZipEntry(container: APath<*>, meta: ArchiveEntryMeta): InputStream {
        val channel = context.openContainerChannel(container)
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
        val handle = context.openSeekableHandle(container)
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
            context.openContainerChannel(container).use { channel ->
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
            val handle = context.openSeekableHandle(container)
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

    companion object {
        val CAPABILITIES = ArchiveCapabilities(
            access = ArchiveCapabilities.Access.RANDOM,
            sequentialExtractionFallback = true,
            readEncryption = setOf(ArchiveCapabilities.EncryptionScope.ENTRY_DATA),
            wrongPasswordDistinguishable = true,
            writable = true,
            writeEncryption = true,
        )
        private const val ZIP64_SIZE_LIMIT = 0xFFFFFFFFL
    }
}
