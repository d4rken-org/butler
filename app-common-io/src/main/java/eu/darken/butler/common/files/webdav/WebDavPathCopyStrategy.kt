package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.operations.TransferStrategy

/** Copies on the server within one location, and uploads with a known length between two locations. */
class WebDavPathCopyStrategy(
    private val fileSystemOps: WebDavFileSystemOps,
) : TransferStrategy<WebDavPath, WebDavPathLookup, WebDavPath, WebDavPathLookup> {

    private val uploadStrategy = WebDavUploadCopyStrategy<WebDavPath, WebDavPathLookup>(fileSystemOps)

    override suspend fun transferFile(
        sourceLookup: WebDavPathLookup,
        destination: WebDavPath,
        sourceOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        destOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<WebDavPath, WebDavPath> {
        val source = sourceLookup.lookedUp
        if (source.locationId != destination.locationId) {
            return uploadStrategy.transferFile(sourceLookup, destination, sourceOps, destOps, options, onProgress)
        }

        log(TAG, DEBUG) { "Copying on the server: $source -> $destination" }
        fileSystemOps.copy(source, destination)
        onProgress(sourceLookup.size ?: 0L)

        return TransferStrategy.TransferResult.Success(
            source = source,
            destination = destination,
            bytesTransferred = sourceLookup.size ?: 0L,
            destinationLookup = destOps.lookup(destination, LookupOptions.BASE),
        )
    }

    override suspend fun createDirectory(
        sourceLookup: WebDavPathLookup,
        destination: WebDavPath,
        sourceOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        destOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult<WebDavPath, WebDavPath> =
        uploadStrategy.createDirectory(sourceLookup, destination, sourceOps, destOps, options)

    companion object {
        private val TAG = logTag("WebDAV", "CopyStrategy")
    }
}
