package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.write.FileCommitContext
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Buffer
import okio.BufferedSink
import okio.Source

/**
 * In-memory data source implementation for new/unsaved documents.
 * Reads and commits go through a UTF-8 byte view of the content.
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
        updateContentSource()
    }

    private val _contentSource = MutableStateFlow<ContentSource>(
        ContentSource.Memory(size = initialContent.length.toLong())
    )
    override val contentSource: StateFlow<ContentSource> = _contentSource.asStateFlow()

    private var content: String = initialContent

    private fun updateContentSource() {
        _contentSource.value = ContentSource.Memory(size = content.length.toLong())
    }

    // Byte size like getMeta(); ContentSource.Memory.size intentionally stays char-based (display)
    override suspend fun getSize(): Long = getMeta().size

    override suspend fun getMeta(): EditorDataSource.Meta = EditorDataSource.Meta(
        size = content.toByteArray(Charsets.UTF_8).size.toLong(),
        modifiedAt = null,
    )

    override suspend fun openByteSource(offset: Long): Source {
        val buffer = Buffer().write(content.toByteArray(Charsets.UTF_8))
        buffer.skip(offset)
        return buffer
    }

    override suspend fun commit(writer: suspend (FileCommitContext) -> Unit) {
        val original = content.toByteArray(Charsets.UTF_8)
        val collected = Buffer()
        val context = object : FileCommitContext {
            override val sink: BufferedSink = collected
            override suspend fun openOriginalSource(offset: Long): Source =
                Buffer().write(original).apply { skip(offset) }
        }
        writer(context)
        content = collected.readByteArray().toString(Charsets.UTF_8)
        updateContentSource()
        log(tag) { "Committed ${content.length} chars to in-memory content" }
    }

    override suspend fun close() {
        content = ""
        updateContentSource()
    }

    fun setContent(newContent: String) {
        content = newContent
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
