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
import eu.darken.butler.editor.core.engine.FileInfo
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

    // Modified chunks cached in memory (offset -> content)
    private val modifiedChunks = mutableMapOf<Long, ByteArray>()
    private val accessMutex = Mutex()

    suspend fun initialize(): Result<Unit> {
        log(tag) { "Initializing file data source: $filePath" }
        return try {
            if (!filePath.exists(gatewaySwitch)) {
                return Result.failure(IllegalArgumentException("File does not exist: $filePath"))
            }

            val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)

            _fileInfo.value = FileInfo(
                path = filePath,
                size = lookup.size!!,
                lastModified = lookup.modifiedAt!!,
                canWrite = true // We'll assume writable for now
            )

            log(tag) { "Initialized FileDataSource without loading content (${lookup.size} bytes)" }
            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to initialize - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    override suspend fun readChunk(startOffset: Long, size: Long): String = accessMutex.withLock {
        withContext(Dispatchers.IO) {
            // Check modified chunks first (unsaved edits take precedence)
            modifiedChunks[startOffset]?.let {
                return@withContext String(it, Charsets.UTF_8)
            }

            // Check if offset is beyond file size
            val fileSize = _fileInfo.value?.size ?: 0L
            if (startOffset >= fileSize) {
                return@withContext ""
            }

            // Read from file
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

    override suspend fun writeChunk(startOffset: Long, content: String): Unit = accessMutex.withLock {
        val bytes = content.toByteArray(Charsets.UTF_8)
        modifiedChunks[startOffset] = bytes
        _isModified.value = true

        log(tag) { "Cached write at offset $startOffset (${bytes.size} bytes)" }
    }

    override suspend fun getSize(): Long = _fileInfo.value?.size ?: 0L

    override suspend fun save() = accessMutex.withLock {
        withContext(Dispatchers.IO) {
            if (modifiedChunks.isEmpty()) {
                log(tag) { "No modifications to save" }
                return@withContext
            }

            try {
                log(tag) { "Saving ${modifiedChunks.size} modified chunks" }

                // Read original file into memory
                val originalContent = gatewaySwitch.file(filePath, readWrite = false).use { handle ->
                    handle.source().buffer().use { source ->
                        source.readByteArray()
                    }
                }

                // Apply modifications
                val modifiedContent = originalContent.toMutableList()
                for ((offset, bytes) in modifiedChunks.toSortedMap()) {
                    val offsetInt = offset.toInt()
                    // Replace bytes at offset (ensure we don't exceed bounds)
                    for (i in bytes.indices) {
                        val pos = offsetInt + i
                        if (pos < modifiedContent.size) {
                            modifiedContent[pos] = bytes[i]
                        } else {
                            modifiedContent.add(bytes[i])
                        }
                    }
                }

                // Write back to file
                gatewaySwitch.file(filePath, readWrite = true).use { handle ->
                    handle.sink().buffer().use { sink ->
                        sink.write(modifiedContent.toByteArray())
                    }
                }

                // Clear modifications
                modifiedChunks.clear()
                _isModified.value = false

                // Update file info with new size
                val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)
                _fileInfo.value = FileInfo(
                    path = filePath,
                    size = lookup.size!!,
                    lastModified = lookup.modifiedAt!!,
                    canWrite = true
                )

                log(tag) { "Successfully saved modifications" }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to save - ${e.asLog()}" }
                throw e
            }
        }
    }

    override suspend fun close() = accessMutex.withLock {
        modifiedChunks.clear()
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