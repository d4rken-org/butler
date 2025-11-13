package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.ChunkBoundary
import eu.darken.butler.editor.core.engine.EditorChunk
import eu.darken.butler.editor.core.engine.FileInfo
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Buffer
import okio.Source

/**
 * In-memory data source implementation for new/unsaved documents.
 */
class InMemoryDataSource @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val initialContent: String
) : EditorDataSource {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "DataSource", "InMemory")

    init {
        log(tag) { "Initialized in-memory data source with initial content: ${initialContent.toByteArray(Charsets.UTF_8).size} bytes" }
    }

    override suspend fun open() {
        // No-op: in-memory data source doesn't require opening
        // Line ending will be detected by ChunkRepository when chunks are loaded
    }

    override val fileInfo: StateFlow<FileInfo?> = MutableStateFlow(null)

    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private var content: String = initialContent

    override suspend fun readChunk(startOffset: Long, size: Long): ByteArray {
        log(tag) { "readChunk called: startOffset=$startOffset, size=$size" }

        // Convert content to bytes
        val contentBytes = content.toByteArray(Charsets.UTF_8)

        val start = startOffset.toInt().coerceIn(0, contentBytes.size)
        val end = (startOffset + size).toInt().coerceAtMost(contentBytes.size)

        log(tag) { "readChunk: start=$start, end=$end, returning ${end - start} bytes" }

        return contentBytes.copyOfRange(start, end)
    }

    override suspend fun getSize(): Long = content.toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * Writes raw bytes at a specific offset in the in-memory content.
     * Uses ISO-8859-1 encoding to preserve all byte values (0x00-0xFF).
     */
    override suspend fun writeChunk(offset: Long, bytes: ByteArray) {
        log(tag) { "Writing ${bytes.size} bytes at offset $offset to in-memory content" }

        // Convert current content to bytes using ISO-8859-1 (1:1 byte mapping)
        val currentBytes = content.toByteArray(Charsets.ISO_8859_1)

        // Calculate new size
        val newSize = maxOf(currentBytes.size.toLong(), offset + bytes.size)
        val newBytes = ByteArray(newSize.toInt())

        // Copy original content
        System.arraycopy(currentBytes, 0, newBytes, 0, currentBytes.size)

        // Write new bytes at offset
        System.arraycopy(bytes, 0, newBytes, offset.toInt(), bytes.size)

        // Convert back to string using ISO-8859-1
        content = String(newBytes, Charsets.ISO_8859_1)
        _isModified.value = true

        log(tag) { "Successfully wrote ${bytes.size} bytes at offset $offset (new size: ${content.length})" }
    }

    /**
     * Saves dirty chunks by merging them back into the in-memory content.
     * This allows testing of save operations without requiring file I/O.
     */
    override suspend fun save(dirtyChunks: List<EditorChunk>, boundaries: Map<EditorChunk.ChunkId, ChunkBoundary>) {
        if (dirtyChunks.isEmpty()) {
            log(tag) { "No dirty chunks to save" }
            return
        }

        log(tag) { "Merging ${dirtyChunks.size} dirty chunks into in-memory content" }

        // Filter only text chunks for saving
        val textChunks = dirtyChunks.filterIsInstance<EditorChunk.Text>()

        // Use ChunkManager.mergeChunks to properly merge dirty chunks into original content
        val originalBytes = content.toByteArray(Charsets.UTF_8)
        val mergedBytes = eu.darken.butler.editor.core.engine.ChunkManager.mergeChunks(
            originalContent = originalBytes,
            dirtyChunks = textChunks,
            boundaries = boundaries
        )

        content = mergedBytes.toString(Charsets.UTF_8)
        _isModified.value = content != initialContent

        log(tag) { "Successfully merged dirty chunks (new size: ${content.length} bytes)" }
    }

    override suspend fun close() {
        content = ""
        _isModified.value = false
    }

    override suspend fun openSource(): Source {
        val utf8Bytes = content.toByteArray(Charsets.UTF_8)
        log(tag) { "Creating source from in-memory content (${utf8Bytes.size} bytes)" }

        // Create buffer with current content
        val buffer = Buffer()
        buffer.write(utf8Bytes)

        return buffer
    }

    fun setContent(newContent: String) {
        content = newContent
        _isModified.value = content != initialContent
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            initialContent: String,
        ): InMemoryDataSource
    }
}