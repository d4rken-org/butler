package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.editor.core.mode.EditorMode
import eu.darken.butler.editor.core.sources.EditorDataSource

sealed class EditorState {
    /**
     * No file is currently open in the editor.
     */
    data object Empty : EditorState()

    /**
     * A file is being loaded into the editor.
     */
    data class Loading(
        val filePath: APath<*>,
    ) : EditorState()

    /**
     * A file is successfully loaded and ready for editing.
     */
    data class Loaded(
        val filePath: APath<*>?,
        val resources: EditorResources,
        val fileInfo: FileInfo?,
        val isModified: Boolean,
    ) : EditorState()

    /**
     * An error occurred during file operations.
     */
    data class Error(
        val throwable: Throwable,
        val previousState: EditorState? = null,
    ) : EditorState()
}

/**
 * Internal resources required for editor operations.
 */
data class EditorResources(
    val dataSource: EditorDataSource,
    val chunkRepository: ChunkRepository?,  // Can be null for text-only mode
    val binaryRepository: BinaryChunkRepository?,  // Can be null for text-only mode
    val chunkManager: ChunkManager,
    val mode: EditorMode,
    val buffer: EditorBuffer,
)
