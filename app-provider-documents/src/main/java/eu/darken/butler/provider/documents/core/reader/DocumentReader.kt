package eu.darken.butler.provider.documents.core.reader

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles openDocument() calls from Android's DocumentsProvider API.
 *
 * Responsibilities:
 * - Open files for reading/writing
 * - Return ParcelFileDescriptor for client apps
 * - Route through appropriate file access methods (LocalPath, SAFPath)
 * - Handle access modes and cancellation
 */
@Singleton
class DocumentReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: DocumentIdCodec,
    private val gatewaySwitch: GatewaySwitch,
) {

    /**
     * Open a document and return a ParcelFileDescriptor.
     *
     * @param documentId Document ID to open
     * @param mode Access mode ("r", "w", "wa", "rw", "rwt", "wt")
     * @param signal Optional cancellation signal
     * @return ParcelFileDescriptor for reading/writing the file
     * @throws FileNotFoundException If file doesn't exist
     * @throws IllegalArgumentException If document ID is invalid or for virtual documents
     */
    suspend fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        log(TAG, INFO) { "openDocument(documentId=$documentId, mode=$mode)" }

        signal?.throwIfCanceled()

        return try {
            val path = codec.decode(documentId)
            log(TAG, INFO) { "Opening path: $path (mode=$mode)" }

            when (mode) {
                "r" -> openForRead(path)
                "w", "wt" -> openForWrite(path, truncate = true)
                "wa" -> openForWrite(path, truncate = false)
                "rw", "rwt" -> openForReadWrite(path, truncate = true)
                else -> throw IllegalArgumentException("Unsupported mode: $mode")
            }
        } catch (e: IllegalArgumentException) {
            log(TAG, ERROR) { "openDocument($documentId) failed: ${e.asLog()}" }
            throw FileNotFoundException("Cannot open document: ${e.message}")
        } catch (e: Exception) {
            log(TAG, ERROR) { "openDocument($documentId) failed: ${e.asLog()}" }
            throw e
        }
    }

    private suspend fun openForRead(path: APath<*>): ParcelFileDescriptor {
        log(TAG, VERBOSE) { "Opening for read: $path" }
        val inputStream = gatewaySwitch.openInputStream(path)
        return createPipeFromInputStream(inputStream)
    }

    /**
     * Open file for writing (truncate or append).
     * Uses pipe pattern: client writes → pipe → background thread → GatewaySwitch.
     */
    private suspend fun openForWrite(path: APath<*>, truncate: Boolean): ParcelFileDescriptor {
        log(TAG, VERBOSE) { "Opening for write (truncate=$truncate): $path" }

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                    gatewaySwitch.openOutputStream(path, append = !truncate).use { output ->
                        input.copyTo(output)
                    }
                }
                log(TAG, VERBOSE) { "Write pipe transfer completed: $path" }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Write pipe transfer failed: ${e.asLog()}" }
                try {
                    writeSide.closeWithError(e.message ?: "Write failed")
                } catch (closeError: Exception) {
                    log(TAG, ERROR) { "Failed to close write side with error: ${closeError.asLog()}" }
                }
            }
        }

        return writeSide
    }

    /**
     * Open file for read-write.
     * Currently treats read-write mode as write-only since most apps only need one direction.
     */
    private suspend fun openForReadWrite(path: APath<*>, truncate: Boolean): ParcelFileDescriptor {
        log(TAG, WARN) { "Read-write mode not fully optimized (using write-only for now): $path" }
        return openForWrite(path, truncate)
    }

    /**
     * Create a ParcelFileDescriptor pipe from an InputStream.
     * Data is transferred asynchronously from InputStream to pipe's write side.
     */
    private fun createPipeFromInputStream(inputStream: InputStream): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                log(TAG, VERBOSE) { "Pipe transfer completed successfully" }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Pipe transfer failed: ${e.asLog()}" }
                try {
                    readSide.closeWithError(e.message ?: "Transfer failed")
                } catch (closeError: Exception) {
                    log(TAG, ERROR) { "Failed to close read side with error: ${closeError.asLog()}" }
                }
            }
        }

        return readSide
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "Reader")
    }
}
