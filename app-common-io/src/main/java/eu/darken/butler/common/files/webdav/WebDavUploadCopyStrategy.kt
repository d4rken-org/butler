package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.GenericCrossTypeCopyStrategy
import eu.darken.butler.common.files.operations.TransferStrategy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream

fun interface WebDavUploader {
    /**
     * Uploads exactly [length] bytes from [input] to [path], replacing what is there. A source that
     * delivers more or fewer bytes fails the upload, which the server then discards.
     */
    suspend fun upload(path: WebDavPath, length: Long, input: InputStream, onProgress: suspend (Long) -> Unit)
}

/**
 * Copies files INTO WebDAV with the source's length declared up front, so nothing has to be spooled.
 * Like [GenericCrossTypeCopyStrategy] it claims the destination with an exclusive create first; a
 * failed upload deletes that claim again. A source of unknown length, or one that is not a plain file,
 * goes through [GenericCrossTypeCopyStrategy] and its spooled stream.
 */
class WebDavUploadCopyStrategy<SP : APath<SP>, SPL : APathLookup<SP>>(
    private val uploader: WebDavUploader,
) : TransferStrategy<SP, SPL, WebDavPath, WebDavPathLookup> {

    private val genericStrategy = GenericCrossTypeCopyStrategy<SP, SPL, WebDavPath, WebDavPathLookup>()

    override suspend fun transferFile(
        sourceLookup: SPL,
        destination: WebDavPath,
        sourceOps: FileSystemOps<SP, SPL>,
        destOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<SP, WebDavPath> {
        val length = sourceLookup.size
        if (sourceLookup.fileType != FileType.FILE || length == null) {
            return genericStrategy.transferFile(sourceLookup, destination, sourceOps, destOps, options, onProgress)
        }

        log(TAG, DEBUG) { "Uploading $length bytes: ${sourceLookup.lookedUp} -> $destination" }

        var transferred = 0L
        // Source first, so a source that cannot be opened leaves no claimed destination behind.
        sourceOps.openInputStream(sourceLookup.lookedUp).use { input ->
            destOps.createFile(destination)
            try {
                uploader.upload(destination, length, input) { bytes ->
                    transferred += bytes
                    onProgress(bytes)
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    try {
                        destOps.delete(destination)
                    } catch (cleanup: Exception) {
                        log(TAG, WARN) { "Failed to remove the claimed $destination: ${cleanup.asLog()}" }
                        error.addSuppressed(cleanup)
                    }
                }
                throw error
            }
        }

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = transferred,
            destinationLookup = destOps.lookup(destination, LookupOptions.BASE),
        )
    }

    override suspend fun createDirectory(
        sourceLookup: SPL,
        destination: WebDavPath,
        sourceOps: FileSystemOps<SP, SPL>,
        destOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult<SP, WebDavPath> =
        genericStrategy.createDirectory(sourceLookup, destination, sourceOps, destOps, options)

    companion object {
        private val TAG = logTag("WebDAV", "UploadStrategy")
    }
}
