package eu.darken.butler.searcher.ui.search

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * Arguments for opening a file in the editor workspace
 */
@Parcelize
data class EditorArguments(
    val filePath: APath<*>,
    val chunkSize: Long = 8192L, // ChunkManager.DEFAULT_CHUNK_SIZE
    val memoryLimit: Long = 104857600L, // MemoryManager.DEFAULT_MAX_MEMORY_BYTES
    val isReadOnly: Boolean = false,
    val goToLine: Int? = null,
) : Workspace.Arguments {
    @IgnoredOnParcel
    override val type: Workspace.Type = Workspace.Type.EDITOR
}

/**
 * Arguments for opening a path in the explorer workspace
 */
@Parcelize
data class ExplorerArguments(
    val startPath: APath<*>? = null,
) : Workspace.Arguments {
    @IgnoredOnParcel
    override val type: Workspace.Type = Workspace.Type.EXPLORER
}