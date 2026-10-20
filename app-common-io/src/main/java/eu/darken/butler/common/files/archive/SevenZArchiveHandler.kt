package eu.darken.butler.common.files.archive

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.files.APath
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
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.time.Instant

/** 7z: random access and read-only. An encrypted entry list needs the password before anything is indexed. */
internal class SevenZArchiveHandler(private val context: ArchiveHandlerContext) : ArchiveHandler {

    private val passwordStore: ArchivePasswordStore get() = context.passwordStore

    override val capabilities: ArchiveCapabilities = CAPABILITIES

    override val mayDecryptUnflaggedEntries: Boolean = true

    override suspend fun scan(container: APath<*>, format: ArchiveFormat): ScanResult = scanSevenZ(container)

    override suspend fun openEntry(container: APath<*>, index: ArchiveIndex, meta: ArchiveEntryMeta): InputStream =
        openSevenZEntry(container, index, meta)

    override suspend fun useEntryStreams(
        container: APath<*>,
        index: ArchiveIndex,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    ) = useSevenZEntryStreams(container, index, entries, action)

    override suspend fun verifyPassword(
        container: APath<*>,
        password: CharArray,
        index: suspend () -> ArchiveIndex,
    ): Boolean = verifySevenZPassword(container, password)

    override val writer: ArchiveWriter? = null

    override val sequentialExtractor: SequentialExtractor? = null

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
        val channel = OkioSeekableByteChannel(context.openSeekableHandle(container))
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

    companion object {
        private val TAG = logTag("Gateway", "Archive", "SevenZ")
        val CAPABILITIES = ArchiveCapabilities(
            access = ArchiveCapabilities.Access.RANDOM,
            sequentialExtractionFallback = false,
            readEncryption = setOf(
                ArchiveCapabilities.EncryptionScope.ENTRY_DATA,
                ArchiveCapabilities.EncryptionScope.HEADERS,
            ),
            wrongPasswordDistinguishable = false,
            writable = false,
            writeEncryption = false,
        )
        // commons-compress 1.28 reports an unknown 7z coder (e.g. PPMd) only through this message.
        private const val SEVEN_Z_UNSUPPORTED_METHOD = "Unsupported compression method"
        // Per-reader decoder budget; larger dictionaries fail as "not enough memory" instead of an OOM.
        private const val SEVEN_Z_MAX_MEMORY_BYTES = 256L * 1024 * 1024
    }
}
