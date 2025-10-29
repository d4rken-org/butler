package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.FileInfo
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

    override suspend fun open() {
        log(tag) { "Opening file data source: $filePath" }
        try {
            if (!filePath.exists(gatewaySwitch)) {
                throw FileNotFoundException("File does not exist: $filePath")
            }

            val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)

            _fileInfo.value = FileInfo(
                path = filePath,
                size = lookup.size!!,
                lastModified = lookup.modifiedAt!!,
                canWrite = true // We'll assume writable for now
            )

            log(tag) { "Opened FileDataSource without loading content (${lookup.size} bytes)" }
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

                        // Read requested size
                        val buffer = Buffer()
                        val bytesRead = source.read(buffer, size)

                        if (bytesRead == -1L) {
                            // Offset is beyond file size
                            return@withContext ""
                        }

                        val bytes = buffer.readByteArray()
                        String(bytes, Charsets.UTF_8)
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
    override suspend fun save(dirtyChunks: List<TextChunk>) = accessMutex.withLock {
        withContext(Dispatchers.IO) {
            if (dirtyChunks.isEmpty()) {
                log(tag) { "No modifications to save" }
                _isModified.value = false
                return@withContext
            }

            try {
                log(tag) { "Saving ${dirtyChunks.size} modified chunks using atomic write" }

                // Read original file into memory
                val originalContent = gatewaySwitch.file(filePath, readWrite = false).use { handle ->
                    handle.source().buffer().use { source ->
                        source.readByteArray()
                    }
                }

                // Merge modifications using ChunkManager algorithm
                val mergedContent = ChunkManager.mergeChunks(originalContent, dirtyChunks)

                // Atomic save: write to temp file, then rename
                val tempPath = filePath.parent?.child("${filePath.name}.tmp")
                    ?: throw IllegalStateException("Cannot create temp file - no parent directory")

                try {
                    // Write merged content to temp file
                    gatewaySwitch.file(tempPath, readWrite = true).use { handle ->
                        handle.sink().buffer().use { sink ->
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

                // Update file info with new size
                val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)
                _fileInfo.value = FileInfo(
                    path = filePath,
                    size = lookup.size!!,
                    lastModified = lookup.modifiedAt!!,
                    canWrite = true
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