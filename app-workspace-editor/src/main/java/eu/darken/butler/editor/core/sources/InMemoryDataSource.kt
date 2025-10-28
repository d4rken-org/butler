package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
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
        log(tag) { "Initialized in-memory data source with initial content: ${initialContent.length} bytes" }
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

    override suspend fun writeChunk(startOffset: Long, content: String) {
        val before = this.content.substring(0, startOffset.toInt().coerceIn(0, this.content.length))
        val after = this.content.substring(startOffset.toInt().coerceIn(0, this.content.length))
        this.content = before + content + after
        _isModified.value = this.content != initialContent
    }

    override suspend fun getSize(): Long = content.length.toLong()

    override suspend fun save() {
        throw UnsupportedOperationException("Cannot save in-memory content without a file path")
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