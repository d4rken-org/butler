package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.ChunkBoundary
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.TextChunk
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
        updateContentSource()
    }

    private val _contentSource = MutableStateFlow<ContentSource>(
        ContentSource.Memory(size = initialContent.length.toLong())
    )
    override val contentSource: StateFlow<ContentSource> = _contentSource.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private var content: String = initialContent

    private fun updateContentSource() {
        _contentSource.value = ContentSource.Memory(size = content.length.toLong())
    }

    override suspend fun readChunk(startOffset: Long, size: Long): String {
        log(tag) { "readChunk called: startOffset=$startOffset, size=$size, content.length=${content.length}" }

        val start = startOffset.toInt().coerceIn(0, content.length)
        val end = (startOffset + size).toInt().coerceAtMost(content.length)

        log(tag) { "readChunk: start=$start, end=$end" }

        // Return substring directly
        // Note: May contain incomplete UTF-16 surrogate pairs at chunk boundaries
        // ChunkRepository is responsible for handling this
        return content.substring(start, end)
    }

    override suspend fun getSize(): Long = content.length.toLong()

    /**
     * Saves dirty chunks by merging them back into the in-memory content.
     * This allows testing of save operations without requiring file I/O.
     */
    override suspend fun save(dirtyChunks: List<TextChunk>, boundaries: Map<TextChunk.ChunkId, ChunkBoundary>) {
        if (dirtyChunks.isEmpty()) {
            log(tag) { "No dirty chunks to save" }
            return
        }

        log(tag) { "Merging ${dirtyChunks.size} dirty chunks into in-memory content" }

        // Use ChunkManager.mergeChunks to properly merge dirty chunks into original content
        val originalBytes = content.toByteArray(Charsets.UTF_8)
        val mergedBytes = eu.darken.butler.editor.core.engine.ChunkManager.mergeChunks(
            originalContent = originalBytes,
            dirtyChunks = dirtyChunks,
            boundaries = boundaries
        )

        content = mergedBytes.toString(Charsets.UTF_8)
        _isModified.value = content != initialContent
        updateContentSource()

        log(tag) { "Successfully merged dirty chunks (new size: ${content.length} bytes)" }
    }

    override suspend fun close() {
        content = ""
        _isModified.value = false
        updateContentSource()
    }

    override suspend fun openSource(): Source {
        val utf8Bytes = content.toByteArray(Charsets.UTF_8)
        log(tag) { "Creating source from in-memory content (${utf8Bytes.size} bytes)" }

        // Create buffer with current content
        val buffer = Buffer()
        buffer.write(utf8Bytes)

        return buffer
    }

    override suspend fun getMeta(): EditorDataSource.Meta = EditorDataSource.Meta(
        size = content.toByteArray(Charsets.UTF_8).size.toLong(),
        modifiedAt = null,
    )

    override suspend fun commit(writer: suspend (EditorDataSource.CommitContext) -> Unit) {
        val original = content.toByteArray(Charsets.UTF_8)
        val collected = Buffer()
        val context = object : EditorDataSource.CommitContext {
            override val sink: okio.BufferedSink = collected
            override suspend fun openOriginalSource(offset: Long): Source =
                Buffer().write(original).apply { skip(offset) }
        }
        writer(context)
        content = collected.readByteArray().toString(Charsets.UTF_8)
        _isModified.value = content != initialContent
        updateContentSource()
        log(tag) { "Committed ${content.length} chars to in-memory content" }
    }

    override suspend fun openByteSource(offset: Long): Source {
        val buffer = Buffer().write(content.toByteArray(Charsets.UTF_8))
        buffer.skip(offset)
        return buffer
    }

    fun setContent(newContent: String) {
        content = newContent
        _isModified.value = content != initialContent
        updateContentSource()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            initialContent: String,
        ): InMemoryDataSource
    }
}