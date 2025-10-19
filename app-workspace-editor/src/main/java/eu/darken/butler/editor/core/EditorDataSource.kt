package eu.darken.butler.editor.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.buffer
import okio.use
import kotlin.time.Instant

/**
 * Data source interface for editor content.
 * Supports both file-based and in-memory editing.
 */
interface EditorDataSource {
    val fileInfo: StateFlow<FileInfo?>
    val isModified: StateFlow<Boolean>
    
    suspend fun readChunk(startOffset: Long, size: Long): Result<String>
    suspend fun writeChunk(startOffset: Long, content: String): Result<Unit>
    suspend fun getSize(): Long
    suspend fun save(): Result<Unit>
    suspend fun close(): Result<Unit>
}

/**
 * File-based data source implementation.
 */
class FileDataSource @AssistedInject constructor(
    @Assisted private val filePath: APath<*>,
    @Assisted private val gatewaySwitch: GatewaySwitch
) : EditorDataSource {
    
    private val _fileInfo = MutableStateFlow<FileInfo?>(null)
    override val fileInfo: StateFlow<FileInfo?> = _fileInfo.asStateFlow()
    
    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()
    
    private var fileContent: String = ""
    
    suspend fun initialize(): Result<Unit> {
        return try {
            if (!filePath.exists(gatewaySwitch)) {
                return Result.failure(IllegalArgumentException("File does not exist: $filePath"))
            }
            
            val lookup = filePath.lookup(gatewaySwitch)
            val content = gatewaySwitch.file(filePath, readWrite = false).use { handle ->
                handle.source().buffer().use { source ->
                    source.readByteArray()
                }
            }
            
            fileContent = String(content)
            _fileInfo.value = FileInfo(
                path = filePath,
                size = lookup.size ?: 0L,
                lastModified = lookup.modifiedAt ?: Instant.DISTANT_PAST,
                canWrite = true // We'll assume writable for now
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun readChunk(startOffset: Long, size: Long): Result<String> {
        return try {
            val endOffset = (startOffset + size).coerceAtMost(fileContent.length.toLong())
            val chunk = fileContent.substring(
                startOffset.toInt().coerceIn(0, fileContent.length),
                endOffset.toInt().coerceIn(0, fileContent.length)
            )
            Result.success(chunk)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun writeChunk(startOffset: Long, content: String): Result<Unit> {
        return try {
            val before = fileContent.substring(0, startOffset.toInt().coerceIn(0, fileContent.length))
            val after = fileContent.substring(startOffset.toInt().coerceIn(0, fileContent.length))
            fileContent = before + content + after
            _isModified.value = true
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getSize(): Long = fileContent.length.toLong()
    
    override suspend fun save(): Result<Unit> {
        return try {
            gatewaySwitch.file(filePath, readWrite = true).use { handle ->
                handle.sink().buffer().use { sink ->
                    sink.write(fileContent.toByteArray())
                }
            }
            _isModified.value = false
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun close(): Result<Unit> {
        fileContent = ""
        _fileInfo.value = null
        _isModified.value = false
        return Result.success(Unit)
    }
}

/**
 * In-memory data source implementation for new/unsaved documents.
 */
class InMemoryDataSource @AssistedInject constructor(
    @Assisted private val initialContent: String
) : EditorDataSource {
    
    override val fileInfo: StateFlow<FileInfo?> = MutableStateFlow(null)
    
    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()
    
    private var content: String = initialContent
    
    override suspend fun readChunk(startOffset: Long, size: Long): Result<String> {
        return try {
            val endOffset = (startOffset + size).coerceAtMost(content.length.toLong())
            val chunk = content.substring(
                startOffset.toInt().coerceIn(0, content.length),
                endOffset.toInt().coerceIn(0, content.length)
            )
            Result.success(chunk)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun writeChunk(startOffset: Long, content: String): Result<Unit> {
        return try {
            val before = this.content.substring(0, startOffset.toInt().coerceIn(0, this.content.length))
            val after = this.content.substring(startOffset.toInt().coerceIn(0, this.content.length))
            this.content = before + content + after
            _isModified.value = this.content != initialContent
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getSize(): Long = content.length.toLong()
    
    override suspend fun save(): Result<Unit> {
        // In-memory content can't be saved without a file path
        return Result.failure(UnsupportedOperationException("Cannot save in-memory content without a file path"))
    }
    
    override suspend fun close(): Result<Unit> {
        content = ""
        _isModified.value = false
        return Result.success(Unit)
    }
    
    fun getContent(): String = content
    
    fun setContent(newContent: String) {
        content = newContent
        _isModified.value = content != initialContent
    }
}