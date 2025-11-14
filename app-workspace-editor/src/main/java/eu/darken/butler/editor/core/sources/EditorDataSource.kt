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
 * ## Architectural Role
 * DataSource is a **pure I/O layer** - it handles reading/writing bytes from storage.
 * - **Does NOT handle**: Text semantics (line endings, UTF-16 validation, etc.)
 * - **Does handle**: File I/O, memory operations, encoding/decoding
 *
 * Text-level concerns (line ending detection, UTF-16 surrogate pair protection) are
 * handled by ChunkRepository.
 *
 * Caching is handled by ChunkManager - data sources don't cache content.
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
     * Reads a chunk of content from the data source.
     *
     * @param startOffset Byte offset in the file/content where reading begins
     * @param size Number of bytes to read
     * @return String content decoded from UTF-8 bytes
     *
     * **Important**: The returned String may contain incomplete UTF-16 surrogate pairs
     * at chunk boundaries. This occurs when byte-based chunk boundaries split multi-byte
     * UTF-8 characters (like emoji). ChunkRepository is responsible for detecting and
     * handling incomplete surrogate pairs.
     *
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