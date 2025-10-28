package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.editor.core.engine.FileInfo
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Source
import okio.buffer
import okio.use

/**
 * File-based data source implementation.
 */
class FileDataSource @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val filePath: APath<*>,
    @Assisted private val gatewaySwitch: GatewaySwitch
) : EditorDataSource {
    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "DataSource", "File")
    private val _fileInfo = MutableStateFlow<FileInfo?>(null)
    override val fileInfo: StateFlow<FileInfo?> = _fileInfo.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private var fileContent: String = ""

    suspend fun initialize(): Result<Unit> {
        log(tag) { "Initializing on file: $filePath with $gatewaySwitch" }
        return try {
            if (!filePath.exists(gatewaySwitch)) {
                return Result.failure(IllegalArgumentException("File does not exist: $filePath"))
            }

            val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)
            log(tag) { "Opening $lookup now" }

            val content = gatewaySwitch.file(filePath, readWrite = false).use { handle ->
                handle.source().buffer().use { source ->
                    source.readByteArray()
                }
            }

            fileContent = String(content)

            _fileInfo.value = FileInfo(
                path = filePath,
                size = lookup.size!!,
                lastModified = lookup.modifiedAt!!,
                canWrite = true // We'll assume writable for now
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun readChunk(startOffset: Long, size: Long): String {
        val endOffset = (startOffset + size).coerceAtMost(fileContent.length.toLong())
        return fileContent.substring(
            startOffset.toInt().coerceIn(0, fileContent.length),
            endOffset.toInt().coerceIn(0, fileContent.length)
        )
    }

    override suspend fun writeChunk(startOffset: Long, content: String): Unit {
        val before = fileContent.substring(0, startOffset.toInt().coerceIn(0, fileContent.length))
        val after = fileContent.substring(startOffset.toInt().coerceIn(0, fileContent.length))
        fileContent = before + content + after
        _isModified.value = true
    }

    override suspend fun getSize(): Long = fileContent.length.toLong()

    override suspend fun save() {
        gatewaySwitch.file(filePath, readWrite = true).use { handle ->
            handle.sink().buffer().use { sink ->
                sink.write(fileContent.toByteArray())
            }
        }
        _isModified.value = false
    }

    override suspend fun close() {
        fileContent = ""
        _fileInfo.value = null
        _isModified.value = false
    }

    override suspend fun openSource(): Source {
        log(tag) { "Opening source for file: $filePath" }
        val handle = gatewaySwitch.file(filePath, readWrite = false)
        return handle.source()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            filePath: APath<*>,
            gatewaySwitch: GatewaySwitch,
        ): FileDataSource
    }
}