package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.workspace.core.Workspace

/**
 * Repository for loading binary chunks without UTF-8 conversion.
 * Preserves all byte values (0x00-0xFF) exactly as stored.
 */
class BinaryChunkRepository @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted val dataSource: EditorDataSource,
    @Assisted private val chunkSize: Long = DEFAULT_CHUNK_SIZE
) {
    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "BinaryChunkRepo")

    /**
     * Load a binary chunk from the data source.
     *
     * @param chunkId Unique identifier for this chunk
     * @param boundary Position information (start/end offsets)
     * @return EditorChunk.Binary with raw byte content
     */
    suspend fun loadChunk(chunkId: EditorChunk.ChunkId, boundary: ChunkBoundary): EditorChunk.Binary {
        log(tag, DEBUG) {
            "Loading binary chunk $chunkId: offset=${boundary.startOffset}, " +
                    "size=${boundary.endOffset - boundary.startOffset}"
        }

        // Calculate chunk size from boundary
        val size = boundary.endOffset - boundary.startOffset

        // Read content from data source as ByteArray
        val bytes = dataSource.readChunk(boundary.startOffset, size)

        log(tag, DEBUG) { "Loaded ${bytes.size} bytes for chunk $chunkId" }

        return EditorChunk.Binary(
            offset = boundary.startOffset,
            content = bytes,
            size = bytes.size.toLong(),
            isDirty = false,
            id = chunkId
        )
    }

    /**
     * Search for a byte pattern within a chunk's content.
     *
     * @param chunk The chunk to search in
     * @param pattern The byte pattern to find
     * @return List of offsets where pattern was found (relative to chunk start)
     */
    fun searchInChunk(chunk: EditorChunk.Binary, pattern: ByteArray): List<Long> {
        if (pattern.isEmpty() || chunk.content.isEmpty()) return emptyList()

        val results = mutableListOf<Long>()
        val content = chunk.content

        for (i in 0..(content.size - pattern.size)) {
            var match = true
            for (j in pattern.indices) {
                if (content[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                results.add(i.toLong())
            }
        }

        return results
    }

    /**
     * Search for a hex string pattern (e.g., "DEADBEEF").
     *
     * @param chunk The chunk to search in
     * @param hexString Hex string to find (without 0x prefix)
     * @return List of offsets where pattern was found
     */
    fun searchHexInChunk(chunk: EditorChunk.Binary, hexString: String): List<Long> {
        // Convert hex string to byte array
        val cleanHex = hexString.replace("\\s".toRegex(), "").uppercase()
        require(cleanHex.length % 2 == 0) { "Hex string must have even number of characters" }

        val pattern = ByteArray(cleanHex.length / 2) { i ->
            val index = i * 2
            cleanHex.substring(index, index + 2).toInt(16).toByte()
        }

        return searchInChunk(chunk, pattern)
    }

    /**
     * Save file by writing binary chunks.
     * Merges all dirty chunks and writes atomically via EditorDataSource.
     */
    suspend fun saveFile(dirtyChunks: List<EditorChunk.Binary>, boundaries: Map<EditorChunk.ChunkId, ChunkBoundary>) {
        if (dirtyChunks.isEmpty()) {
            log(tag, DEBUG) { "No dirty binary chunks to save" }
            return
        }

        log(tag, DEBUG) { "Saving ${dirtyChunks.size} dirty binary chunks" }

        // Read original file content
        val originalSize = dataSource.getSize()
        val originalContent = if (originalSize > 0) {
            // Read entire file as bytes
            dataSource.readChunk(0L, originalSize)
        } else {
            byteArrayOf()
        }

        // Merge dirty binary chunks with original content
        val sortedChunks = dirtyChunks.sortedBy { chunk ->
            boundaries[chunk.id]?.startOffset ?: Long.MAX_VALUE
        }

        val result = mutableListOf<Byte>()
        var currentOriginalPos = 0L

        for (chunk in sortedChunks) {
            val boundary = boundaries[chunk.id]
                ?: throw IllegalStateException("No boundary found for chunk ${chunk.id}")

            // Copy unchanged content before this chunk
            if (currentOriginalPos < boundary.startOffset) {
                val unchangedStart = currentOriginalPos.toInt()
                val unchangedEnd = boundary.startOffset.toInt()
                if (unchangedStart < originalContent.size) {
                    val unchangedBytes = originalContent.sliceArray(
                        unchangedStart until minOf(unchangedEnd, originalContent.size)
                    )
                    result.addAll(unchangedBytes.toList())
                }
            }

            // Add the modified chunk content
            result.addAll(chunk.content.toList())

            // Move past this chunk in original file
            currentOriginalPos = boundary.endOffset
        }

        // Copy any remaining content after the last chunk
        if (currentOriginalPos < originalContent.size) {
            val remainingBytes = originalContent.sliceArray(
                currentOriginalPos.toInt() until originalContent.size
            )
            result.addAll(remainingBytes.toList())
        }

        val mergedContent = result.toByteArray()

        // Write merged content using EditorDataSource.writeChunk()
        // This writes at offset 0 with the entire merged content (atomic write)
        dataSource.writeChunk(0L, mergedContent)

        log(tag, DEBUG) { "Successfully saved ${mergedContent.size} bytes (${dirtyChunks.size} dirty chunks merged)" }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            dataSource: EditorDataSource,
            chunkSize: Long = DEFAULT_CHUNK_SIZE
        ): BinaryChunkRepository
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 1024L * 1024L  // 1 MB chunks
    }
}
