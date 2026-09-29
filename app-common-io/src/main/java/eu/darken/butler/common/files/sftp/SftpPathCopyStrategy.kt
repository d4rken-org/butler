package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.GenericCrossTypeCopyStrategy
import eu.darken.butler.common.files.operations.TransferStrategy

/**
 * Without followSymlinks a link is recreated when it stays on its own location and points inside it.
 * Everything else is streamed like any cross-type copy, which copies what a link leads to: a link
 * cannot point into another location, and one whose target lies outside the location has no path.
 */
class SftpPathCopyStrategy : TransferStrategy<SftpPath, SftpPathLookup, SftpPath, SftpPathLookup> {

    private val copyStrategy = GenericCrossTypeCopyStrategy<SftpPath, SftpPathLookup, SftpPath, SftpPathLookup>()

    override suspend fun transferFile(
        sourceLookup: SftpPathLookup,
        destination: SftpPath,
        sourceOps: FileSystemOps<SftpPath, SftpPathLookup>,
        destOps: FileSystemOps<SftpPath, SftpPathLookup>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<SftpPath, SftpPath> {
        val target = sourceLookup.target
        if (
            options.followSymlinks ||
            sourceLookup.fileType != FileType.SYMBOLIC_LINK ||
            target == null ||
            target.locationId != destination.locationId
        ) {
            return copyStrategy.transferFile(
                sourceLookup = sourceLookup,
                destination = destination,
                sourceOps = sourceOps,
                destOps = destOps,
                options = options,
                onProgress = onProgress,
            )
        }

        log(TAG, DEBUG) { "Recreating link ${sourceLookup.lookedUp} -> $target at $destination" }

        // SFTP v3 answers a link over an existing path with a generic failure rather than a conflict.
        when (destOps.existsStrict(destination)) {
            Existence.PRESENT -> throw PathAlreadyExistsException(path = destination)
            Existence.UNKNOWN -> throw WriteException("Cannot tell whether $destination exists", destination)
            Existence.ABSENT -> Unit
        }
        destOps.createSymlink(destination, target)
        onProgress(sourceLookup.size ?: 0L)

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = sourceLookup.size ?: 0L,
            destinationLookup = destOps.lookup(destination, LookupOptions.BASE),
        )
    }

    override suspend fun createDirectory(
        sourceLookup: SftpPathLookup,
        destination: SftpPath,
        sourceOps: FileSystemOps<SftpPath, SftpPathLookup>,
        destOps: FileSystemOps<SftpPath, SftpPathLookup>,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult<SftpPath, SftpPath> = copyStrategy.createDirectory(
        sourceLookup = sourceLookup,
        destination = destination,
        sourceOps = sourceOps,
        destOps = destOps,
        options = options,
    )

    companion object {
        private val TAG = logTag("SFTP", "CopyStrategy")
    }
}
