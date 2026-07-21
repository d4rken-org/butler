package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.enums.CompressionMethod
import okio.FileHandle
import okio.buffer
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Positioned zip4j entry access over a gateway [FileHandle]: seeks to the entry's local file
 * header and lets zip4j parse it off the stream, overriding size/CRC with the central-directory
 * values stored in [ArchiveEntryMeta] — the same mechanism zip4j's own `ZipFile.getInputStream`
 * uses internally. This removes zip4j's `java.io.File` requirement, so encrypted entries decrypt
 * copy-free on any seekable backend (local, root/ADB, file-backed SAF).
 *
 * The returned stream owns the zip4j chain (and wipes its password copy on close) but does NOT
 * close [handle], so callers can reuse one handle across multiple entries.
 *
 * Limitation: entries whose effective compression (AES inner method for 99) is not STORE or
 * DEFLATE are rejected — zip4j would silently misdecompress them as STORE.
 */
internal object Zip4jPositionedStream {

    fun open(
        handle: FileHandle,
        container: APath<*>,
        meta: ArchiveEntryMeta,
        password: CharArray?,
    ): InputStream {
        val offset = meta.localHeaderOffset
            ?: throw ReadException("No local header offset for ${meta.rawName}", container)
        val compressedSize = meta.compressedSize
            ?: throw ReadException("No compressed size for ${meta.rawName}", container)
        val uncompressedSize = meta.size
            ?: throw ReadException("No uncompressed size for ${meta.rawName}", container)
        val crc = meta.crc
            ?: throw ReadException("No CRC for ${meta.rawName}", container)
        if (meta.rawMethod !in SUPPORTED_RAW_METHODS) {
            throw ReadException("Unsupported compression method ${meta.rawMethod} for ${meta.rawName}", container)
        }
        val containerSize = handle.size()
        if (offset < 0 || compressedSize < 0 || offset >= containerSize || offset + compressedSize > containerSize) {
            throw ReadException("Entry bounds outside container for ${meta.rawName}", container)
        }

        val passwordCopy = password?.copyOf()
        // Kept separately: zip4j's ZipInputStream.close() only closes its decompressed stream,
        // which isn't assigned yet if getNextEntry() fails (e.g. wrong password at cipher init) -
        // closing rawIn explicitly prevents leaking the positioned source on every failed attempt.
        val rawIn = handle.source(offset).buffer().inputStream()
        val zipIn = ZipInputStream(rawIn, passwordCopy)
        try {
            val central = FileHeader().apply {
                fileName = meta.rawName
                this.crc = crc
                this.compressedSize = compressedSize
                this.uncompressedSize = uncompressedSize
                isDirectory = false
            }
            val local = zipIn.getNextEntry(central, false)
                ?: throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
            when {
                local.fileName != meta.rawName ->
                    throw ReadException("Local header name mismatch for ${meta.rawName}", container)
                local.isEncrypted != meta.isEncrypted ->
                    throw ReadException("Local/central encryption mismatch for ${meta.rawName}", container)
                local.compressionMethod.code != meta.rawMethod ->
                    throw ReadException("Local/central method mismatch for ${meta.rawName}", container)
            }
            val effective = local.aesExtraDataRecord?.compressionMethod ?: local.compressionMethod
            if (effective != CompressionMethod.STORE && effective != CompressionMethod.DEFLATE) {
                throw ReadException("Unsupported effective compression $effective for ${meta.rawName}", container)
            }
            return object : FilterInputStream(zipIn) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { rawIn.close() }
                        passwordCopy?.fill('\u0000')
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { zipIn.close() }
            runCatching { rawIn.close() }
            passwordCopy?.fill('\u0000')
            throw e
        }
    }

    private val SUPPORTED_RAW_METHODS = setOf(
        CompressionMethod.STORE.code,
        CompressionMethod.DEFLATE.code,
        CompressionMethod.AES_INTERNAL_ONLY.code,
    )
}
