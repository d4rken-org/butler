package eu.darken.butler.common.files.archive

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileHandle
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

internal interface ArchiveHandler {
    val capabilities: ArchiveCapabilities

    /** Content of an entry not flagged isEncrypted may still be decrypted when a password is cached (7z probes encryption per archive). */
    val mayDecryptUnflaggedEntries: Boolean

    suspend fun scan(container: APath<*>, format: ArchiveFormat): ScanResult

    suspend fun openEntry(container: APath<*>, index: ArchiveIndex, meta: ArchiveEntryMeta): InputStream

    /** [entries] are files only (service filters dirs/symlinks). Delivery order is the handler's (archive order for streamed formats). Streams valid only inside [action]. */
    suspend fun useEntryStreams(
        container: APath<*>,
        index: ArchiveIndex,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    )

    /** [index] is the service's cached index, built on demand. */
    suspend fun verifyPassword(container: APath<*>, password: CharArray, index: suspend () -> ArchiveIndex): Boolean

    /** Null when the format cannot be created. */
    val writer: ArchiveWriter?

    /** Null unless capabilities.sequentialExtractionFallback. */
    val sequentialExtractor: SequentialExtractor?
}

internal fun interface ArchiveWriter {
    suspend fun write(
        out: OutputStream,
        options: ArchiveWriteOptions,
        entries: List<ArchiveService.WriteEntry>,
        onEntry: suspend (ArchiveService.WriteEntry, Long) -> Unit,
    )
}

internal fun interface SequentialExtractor {
    suspend fun extract(
        container: APath<*>,
        stat: ArchiveService.ContainerStat,
        processedOrdinals: Set<Int>,
        onContainerProgress: (Long, Long?) -> Unit,
        action: suspend (SequentialEntry, InputStream) -> SequentialOutcome,
    ): SequentialResult
}

internal class ScanResult(
    val entries: List<ArchiveEntryMeta>,
    val skippedUnsafe: Int,
    val skippedSpecial: Int,
    val headerEncrypted: Boolean = false,
    val passwordGeneration: Int? = null,
    /** Multi-part archives: every file the scan read, container first. Empty when it read only the container. */
    val volumes: List<APath<*>> = emptyList(),
    /** [ArchiveHandlerContext.volumeFingerprint] of each of [volumes] after the container, taken before it was read. */
    val laterVolumeFingerprints: List<String> = emptyList(),
)

/** Shared container access for the format handlers. Resolves the gateway on use, never on construction. */
internal class ArchiveHandlerContext(
    private val gatewaySwitchLazy: dagger.Lazy<GatewaySwitch>,
    val passwordStore: ArchivePasswordStore,
    /** Stat fingerprint of a later volume of a multi-part archive, as its index records it. */
    val volumeFingerprint: suspend (APath<*>) -> String,
) {

    val gatewaySwitch: GatewaySwitch get() = gatewaySwitchLazy.get()

    /**
     * Opens the container through the gateway and verifies positioned reads actually work via a
     * probe read at the last byte. Only a failed probe on a successfully opened handle classifies
     * as [ArchiveNotSeekableException] — open failures (permissions, offline, vanished files)
     * propagate untouched so callers/UI don't mistake them for a capability limitation.
     */
    suspend fun openSeekableHandle(container: APath<*>): FileHandle {
        val handle = gatewaySwitch.file(container, readWrite = false)
        try {
            // Any positioned read that completes proves seekability - including -1 (EOF) at
            // offset 0 of an empty file, which is invalid as an archive but not stream-only.
            // Pipe-backed descriptors fail the pread itself (and report size -1).
            val size = handle.size()
            val probe = ByteArray(1)
            handle.read(if (size > 0) size - 1 else 0L, probe, 0, 1)
            return handle
        } catch (e: CancellationException) {
            runCatching { handle.close() }
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "openSeekableHandle($container): probe failed: ${e.asLog()}" }
            runCatching { handle.close() }
            throw ArchiveNotSeekableException(container, e)
        }
    }

    /**
     * Random access to the container bytes over the gateway's [okio.FileHandle]. Forward-only
     * backends throw [ArchiveNotSeekableException] — there is deliberately no local-copy fallback.
     */
    suspend fun openContainerChannel(container: APath<*>): SeekableByteChannel =
        OkioSeekableByteChannel(openSeekableHandle(container))

    suspend fun pumpSource(entry: ArchiveService.WriteEntry, out: OutputStream): Long {
        var count = 0L
        val stream = entry.openStream?.invoke() ?: gatewaySwitch.openInputStream(entry.source)
        stream.use { input ->
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

    companion object {
        private val TAG = logTag("Gateway", "Archive", "Context")
        const val MAX_ENTRIES = 50_000
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val UNIX_TYPE_MASK = 0xF000
        const val UNIX_TYPE_SYMLINK = 0xA000
        // FIFO, character device, block device, socket.
        val SPECIAL_UNIX_TYPES = setOf(0x1000, 0x2000, 0x6000, 0xC000)
        // Full-drain limit for password verification: below this the probe entry is read to EOF so
        // the cipher's MAC/CRC check runs; above it only the header verifier is consulted.
        const val VERIFY_DRAIN_LIMIT = 1L * 1024 * 1024
    }
}
