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
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.editor.core.engine.ChunkBoundary
import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.ContentSource
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
import kotlin.uuid.Uuid

/**
 * File-based data source implementation.
 */
class FileDataSource @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val filePath: APath<*>,
    @Assisted private val gatewaySwitch: GatewaySwitch
) : EditorDataSource {
    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "DataSource", "File")
    private val _contentSource = MutableStateFlow<ContentSource>(ContentSource.Memory(size = 0L))
    override val contentSource: StateFlow<ContentSource> = _contentSource.asStateFlow()

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

            _contentSource.value = ContentSource.File(
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
            val source = _contentSource.value as? ContentSource.File
            val fileSize = source?.size ?: 0L
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

                        // Get detected charset from ContentSource.File
                        val fileSource = _contentSource.value as? ContentSource.File
                        val charset = fileSource?.detectedCharset ?: Charsets.UTF_8

                        // If this is the first chunk (offset 0) and file has BOM, skip BOM bytes
                        val (contentBytes, skippedBOM) = if (startOffset == 0L && fileSource?.hasBOM == true && fileSource.bomBytes != null) {
                            val bomSize = fileSource.bomBytes.size
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
                            log(tag) { "Skipped ${fileSource?.bomBytes?.size} byte BOM in first chunk" }
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

    override suspend fun getSize(): Long = _contentSource.value.size

    /**
     * Saves dirty chunks to the file without risking the original on failure.
     *
     * Local paths use a backup-swap: the original is renamed aside, the new content (written to a
     * uniquely-named temp) is renamed into place, and the backup is removed only after the commit
     * succeeds. On any failure the original is restored from the backup. SAF paths have no rename
     * primitive, so the original bytes are copied to a uniquely-named backup, the document is
     * overwritten in place, and the original is restored from the backup if the write fails.
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
                    val fileSource = _contentSource.value as? ContentSource.File
                        ?: error("ContentSource.File not initialized")

                    // Read original file into memory
                    val originalBytes = gatewaySwitch.file(filePath, readWrite = false).use { handle ->
                        handle.source().buffer().use { source ->
                            source.readByteArray()
                        }
                    }

                    // Strip BOM from original content before merging (we'll restore it separately)
                    val originalContent = if (fileSource.hasBOM && fileSource.bomBytes != null) {
                        originalBytes.drop(fileSource.bomBytes.size).toByteArray()
                    } else {
                        originalBytes
                    }

                    // Merge modifications using ChunkManager algorithm with original charset
                    val mergedContent = ChunkManager.mergeChunks(
                        originalContent,
                        dirtyChunks,
                        boundaries,
                        charset = fileSource.detectedCharset
                    )

                    // BOM to prepend when writing the new content (restored verbatim from the original).
                    val bom = if (fileSource.hasBOM) fileSource.bomBytes else null

                    // Unique per-save artifact names so we never collide with or delete a user's own
                    // files, and never touch artifacts from a different (e.g. crashed) save.
                    val token = Uuid.random().toString().take(8)
                    val parent = filePath.parent
                        ?: throw IllegalStateException("Cannot save - no parent directory")
                    val backupPath = parent.child("${filePath.name}.butler-save-bak-$token")

                    if (filePath is LocalPath) {
                        val tempPath = parent.child("${filePath.name}.butler-save-tmp-$token")
                        commitViaBackupSwap(tempPath, backupPath, bom, mergedContent)
                    } else {
                        commitViaInPlace(backupPath, bom, mergedContent, originalBytes)
                    }

                    log(tag) { "Saved ${mergedContent.size} bytes" }

                    // Update state
                    _isModified.value = false

                    // Update content source with new size (preserve charset and BOM)
                    val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)
                    _contentSource.value = ContentSource.File(
                        path = filePath,
                        size = lookup.size!!,
                        lastModified = lookup.modifiedAt!!,
                        canWrite = true,
                        lineEnding = fileSource.lineEnding, // Preserve line ending
                        detectedCharset = fileSource.detectedCharset, // Preserve charset
                        hasBOM = fileSource.hasBOM, // Preserve BOM flag
                        bomBytes = fileSource.bomBytes // Preserve BOM bytes
                    )

                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to save - ${e.asLog()}" }
                    throw e
                }
            }
        }

    /**
     * Writes [bom] (if any) followed by [body] to [target], truncating any existing content first and
     * flushing to disk so the result is durable before it is treated as a recovery copy.
     */
    private suspend fun writeContent(target: APath<*>, bom: ByteArray?, body: ByteArray) {
        gatewaySwitch.file(target, readWrite = true).use { handle ->
            handle.resize(0)
            handle.sink().buffer().use { sink ->
                if (bom != null) sink.write(bom)
                sink.write(body)
                sink.flush()
            }
            handle.flush()
        }
    }

    /** Best-effort removal of a save artifact; logs (never throws) if the delete fails or returns false. */
    private suspend fun cleanupArtifact(path: APath<*>) {
        runCatching {
            if (path.exists(gatewaySwitch) && !gatewaySwitch.delete(path)) {
                log(tag, WARN) { "Failed to delete leftover save artifact: $path" }
            }
        }.onFailure { log(tag, WARN) { "Error cleaning up save artifact $path: ${it.asLog()}" } }
    }

    /**
     * Local-path commit: rename the original aside, rename the freshly-written temp into place, and
     * drop the backup only after the commit succeeds. Restores the original on failure and always
     * cleans up the temp artifact.
     */
    internal suspend fun commitViaBackupSwap(
        tempPath: APath<*>,
        backupPath: APath<*>,
        bom: ByteArray?,
        mergedContent: ByteArray,
    ) {
        var backedUp = false
        try {
            writeContent(tempPath, bom, mergedContent)
            if (filePath.exists(gatewaySwitch)) {
                check(gatewaySwitch.move(filePath, backupPath)) { "Backup move failed: $filePath -> $backupPath" }
                backedUp = true
            }
            check(gatewaySwitch.move(tempPath, filePath)) { "Commit move failed: $tempPath -> $filePath" }
        } catch (e: Exception) {
            // Moves are atomic, so a failure here means the commit never landed and the original path
            // is free; if we had moved the original aside, put it back.
            if (backedUp) {
                try {
                    check(gatewaySwitch.move(backupPath, filePath)) { "Restore move returned false" }
                    backedUp = false
                } catch (restoreError: Exception) {
                    log(tag, ERROR) { "Restore failed - original preserved at $backupPath: ${restoreError.asLog()}" }
                    e.addSuppressed(restoreError)
                }
            }
            cleanupArtifact(tempPath)
            throw e
        }

        cleanupArtifact(backupPath)
    }

    /**
     * SAF/non-local commit: no rename primitive is available, so copy the original bytes to a backup,
     * overwrite the document in place, and restore from the backup on failure. Not atomic - process
     * death mid-write can leave the file partial with the original preserved in [backupPath].
     */
    internal suspend fun commitViaInPlace(
        backupPath: APath<*>,
        bom: ByteArray?,
        mergedContent: ByteArray,
        originalBytes: ByteArray,
    ) {
        var backupReady = false
        try {
            gatewaySwitch.createFile(backupPath, createParents = false)
            writeContent(backupPath, bom = null, body = originalBytes)
            backupReady = true
            writeContent(filePath, bom, mergedContent)
        } catch (e: Exception) {
            if (backupReady) {
                // The in-place overwrite failed; the original may be partial. Restore it, and keep the
                // backup as a recovery copy if the restore also fails.
                runCatching { writeContent(filePath, bom = null, body = originalBytes) }
                    .onFailure {
                        log(tag, ERROR) { "Restore failed - original preserved at $backupPath: ${it.asLog()}" }
                        e.addSuppressed(it)
                    }
            } else {
                // Backup never completed; the original was not touched, so the partial backup is junk.
                cleanupArtifact(backupPath)
            }
            throw e
        }

        cleanupArtifact(backupPath)
    }

    override suspend fun close() = accessMutex.withLock {
        _contentSource.value = ContentSource.Memory(size = 0L)
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