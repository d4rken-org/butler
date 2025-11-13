package eu.darken.butler.provider.documents.reader

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import java.io.FileNotFoundException
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
     * Uses standard Java File I/O.
     */
    private fun openLocalPath(path: LocalPath, mode: String): ParcelFileDescriptor {
        val file = path.file

        if (!file.exists()) {
            throw FileNotFoundException("File not found: ${path.path}")
        }

        if (!file.isFile) {
            throw FileNotFoundException("Not a file: ${path.path}")
        }

        // Parse mode string to ParcelFileDescriptor mode flags
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, pfdMode)
    }

    /**
     * Open a SAFPath file.
     * Uses ContentResolver to open the Storage Access Framework URI.
     */
    private fun openSAFPath(path: SAFPath, mode: String): ParcelFileDescriptor {
        // ContentResolver expects same mode strings as DocumentsProvider
        return context.contentResolver.openFileDescriptor(path.pathUri.toAndroidUri(), mode)
            ?: throw FileNotFoundException("Couldn't open SAF file: ${path.pathUri}")
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "Reader")
    }
}
