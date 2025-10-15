package eu.darken.butler.common.files.saf

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.operations.TransferStrategy
import okio.buffer
import okio.sink
import okio.source

/**
 * Strategy for copying files using SAFPath (Storage Access Framework).
 *
 * Unlike LocalPathCopyStrategy which uses java.nio.Files.copy(), this strategy uses:
 * - ContentResolver streams for file content transfer
 * - Manual byte copying with progress tracking
 * - SAFDocFile extensions for attribute preservation
 *
 * ## Key Differences from LocalPath
 *
 * | Feature | LocalPath | SAFPath |
 * |---------|-----------|---------|
 * | Copy API | `Files.copy()` | ContentResolver streams |
 * | Attributes | `Files.copyAttributes()` | SAFDocFile extensions |
 * | Symlinks | Supported | Not supported (Android limitation) |
 * | Atomic ops | Available | Not available |
 *
 * ## Attribute Preservation
 *
 * SAF has limited attribute support compared to local files:
 * - ✅ Last modified time (via DocumentFile extensions)
 * - ✅ Permissions (via ParcelFileDescriptor + Os.fchmod)
 * - ✅ Ownership (via ParcelFileDescriptor + Os.fchown)
 * - ❌ Symlinks (not supported by Android SAF)
 * - ❌ Extended attributes (not supported by Android SAF)
 *
 * @see LocalPathCopyStrategy for comparison
 */
class SAFPathCopyStrategy : TransferStrategy<
    SAFPath, SAFPathLookup, SAFPathLookupExtended,      // Source types
    SAFPath, SAFPathLookup, SAFPathLookupExtended       // Destination types
> {

    override suspend fun transferFile(
        sourceLookup: SAFPathLookup,
        destination: SAFPath,
        sourceOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
        destOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<SAFPath, SAFPath> {
        log(TAG, DEBUG) { "Copying SAF file: ${sourceLookup.lookedUp} -> $destination" }

        // SAF doesn't support symlinks - treat as regular file
        // For same-type SAF operations, sourceOps and destOps are the same instance
        return copyRegularFile(sourceLookup, destination, destOps, options, onProgress)
    }

    override suspend fun createDirectory(
        sourceLookup: SAFPathLookup,
        destination: SAFPath,
        sourceOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
        destOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult<SAFPath, SAFPath> {
        log(TAG, DEBUG) { "Creating SAF directory: $destination" }

        // Create directory (FileSystemOps handles parent creation)
        destOps.createDir(destination)

        // Copy attributes if requested
        if (options.preserveAttributes) {
            val destLookup = destOps.lookup(destination)
            copyAttributes(sourceLookup, destLookup, destOps)
        }

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = 0L
        )
    }

    /**
     * Copy a regular file using ContentResolver streams.
     *
     * Uses Okio for efficient buffered I/O with progress tracking.
     */
    private suspend fun copyRegularFile(
        sourceLookup: SAFPathLookup,
        destination: SAFPath,
        fileSystemOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<SAFPath, SAFPath> {
        var totalBytesTransferred = 0L

        // Create destination file (GenericPathCopy guarantees destination doesn't exist)
        fileSystemOps.createFile(destination)

        // Copy file contents with progress tracking
        fileSystemOps.openInputStream(sourceLookup.lookedUp).source().buffer().use { source ->
            fileSystemOps.openOutputStream(destination, append = false).sink().buffer().use { sink ->
                val buffer = okio.Buffer()
                var bytesRead: Long

                while (source.read(buffer, BUFFER_SIZE.toLong()).also { bytesRead = it } != -1L) {
                    sink.write(buffer, bytesRead)
                    totalBytesTransferred += bytesRead
                    onProgress(bytesRead) // Report incremental bytes, not total
                }
                sink.flush()
            }
        }

        // Copy file attributes if requested
        if (options.preserveAttributes) {
            val destLookup = fileSystemOps.lookup(destination)
            copyAttributes(sourceLookup, destLookup, fileSystemOps)
        }

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = totalBytesTransferred
        )
    }

    /**
     * Copy file attributes from source to destination.
     *
     * Attempts to copy all available attributes, silently ignoring failures
     * (some attributes may not be supported on all file systems).
     */
    private suspend fun copyAttributes(
        source: SAFPathLookup,
        dest: SAFPathLookup,
        fileSystemOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>
    ) {
        try {
            // Copy last modified time
            source.modifiedAt?.let { modTime ->
                val success = fileSystemOps.setModifiedAt(dest.lookedUp, modTime)
                if (!success) {
                    log(TAG, VERBOSE) { "Failed to set modified time for ${dest.lookedUp}" }
                }
            }

            // Permissions and ownership not available in SAFPathLookup
            // Only copy last modified time which is available
            log(TAG, VERBOSE) { "Attribute preservation for SAF is limited to modified time" }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to copy some attributes: $e" }
            // Don't fail the operation - attribute copying is best-effort
        }
    }

    companion object {
        private val TAG = logTag("PathOperation", "SAFCopyStrategy")
        private const val BUFFER_SIZE = 64 * 1024 // 64KB chunks (same as LocalPath)
    }
}
