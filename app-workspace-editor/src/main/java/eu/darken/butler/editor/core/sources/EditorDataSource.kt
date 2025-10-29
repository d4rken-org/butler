package eu.darken.butler.editor.core.sources

import eu.darken.butler.editor.core.engine.FileInfo
import kotlinx.coroutines.flow.StateFlow
import okio.Source
import java.io.FileNotFoundException

/**
 * Data source interface for editor content.
 * Supports both file-based and in-memory editing.
 */
interface EditorDataSource {
    val fileInfo: StateFlow<FileInfo?>
    val isModified: StateFlow<Boolean>

    /**
     * Opens the data source and prepares it for reading/writing.
     * @throws IOException if the resource cannot be opened
     * @throws FileNotFoundException if the resource doesn't exist
     */
    suspend fun open()

    suspend fun readChunk(startOffset: Long, size: Long): String
    suspend fun writeChunk(startOffset: Long, content: String)
    suspend fun getSize(): Long
    suspend fun save()
    suspend fun close()

    /**
     * Opens a source for streaming the complete content.
     * Caller is responsible for closing the Source.
     */
    suspend fun openSource(): Source
}