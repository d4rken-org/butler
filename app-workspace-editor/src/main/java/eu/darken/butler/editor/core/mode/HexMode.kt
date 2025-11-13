package eu.darken.butler.editor.core.mode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.EditorBuffer
import eu.darken.butler.editor.core.engine.EditorChunk
import eu.darken.butler.editor.core.sources.EditorDataSource

/**
 * Hex editing mode.
 *
 * Loads file content as raw bytes and provides hex editor operations.
 * Does not decode content or calculate line information.
 */
class HexMode : EditorMode {

    override val type = EditorModeType.HEX

    override val capabilities = EditorCapabilities(
        canEdit = true,
        canSearch = true,
        canUndo = true,
        canGoToLine = false,      // Hex mode doesn't have lines
        canGoToOffset = true,     // Hex mode uses byte offsets
        canShowLineNumbers = false
    )

    override suspend fun loadChunk(
        dataSource: EditorDataSource,
        offset: Long,
        size: Long
    ): EditorChunk {
        log(TAG, DEBUG) { "loadChunk(offset=$offset, size=$size)" }

        // Read content from data source as raw bytes
        val bytes = dataSource.readChunk(offset, size)

        log(TAG, DEBUG) { "Loaded binary chunk: ${bytes.size} bytes" }

        return EditorChunk.Binary(
            offset = offset,
            content = bytes,
            size = bytes.size.toLong(),
            isDirty = false
        )
    }

    override suspend fun saveChunk(
        dataSource: EditorDataSource,
        chunk: EditorChunk
    ) {
        require(chunk is EditorChunk.Binary) {
            "HexMode can only save Binary chunks, got ${chunk::class.simpleName}"
        }

        log(TAG, DEBUG) { "saveChunk(offset=${chunk.offset}, size=${chunk.size})" }

        // Write raw bytes to data source
        dataSource.writeChunk(chunk.offset, chunk.content)

        log(TAG, DEBUG) { "Saved ${chunk.content.size} bytes at offset ${chunk.offset}" }
    }

    override fun createBuffer(chunkManager: ChunkManager): EditorBuffer {
        // HexMode uses ChunkedBinaryBuffer
        // Note: This requires a BinaryChunkRepository instance
        // In practice, this will be called by EditorEngine which has access to the repository
        // For now, we throw an error indicating this method needs repository parameter
        throw UnsupportedOperationException(
            "createBuffer() requires BinaryChunkRepository parameter. " +
            "Use EditorEngine to create hex buffers with proper repository injection."
        )
    }

    @Composable
    override fun RenderEditor(buffer: EditorBuffer, modifier: Modifier) {
        // Will be implemented in Phase 4
        TODO("Hex editor UI will be implemented in Phase 4")
    }

    companion object {
        private val TAG = logTag("Editor", "HexMode")
    }
}
