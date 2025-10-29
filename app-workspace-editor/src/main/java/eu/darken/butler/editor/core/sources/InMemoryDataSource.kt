package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.FileInfo
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
        log(tag) { "Initialized in-memory data source with initial content: ${initialContent.length} bytes" }
    }

    override suspend fun open() {
        // No-op: in-memory data source doesn't require opening
    }

    override val fileInfo: StateFlow<FileInfo?> = MutableStateFlow(null)

    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private var content: String = initialContent

    override suspend fun readChunk(startOffset: Long, size: Long): String {
        val endOffset = (startOffset + size).coerceAtMost(content.length.toLong())
        return content.substring(
            startOffset.toInt().coerceIn(0, content.length),
            endOffset.toInt().coerceIn(0, content.length)
        )
    }

    override suspend fun getSize(): Long = content.length.toLong()

    /**
     * In-memory content cannot be saved to disk without a file path.
     * Use saveFileAs() from EditorWorkspace to save to a specific path.
     */
    override suspend fun save(dirtyChunks: List<TextChunk>) {
        throw UnsupportedOperationException("Cannot save in-memory content without a file path. Use saveFileAs() instead.")
    }

    override suspend fun close() {
        content = ""
        _isModified.value = false
    }

    override suspend fun openSource(): Source {
        log(tag) { "Creating source from in-memory content (${content.length} bytes)" }

        // Create buffer with current content
        val buffer = Buffer()
        buffer.writeString(content, Charsets.UTF_8)

        return buffer as Source
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