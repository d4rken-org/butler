package eu.darken.butler.editor.core.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Common interface for editor buffers.
 * Implemented by ChunkedTextBuffer and ChunkedBinaryBuffer.
 */
interface EditorBuffer {
    /** Whether the buffer has unsaved modifications */
    val isModified: StateFlow<Boolean>

    /** Initialize the buffer (load initial content) */
    suspend fun initialize(): Result<Unit>

    /** Save all modifications to the data source */
    suspend fun saveFile(): Result<Unit>

    /** Undo the last operation, returns new cursor position if applicable */
    suspend fun undo(): Result<TextPosition?>

    /** Redo the previously undone operation, returns new cursor position if applicable */
    suspend fun redo(): Result<TextPosition?>

    /** Check if undo is available */
    fun canUndo(): Boolean

    /** Check if redo is available */
    fun canRedo(): Boolean

    /** Dispose of resources held by this buffer */
    fun dispose()
}
