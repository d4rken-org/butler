package eu.darken.butler.editor.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.parcelize.Parcelize


class EditorWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Arguments?,
) : Workspace {

    private val tag = logTag("Workspace", "Editor", id.shortTag)

    override val type: Workspace.Type = Workspace.Type.EDITOR

    private val _info = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = generateTitle(),
        )
    )
    override val info: MutableStateFlow<Workspace.Info> = _info

    val filePath: APath? get() = arguments?.filePath
    val chunkSize: Long get() = arguments?.chunkSize ?: ChunkManager.DEFAULT_CHUNK_SIZE
    val memoryLimit: Long get() = arguments?.memoryLimit ?: MemoryManager.DEFAULT_MAX_MEMORY_BYTES
    val isReadOnly: Boolean get() = arguments?.isReadOnly ?: false

    init {
        log(tag, INFO) { "Initialized with file: ${filePath?.name ?: "No file"}" }
        
        // Update title based on file
        updateTitle()
    }

    fun updateTitle(fileName: String? = null) {
        val newTitle = when {
            fileName != null -> fileName
            filePath != null -> filePath!!.name
            else -> "Editor ${id.shortTag}"
        }
        
        _info.value = _info.value.copy(title = newTitle.toCaString())
        log(tag, DEBUG) { "Updated title to: $newTitle" }
    }

    fun updateFileInfo(fileInfo: FileInfo?) {
        fileInfo?.let { info ->
            updateTitle(info.path.name)
        }
    }

    private fun generateTitle(): CaString {
        return when {
            arguments?.filePath != null -> arguments.filePath.name.toCaString()
            else -> "Editor ${id.shortTag}".toCaString()
        }
    }

    @Parcelize
    data class Arguments(
        val filePath: APath? = null,
        val chunkSize: Long = ChunkManager.DEFAULT_CHUNK_SIZE,
        val memoryLimit: Long = MemoryManager.DEFAULT_MAX_MEMORY_BYTES,
        val isReadOnly: Boolean = false,
        val goToLine: Int? = null,
        val searchQuery: String? = null
    ) : Workspace.Arguments {
        override val type: Workspace.Type
            get() = Workspace.Type.EDITOR

        companion object {
            fun withFile(filePath: APath, isReadOnly: Boolean = false): Arguments {
                return Arguments(
                    filePath = filePath,
                    isReadOnly = isReadOnly
                )
            }

            fun withFileAndSettings(
                filePath: APath, 
                chunkSize: Long = ChunkManager.DEFAULT_CHUNK_SIZE,
                memoryLimit: Long = MemoryManager.DEFAULT_MAX_MEMORY_BYTES,
                isReadOnly: Boolean = false
            ): Arguments {
                return Arguments(
                    filePath = filePath,
                    chunkSize = chunkSize,
                    memoryLimit = memoryLimit,
                    isReadOnly = isReadOnly
                )
            }

            fun withFileAndNavigation(
                filePath: APath,
                goToLine: Int? = null,
                searchQuery: String? = null,
                isReadOnly: Boolean = false
            ): Arguments {
                return Arguments(
                    filePath = filePath,
                    goToLine = goToLine,
                    searchQuery = searchQuery,
                    isReadOnly = isReadOnly
                )
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Arguments?): EditorWorkspace
    }
}