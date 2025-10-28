package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.editor.core.engine.FileInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.buffer
import okio.use
import kotlin.time.Instant

/**
 * File-based data source implementation.
 */
class FileDataSource @AssistedInject constructor(
    @Assisted private val filePath: APath<*>,
    @Assisted private val gatewaySwitch: GatewaySwitch
) : EditorDataSource {

    private val _fileInfo = MutableStateFlow<FileInfo?>(null)
    override val fileInfo: StateFlow<FileInfo?> = _fileInfo.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private var fileContent: String = ""

    suspend fun initialize(): Result<Unit> {
        return try {
            if (!filePath.exists(gatewaySwitch)) {
                return Result.failure(IllegalArgumentException("File does not exist: $filePath"))
            }

            val lookup = filePath.lookup(gatewaySwitch, LookupOptions.Companion.BASE)
            val content = gatewaySwitch.file(filePath, readWrite = false).use { handle ->
                handle.source().buffer().use { source ->
                    source.readByteArray()
                }
            }

            fileContent = String(content)
            _fileInfo.value = FileInfo(
                path = filePath,
                size = lookup.size ?: 0L,
                lastModified = lookup.modifiedAt ?: Instant.Companion.DISTANT_PAST,
                canWrite = true // We'll assume writable for now
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun readChunk(startOffset: Long, size: Long): Result<String> {
        return try {
            val endOffset = (startOffset + size).coerceAtMost(fileContent.length.toLong())
            val chunk = fileContent.substring(
                startOffset.toInt().coerceIn(0, fileContent.length),
                endOffset.toInt().coerceIn(0, fileContent.length)
            )
            Result.success(chunk)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun writeChunk(startOffset: Long, content: String): Result<Unit> {
        return try {
            val before = fileContent.substring(0, startOffset.toInt().coerceIn(0, fileContent.length))
            val after = fileContent.substring(startOffset.toInt().coerceIn(0, fileContent.length))
            fileContent = before + content + after
            _isModified.value = true
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSize(): Long = fileContent.length.toLong()

    override suspend fun save(): Result<Unit> {
        return try {
            gatewaySwitch.file(filePath, readWrite = true).use { handle ->
                handle.sink().buffer().use { sink ->
                    sink.write(fileContent.toByteArray())
                }
            }
            _isModified.value = false
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun close(): Result<Unit> {
        fileContent = ""
        _fileInfo.value = null
        _isModified.value = false
        return Result.success(Unit)
    }

    @AssistedFactory
    interface Factory {
        fun create(filePath: APath<*>, gatewaySwitch: GatewaySwitch): FileDataSource
    }
}