package eu.darken.butler.provider.documents.core.reader

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.io.ProxyPfdFactory
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import kotlinx.coroutines.CoroutineScope
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
 */
@Singleton
class DocumentReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: DocumentIdCodec,
    private val gatewaySwitch: GatewaySwitch,
    private val proxyPfdFactory: ProxyPfdFactory,
    private val dispatcherProvider: DispatcherProvider,
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
                "rw" -> openForReadWrite(path, truncate = false)
                "rwt" -> openForReadWrite(path, truncate = true)
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

        return try {
            val fileHandle = gatewaySwitch.file(path, readWrite = false)
            log(TAG, VERBOSE) { "Creating seekable ProxyPFD for: $path" }
            proxyPfdFactory.create(fileHandle, "r").also {
                log(TAG, INFO) { "Using seekable ProxyPFD for: $path" }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Seekable PFD failed, falling back to pipe: ${e.asLog()}" }
            createPipeFromInputStream(gatewaySwitch.openInputStream(path)).also {
                log(TAG, INFO) { "Using pipe fallback for: $path" }
            }
        }
    }

    private suspend fun openForWrite(path: APath<*>, truncate: Boolean): ParcelFileDescriptor {
        log(TAG, VERBOSE) { "Opening for write (truncate=$truncate): $path" }

        return try {
            val fileHandle = gatewaySwitch.file(path, readWrite = true)
            if (truncate) fileHandle.resize(0)
            val pfdMode = if (truncate) "w" else "wa"
            proxyPfdFactory.create(fileHandle, pfdMode).also {
                log(TAG, INFO) { "Using seekable ProxyPFD ($pfdMode) for: $path" }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Seekable PFD (w) failed, falling back to pipe: ${e.asLog()}" }
            createPipeFromOutputStream(gatewaySwitch.openOutputStream(path, append = !truncate)).also {
                log(TAG, INFO) { "Using pipe fallback (w) for: $path" }
            }
        }
    }

    private suspend fun openForReadWrite(path: APath<*>, truncate: Boolean): ParcelFileDescriptor {
        log(TAG, VERBOSE) { "Opening for read-write (truncate=$truncate): $path" }

        return try {
            val fileHandle = gatewaySwitch.file(path, readWrite = true)
            if (truncate) fileHandle.resize(0)
            proxyPfdFactory.create(fileHandle, "rw").also {
                log(TAG, INFO) { "Using seekable ProxyPFD (rw) for: $path" }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Seekable PFD (rw) failed, falling back to write pipe: ${e.asLog()}" }
            createPipeFromOutputStream(gatewaySwitch.openOutputStream(path, append = !truncate)).also {
                log(TAG, INFO) { "Using pipe fallback (rw) for: $path" }
            }
        }
    }

    private fun createPipeFromInputStream(inputStream: InputStream): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        CoroutineScope(dispatcherProvider.IO).launch {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                log(TAG, VERBOSE) { "Read pipe transfer completed" }
            } catch (e: Exception) {
                if (e.causeChain.any { it is ErrnoException && it.errno == OsConstants.EPIPE }) {
                    log(TAG, WARN) { "Pipe closed by client (EPIPE)" }
                } else {
                    log(TAG, ERROR) { "Read pipe transfer failed: ${e.asLog()}" }
                }
                try {
                    readSide.closeWithError(e.message ?: "Transfer failed")
                } catch (_: Exception) {
                    // Read side already closed by client
                }
            }
        }

        return readSide
    }

    private fun createPipeFromOutputStream(outputStream: java.io.OutputStream): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        CoroutineScope(dispatcherProvider.IO).launch {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                log(TAG, VERBOSE) { "Write pipe transfer completed" }
            } catch (e: Exception) {
                if (e.causeChain.any { it is ErrnoException && it.errno == OsConstants.EPIPE }) {
                    log(TAG, WARN) { "Pipe closed by client (EPIPE)" }
                } else {
                    log(TAG, ERROR) { "Write pipe transfer failed: ${e.asLog()}" }
                }
                try {
                    writeSide.closeWithError(e.message ?: "Write failed")
                } catch (_: Exception) {
                    // Write side already closed by client
                }
            }
        }

        return writeSide
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "Reader")
    }
}
