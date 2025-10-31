package eu.darken.butler.editor.core.sources

import eu.darken.butler.editor.core.engine.ChunkBoundary
import eu.darken.butler.editor.core.engine.FileInfo
import eu.darken.butler.editor.core.engine.TextChunk
import kotlinx.coroutines.flow.StateFlow
import okio.Source
import java.io.FileNotFoundException

/**
 * Data source interface for editor content.
 * Supports both file-based and in-memory editing.
 *
 * Caching is handled by ChunkManager - data sources are pure I/O layers.
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

    /**
     * Reads a chunk from the data source.
     * ChunkManager cache is the source of truth for modified chunks.
     */
    suspend fun readChunk(startOffset: Long, size: Long): String

    suspend fun getSize(): Long

    /**
     * Saves dirty chunks to the data source.
     * ChunkManager passes all dirty chunks; data source merges and persists.
     *
     * @param dirtyChunks List of modified chunks to save
     * @param boundaries Map of chunk IDs to their file positions
     */
    suspend fun save(dirtyChunks: List<TextChunk>, boundaries: Map<TextChunk.ChunkId, ChunkBoundary>)

    suspend fun close()

    /**
     * Opens a source for streaming the complete content.
     * Caller is responsible for closing the Source.
     */
    suspend fun openSource(): Source
}