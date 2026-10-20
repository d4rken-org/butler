package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.MAX_ENTRIES
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.time.Instant

/** TAR, TAR_GZ and TAR_BZ2: forward-only, so every read is a pass over the (decompressed) container stream. */
internal class TarArchiveHandler(private val context: ArchiveHandlerContext) : ArchiveHandler {

    private val gatewaySwitch: GatewaySwitch get() = context.gatewaySwitch

    override val capabilities: ArchiveCapabilities = CAPABILITIES

    override val mayDecryptUnflaggedEntries: Boolean = false

    override suspend fun scan(container: APath<*>, format: ArchiveFormat): ScanResult = scanTar(container, format)

    override suspend fun openEntry(container: APath<*>, index: ArchiveIndex, meta: ArchiveEntryMeta): InputStream =
        openTarEntry(container, index.format, meta)

    override suspend fun useEntryStreams(
        container: APath<*>,
        index: ArchiveIndex,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    ) = useTarEntryStreams(container, index.format, entries, action)

    /** Tar has no encryption; building the index is the only check. */
    override suspend fun verifyPassword(
        container: APath<*>,
        password: CharArray,
        index: suspend () -> ArchiveIndex,
    ): Boolean {
        index()
        return true
    }

    override val writer: ArchiveWriter = ArchiveWriter { out, options, entries, onEntry ->
        writeTarEntries(out, options, entries, onEntry)
    }

    override val sequentialExtractor: SequentialExtractor? = null

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
        else -> throw IllegalArgumentException("$format is not stream-decompressed")
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

    private suspend fun writeTarEntries(
        rawOut: java.io.OutputStream,
        options: ArchiveWriteOptions,
        entries: List<ArchiveService.WriteEntry>,
        onEntry: suspend (ArchiveService.WriteEntry, Long) -> Unit,
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
                        ?: entry.takeIf { it.openStream == null }
                            ?.let { gatewaySwitch.lookup(it.source, LookupOptions(fetchSize = true)).size }
                        ?: throw WriteException("Size unknown for tar entry", entry.source)
                }
                tar.putArchiveEntry(tarEntry)
                val written = if (entry.isDirectory) 0L else context.pumpSource(entry, tar)
                tar.closeArchiveEntry()
                onEntry(entry, written)
            }
        }
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

    companion object {
        val CAPABILITIES = ArchiveCapabilities(
            access = ArchiveCapabilities.Access.SEQUENTIAL,
            sequentialExtractionFallback = false,
            readEncryption = emptySet(),
            wrongPasswordDistinguishable = true,
            writable = true,
            writeEncryption = false,
        )
        private const val MAX_TAR_SCAN_BYTES = 4L * 1024 * 1024 * 1024
    }
}
