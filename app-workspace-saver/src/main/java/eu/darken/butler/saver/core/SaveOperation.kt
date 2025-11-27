package eu.darken.butler.saver.core

import android.content.Context
import android.net.Uri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import javax.inject.Inject

/**
 * Handles the actual file copy operation from a content URI to a destination path.
 */
@Reusable
class SaveOperation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
) {
    /**
     * State of the save operation.
     */
    sealed class State {
        data object Idle : State()
        data class Saving(
            val bytesWritten: Long,
            val totalBytes: Long?,
        ) : State() {
            val progress: Float?
                get() = totalBytes?.let { if (it > 0) bytesWritten.toFloat() / it else null }
        }

        data class Success(
            val savedPath: APath<*>,
            val bytesWritten: Long,
        ) : State()

        data class Error(
            val error: SaveError,
        ) : State()
    }

    /**
     * Types of errors that can occur during save.
     */
    sealed class SaveError {
        /** URI is no longer accessible (permission expired) */
        data object SourceExpired : SaveError()

        /** Cannot write to the destination */
        data class PermissionDenied(val message: String?) : SaveError()

        /** General write error */
        data class WriteError(val message: String?) : SaveError()

        /** File already exists and overwrite not requested */
        data class FileExists(val path: APath<*>) : SaveError()
    }

    /**
     * Execute the save operation.
     *
     * @param sourceUri The content URI to read from
     * @param targetDirectory The directory to save to
     * @param filename The filename to use
     * @param totalBytes Optional total size for progress calculation
     * @return Flow of save states
     */
    fun execute(
        sourceUri: Uri,
        targetDirectory: APath<*>,
        filename: String,
        totalBytes: Long? = null,
    ): Flow<State> = flow {
        log(TAG) { "execute($sourceUri -> $targetDirectory/$filename)" }
        emit(State.Saving(0, totalBytes))

        try {
            // 1. Open input stream from ContentResolver
            val inputStream = context.contentResolver.openInputStream(sourceUri)
            if (inputStream == null) {
                log(TAG, ERROR) { "Failed to open input stream for $sourceUri" }
                emit(State.Error(SaveError.SourceExpired))
                return@flow
            }

            // 2. Create target path
            val targetPath = targetDirectory.child(filename)
            log(TAG) { "Target path: $targetPath" }

            // 3. Check if file exists
            if (gatewaySwitch.exists(targetPath)) {
                log(TAG) { "File already exists: $targetPath" }
                emit(State.Error(SaveError.FileExists(targetPath)))
                return@flow
            }

            // 4. Create file and write
            gatewaySwitch.createFile(targetPath, createParents = false)

            var bytesWritten = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            gatewaySwitch.openOutputStream(targetPath, append = false).use { outputStream ->
                inputStream.use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead

                        // Emit progress periodically (every 64KB)
                        if (bytesWritten % PROGRESS_UPDATE_INTERVAL == 0L) {
                            emit(State.Saving(bytesWritten, totalBytes))
                        }
                    }
                    outputStream.flush()
                }
            }

            log(TAG, INFO) { "Successfully saved $bytesWritten bytes to $targetPath" }
            emit(State.Success(targetPath, bytesWritten))

        } catch (e: SecurityException) {
            log(TAG, ERROR) { "Security exception: ${e.asLog()}" }
            emit(State.Error(SaveError.SourceExpired))
        } catch (e: IOException) {
            log(TAG, ERROR) { "IO exception: ${e.asLog()}" }
            emit(State.Error(SaveError.WriteError(e.message)))
        } catch (e: Exception) {
            log(TAG, ERROR) { "Unexpected error: ${e.asLog()}" }
            emit(State.Error(SaveError.WriteError(e.message)))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private val TAG = logTag("Saver", "SaveOperation")
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_UPDATE_INTERVAL = 65536L // 64KB
    }
}
