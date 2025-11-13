package eu.darken.butler.editor.core.mode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.EditorBuffer
import eu.darken.butler.editor.core.engine.EditorChunk
import eu.darken.butler.editor.core.sources.EditorDataSource

/**
 * Strategy interface for editor modes (Text, Hex, etc.).
 *
 * Each mode defines:
 * - How to load chunks from raw data (with or without UTF-8 conversion)
 * - How to save chunks back to raw data
 * - What buffer implementation to use
 * - How to render the editor UI
 * - What capabilities are available
 */
interface EditorMode {
    /** Type of this mode */
    val type: EditorModeType

    /** Capabilities available in this mode */
    val capabilities: EditorCapabilities

    /**
     * Load a chunk from the data source.
     *
     * Implementations decide:
     * - Whether to decode bytes as UTF-8 (Text mode)
     * - Whether to keep raw bytes (Hex mode)
     * - What metadata to calculate (line counts, etc.)
     *
     * @param dataSource The source to read from
     * @param offset Byte offset to start reading
     * @param size Number of bytes to read
     * @return EditorChunk.Text or EditorChunk.Binary
     */
    suspend fun loadChunk(
        dataSource: EditorDataSource,
        offset: Long,
        size: Long
    ): EditorChunk

    /**
     * Save a chunk to the data source.
     *
     * Implementations decide:
     * - Whether to encode String as UTF-8 bytes (Text mode)
     * - Whether to write raw bytes (Hex mode)
     *
     * @param dataSource The destination to write to
     * @param chunk The chunk to save
     */
    suspend fun saveChunk(
        dataSource: EditorDataSource,
        chunk: EditorChunk
    )

    /**
     * Create a buffer for this mode.
     *
     * Note: This is a factory method. EditorEngine handles the actual buffer creation
     * based on mode.type since it has access to the necessary factories and dependencies.
     * This method exists for mode-specific logic but may not be directly called.
     *
     * @param chunkManager The chunk manager to use
     * @return Mode-specific buffer (ChunkedTextBuffer or ChunkedBinaryBuffer)
     */
    fun createBuffer(chunkManager: ChunkManager): EditorBuffer

    /**
     * Render the editor UI for this mode.
     *
     * Implementations provide mode-specific composables:
     * - Text mode: LazyTextEditor
     * - Hex mode: LazyHexEditor
     *
     * @param buffer The buffer to render
     * @param modifier Modifier for the composable
     */
    @Composable
    fun RenderEditor(
        buffer: EditorBuffer,
        modifier: Modifier = Modifier
    )
}
