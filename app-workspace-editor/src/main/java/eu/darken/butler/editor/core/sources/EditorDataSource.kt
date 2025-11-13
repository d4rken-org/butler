package eu.darken.butler.editor.core.sources

import eu.darken.butler.editor.core.engine.ChunkBoundary
import eu.darken.butler.editor.core.engine.EditorChunk
import eu.darken.butler.editor.core.engine.FileInfo
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
     * Reads a chunk from the data source as raw bytes.
     * ChunkManager cache is the source of truth for modified chunks.
     * Modes (TextMode/HexMode) are responsible for decoding bytes appropriately.
     */
    suspend fun readChunk(startOffset: Long, size: Long): ByteArray

    suspend fun getSize(): Long

    /**
     * Writes raw bytes at the specified offset (for direct chunk writes).
     * Used by binary editor modes for saving modifications.
     *
     * @param offset File offset to write at
     * @param bytes Raw bytes to write
     * @throws IOException if write fails
     */
    suspend fun writeChunk(offset: Long, bytes: ByteArray)

    /**
     * Saves dirty chunks to the data source.
     * ChunkManager passes all dirty chunks; data source merges and persists.
     *
     * @param dirtyChunks List of modified chunks to save
     * @param boundaries Map of chunk IDs to their file positions
     */
    suspend fun save(dirtyChunks: List<EditorChunk>, boundaries: Map<EditorChunk.ChunkId, ChunkBoundary>)

    suspend fun close()

    /**
     * Opens a source for streaming the complete content.
     * Caller is responsible for closing the Source.
     */
    suspend fun openSource(): Source
}