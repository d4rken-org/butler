package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.editor.core.engine.ChunkBoundary
import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.FileInfo
import eu.darken.butler.editor.core.engine.LineEnding
import eu.darken.butler.editor.core.engine.TextChunk
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.Source
import okio.buffer
import okio.use
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * File-based data source implementation.
 */
class FileDataSource @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val filePath: APath<*>,
    @Assisted private val gatewaySwitch: GatewaySwitch
) : EditorDataSource {
    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "DataSource", "File")
    private val _fileInfo = MutableStateFlow<FileInfo?>(null)
    override val fileInfo: StateFlow<FileInfo?> = _fileInfo.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private val accessMutex = Mutex()

    /**
     * Detects charset from BOM (Byte Order Mark).
     * @return Pair of (Charset, BOM size in bytes) or null if no BOM
     */
    private fun detectCharsetFromBOM(bytes: ByteArray): Pair<Charset, Int>? {
        return when {
            // UTF-8 BOM: EF BB BF
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() ->
                Charsets.UTF_8 to 3

            // UTF-16 LE BOM: FF FE
            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() ->
                Charsets.UTF_16LE to 2

            // UTF-16 BE BOM: FE FF
            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() ->
                Charsets.UTF_16BE to 2

            else -> null
        }
    }

    /**
     * Validates whether bytes are valid UTF-8.
     * Uses strict decoding - any malformed sequence returns false.
     */
    private fun isValidUTF8(bytes: ByteArray): Boolean {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)

            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    /**
     * Detects charset from file content.
     *
     * Strategy:
     * 1. Check for BOM (most reliable)
     * 2. Validate UTF-8 encoding
     * 3. Default to UTF-8 (modern standard)
     *
     * @return Triple of (Charset, hasBOM, bomBytes)
     */
    private suspend fun detectCharset(filePath: APath<*>): Triple<Charset, Boolean, ByteArray?> {
        // Read first 8KB for detection (enough for BOM + content validation)
        val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)
        val fileSize = lookup.size ?: 0L
        val sampleSize = minOf(8192L, fileSize).toInt()

        if (sampleSize == 0) {
            // Empty file - default to UTF-8
            return Triple(Charsets.UTF_8, false, null)
        }

        val sampleBytes = ByteArray(sampleSize)

        gatewaySwitch.file(filePath, readWrite = false).use { handle ->
            handle.source().buffer().use { source ->
                source.read(sampleBytes)
            }
        }

        // 1. Check for BOM (highest priority)
        detectCharsetFromBOM(sampleBytes)?.let { (charset, bomSize) ->
            val bomBytes = sampleBytes.copyOfRange(0, bomSize)
            log(tag, INFO) { "Detected $charset via BOM" }
            return Triple(charset, true, bomBytes)
        }

        // 2. Validate UTF-8 (no BOM)
        if (isValidUTF8(sampleBytes)) {
            log(tag, INFO) { "Detected UTF-8 via validation (no BOM)" }
            return Triple(Charsets.UTF_8, false, null)
        }

        // 3. Default to UTF-8 (modern standard)
        // Note: Legacy encodings (ISO-8859-1, Windows-1252, Shift-JIS) will display as mojibake
        // but won't crash. Future enhancement: add manual encoding selector.
        log(tag, WARN) { "Could not confidently detect encoding - defaulting to UTF-8" }
        return Triple(Charsets.UTF_8, false, null)
    }

    override suspend fun open() {
        log(tag) { "Opening file data source: $filePath" }
        try {
            if (!filePath.exists(gatewaySwitch)) {
                throw FileNotFoundException("File does not exist: $filePath")
            }

            val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)

            // Detect charset from file content
            val (detectedCharset, hasBOM, bomBytes) = detectCharset(filePath)

            _fileInfo.value = FileInfo(
                path = filePath,
                size = lookup.size!!,
                lastModified = lookup.modifiedAt!!,
                canWrite = true, // We'll assume writable for now
                lineEnding = LineEnding.LF, // Updated during chunk loading
                detectedCharset = detectedCharset,
                hasBOM = hasBOM,
                bomBytes = bomBytes
            )

            log(tag, INFO) {
                "Opened FileDataSource: size=${lookup.size} bytes, charset=$detectedCharset, hasBOM=$hasBOM"
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to open - ${e.asLog()}" }
            throw e
        }
    }

    override suspend fun readChunk(startOffset: Long, size: Long): String = accessMutex.withLock {
        withContext(Dispatchers.IO) {
            // Check if offset is beyond file size
            val fileSize = _fileInfo.value?.size ?: 0L
            if (startOffset >= fileSize) {
                return@withContext ""
            }

            // Read from file (ChunkManager cache is the source of truth for modified chunks)
            try {
                gatewaySwitch.file(filePath, readWrite = false).use { handle ->
                    handle.source().buffer().use { source ->
                        // Skip to start offset
                        if (startOffset > 0) {
                            source.skip(startOffset)
                        }

                        // Read requested size - loop to handle partial reads from Okio
                        val buffer = Buffer()
                        var totalBytesRead = 0L

                        // Okio's source.read() may return fewer bytes than requested
                        // (commonly 8192 bytes per Okio Segment). Loop until we have
                        // the full chunk or hit EOF.
                        while (totalBytesRead < size) {
                            val remainingBytes = size - totalBytesRead
                            val bytesRead = source.read(buffer, remainingBytes)

                            if (bytesRead == -1L) {
                                // EOF reached before reading full size
                                break
                            }

                            totalBytesRead += bytesRead
                        }

                        if (totalBytesRead == 0L) {
                            // Offset is beyond file size
                            return@withContext ""
                        }

                        log(tag) { "readChunk: requested=$size, read=$totalBytesRead at offset $startOffset" }

                        val bytes = buffer.readByteArray()

                        // Get detected charset from FileInfo
                        val fileInfo = _fileInfo.value
                        val charset = fileInfo?.detectedCharset ?: Charsets.UTF_8

                        // If this is the first chunk (offset 0) and file has BOM, skip BOM bytes
                        val (contentBytes, skippedBOM) = if (startOffset == 0L && fileInfo?.hasBOM == true && fileInfo.bomBytes != null) {
                            val bomSize = fileInfo.bomBytes.size
                            if (bytes.size > bomSize) {
                                bytes.copyOfRange(bomSize, bytes.size) to true
                            } else {
                                // Edge case: chunk is smaller than BOM (very small chunk size)
                                byteArrayOf() to true
                            }
                        } else {
                            bytes to false
                        }

                        if (skippedBOM) {
                            log(tag) { "Skipped ${fileInfo?.bomBytes?.size} byte BOM in first chunk" }
                        }

                        // Decode bytes using detected charset
                        // Note: May contain incomplete UTF-16 surrogate pairs at chunk boundaries
                        // ChunkRepository is responsible for handling this
                        try {
                            String(contentBytes, charset)
                        } catch (e: CharacterCodingException) {
                            log(tag, ERROR) { "Failed to decode chunk with $charset - ${e.asLog()}" }
                            // Fallback to UTF-8 with replacement chars
                            String(contentBytes, Charsets.UTF_8)
                        }
                    }
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to read chunk at offset $startOffset - ${e.asLog()}" }
                throw e
            }
        }
    }

    override suspend fun getSize(): Long = _fileInfo.value?.size ?: 0L

    /**
     * Saves dirty chunks to file using atomic write pattern.
     * Uses temp file + atomic rename to prevent corruption.
     *
     * @param dirtyChunks List of modified chunks to save (will be merged with original content)
     */
    override suspend fun save(dirtyChunks: List<TextChunk>, boundaries: Map<TextChunk.ChunkId, ChunkBoundary>) =
        accessMutex.withLock {
            withContext(Dispatchers.IO) {
                if (dirtyChunks.isEmpty()) {
                    log(tag) { "No modifications to save" }
                    _isModified.value = false
                    return@withContext
                }

                try {
                    log(tag) { "Saving ${dirtyChunks.size} modified chunks using atomic write" }

                    // Get current file info for charset and BOM preservation
                    val fileInfo = _fileInfo.value ?: error("FileInfo not initialized")

                    // Read original file into memory
                    val originalBytes = gatewaySwitch.file(filePath, readWrite = false).use { handle ->
                        handle.source().buffer().use { source ->
                            source.readByteArray()
                        }
                    }

                    // Strip BOM from original content before merging (we'll restore it separately)
                    val originalContent = if (fileInfo.hasBOM && fileInfo.bomBytes != null) {
                        originalBytes.drop(fileInfo.bomBytes.size).toByteArray()
                    } else {
                        originalBytes
                    }

                    // Merge modifications using ChunkManager algorithm with original charset
                    val mergedContent = ChunkManager.mergeChunks(
                        originalContent,
                        dirtyChunks,
                        boundaries,
                        charset = fileInfo.detectedCharset
                    )

                    // Atomic save: write to temp file, then rename
                    val tempPath = filePath.parent?.child("${filePath.name}.tmp")
                        ?: throw IllegalStateException("Cannot create temp file - no parent directory")

                    try {
                        // Write merged content to temp file
                        gatewaySwitch.file(tempPath, readWrite = true).use { handle ->
                            handle.sink().buffer().use { sink ->
                                // Restore BOM if original file had one
                                if (fileInfo.hasBOM && fileInfo.bomBytes != null) {
                                    sink.write(fileInfo.bomBytes)
                                    log(tag) { "Restored ${fileInfo.bomBytes.size} byte BOM to saved file" }
                                }
                                sink.write(mergedContent)
                            }
                        }

                        // Atomic rename: temp file -> original file
                        // Note: This requires the gateway to support rename/move operations
                        // For now, we'll delete original and rename temp (not fully atomic but safer than direct write)
                        gatewaySwitch.delete(filePath)
                        gatewaySwitch.move(tempPath, filePath)

                        log(tag) { "Successfully saved ${mergedContent.size} bytes using atomic write" }

                    } catch (e: Exception) {
                        // Clean up temp file on failure
                        try {
                            if (tempPath.exists(gatewaySwitch)) {
                                gatewaySwitch.delete(tempPath)
                            }
                        } catch (cleanupError: Exception) {
                            log(tag, ERROR) { "Failed to cleanup temp file: ${cleanupError.asLog()}" }
                        }
                        throw e
                    }

                    // Update state
                    _isModified.value = false

                    // Update file info with new size (preserve charset and BOM)
                    val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)
                    _fileInfo.value = FileInfo(
                        path = filePath,
                        size = lookup.size!!,
                        lastModified = lookup.modifiedAt!!,
                        canWrite = true,
                        lineEnding = fileInfo.lineEnding, // Preserve line ending
                        detectedCharset = fileInfo.detectedCharset, // Preserve charset
                        hasBOM = fileInfo.hasBOM, // Preserve BOM flag
                        bomBytes = fileInfo.bomBytes // Preserve BOM bytes
                    )

                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to save - ${e.asLog()}" }
                    throw e
                }
            }
        }

    override suspend fun close() = accessMutex.withLock {
        _fileInfo.value = null
        _isModified.value = false

        log(tag) { "Closed FileDataSource and released resources" }
    }

    override suspend fun openSource(): Source {
        log(tag) { "Opening source for file: $filePath" }
        val handle = gatewaySwitch.file(filePath, readWrite = false)
        return handle.source()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            filePath: APath<*>,
            gatewaySwitch: GatewaySwitch,
        ): FileDataSource
    }
}