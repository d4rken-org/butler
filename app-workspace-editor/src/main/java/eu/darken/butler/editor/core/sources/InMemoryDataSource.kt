package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.editor.core.engine.FileInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory data source implementation for new/unsaved documents.
 */
class InMemoryDataSource @AssistedInject constructor(
    @Assisted private val initialContent: String
) : EditorDataSource {

    override val fileInfo: StateFlow<FileInfo?> = MutableStateFlow(null)

    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private var content: String = initialContent

    override suspend fun readChunk(startOffset: Long, size: Long): Result<String> {
        return try {
            val endOffset = (startOffset + size).coerceAtMost(content.length.toLong())
            val chunk = content.substring(
                startOffset.toInt().coerceIn(0, content.length),
                endOffset.toInt().coerceIn(0, content.length)
            )
            Result.success(chunk)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun writeChunk(startOffset: Long, content: String): Result<Unit> {
        return try {
            val before = this.content.substring(0, startOffset.toInt().coerceIn(0, this.content.length))
            val after = this.content.substring(startOffset.toInt().coerceIn(0, this.content.length))
            this.content = before + content + after
            _isModified.value = this.content != initialContent
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSize(): Long = content.length.toLong()

    override suspend fun save(): Result<Unit> {
        // In-memory content can't be saved without a file path
        return Result.failure(UnsupportedOperationException("Cannot save in-memory content without a file path"))
    }

    override suspend fun close(): Result<Unit> {
        content = ""
        _isModified.value = false
        return Result.success(Unit)
    }

    fun getContent(): String = content

    fun setContent(newContent: String) {
        content = newContent
        _isModified.value = content != initialContent
    }

    @AssistedFactory
    interface Factory {
        fun create(initialContent: String): InMemoryDataSource
    }
}