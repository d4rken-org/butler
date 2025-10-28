package eu.darken.butler.editor.core.sources

import eu.darken.butler.editor.core.engine.FileInfo
import kotlinx.coroutines.flow.StateFlow

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