package eu.darken.butler.provider.documents.reader

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
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
 *
 * Phase 1: Read-only support (mode "r")
 * Phase 3: Write support (modes: w, wa, rw, rwt, wt)
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
     * @throws UnsupportedOperationException If mode not supported in current phase
     * @throws IllegalArgumentException If document ID is invalid or for virtual documents
     */
    suspend fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        log(TAG, INFO) { "openDocument(documentId=$documentId, mode=$mode)" }

        // Check cancellation
        signal?.throwIfCanceled()

        // Phase 1: Only support read mode
        if (mode != "r") {
            throw UnsupportedOperationException("Write operations not yet supported (Phase 3)")
        }

        try {
            // Decode document ID to path
            val path = codec.decode(documentId)
            log(TAG, INFO) { "Opening path: $path" }

            // Route to appropriate handler based on path type
            return when (path) {
                is LocalPath -> openLocalPath(path, mode)
                is SAFPath -> openSAFPath(path, mode)
                else -> throw IllegalArgumentException("Unsupported path type: ${path::class.simpleName}")
            }
        } catch (e: IllegalArgumentException) {
            // Document ID decode failure or virtual document
            log(TAG, ERROR) { "openDocument($documentId) failed: ${e.asLog()}" }
            throw FileNotFoundException("Cannot open document: ${e.message}")
        } catch (e: Exception) {
            log(TAG, ERROR) { "openDocument($documentId) failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Open a LocalPath file.
     * Fast path: Direct Java File I/O if accessible.
     * Fallback: GatewaySwitch with pipe for inaccessible files (root/ADB).
     */
    private suspend fun openLocalPath(path: LocalPath, mode: String): ParcelFileDescriptor {
        val file = path.file

        // Fast path: Direct file access if possible
        if (file.exists() && file.isFile && file.canRead()) {
            log(TAG, VERBOSE) { "Using direct file access for: ${path.path}" }
            val pfdMode = ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(file, pfdMode)
        }

        // Fallback: Use GatewaySwitch for inaccessible files
        log(TAG, INFO) { "File not directly accessible, routing through GatewaySwitch: ${path.path}" }
        return openViaGateway(path)
    }

    /**
     * Open a file via GatewaySwitch using pipe pattern.
     * Used for files that require privileged access (root/ADB) or caller lacks permissions (SAF).
     */
    private suspend fun openViaGateway(path: APath<*>): ParcelFileDescriptor {
        val inputStream = gatewaySwitch.openInputStream(path)
        return createPipeFromInputStream(inputStream)
    }

    /**
     * Create a ParcelFileDescriptor pipe from an InputStream.
     * Data is transferred asynchronously from InputStream to pipe's write side.
     */
    private fun createPipeFromInputStream(inputStream: InputStream): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        // Transfer data on background thread
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

    /**
     * Open a SAFPath file.
     * Uses pipe pattern because calling app doesn't have the SAF tree grant - only Butler does.
     * Similar to root/ADB files that require privileged access.
     */
    private suspend fun openSAFPath(path: SAFPath, mode: String): ParcelFileDescriptor {
        // SAF files are like root/ADB files - calling app doesn't have the tree grant
        // We must stream through a pipe using Butler's permissions
        log(TAG, INFO) { "SAF file requires pipe (caller lacks tree grant): ${path.path}" }
        return openViaGateway(path)
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "Reader")
    }
}
