package eu.darken.butler.common.files.saf

import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.operations.TransferStrategy

/**
 * Strategy for moving files using SAFPath (Storage Access Framework).
 *
 * Move operation attempts atomic moves when possible using DocumentsContract.moveDocument().
 * This mirrors LocalPath behavior for optimal performance.
 *
 * ## Implementation Strategy
 *
 * 1. Try atomic move using DocumentsContract.moveDocument() (API 24+)
 * 2. On failure, fall back to copy+delete pattern:
 *    - Copy file using SAFPathCopyStrategy
 *    - Delete source file after successful copy
 * 3. No rollback on failure (source remains if delete fails)
 *
 * ## Comparison with LocalPath
 *
 * | Feature | LocalPath | SAFPath |
 * |---------|-----------|---------|
 * | Atomic move | Yes (same filesystem) | Yes (same document tree) |
 * | Fallback | Copy+delete | Copy+delete |
 * | Performance | Fast (rename) | Fast (atomic) or slower (copy) |
 *
 * @see LocalPathMoveStrategy for comparison
 * @see SAFPathCopyStrategy for copy implementation
 */
class SAFPathMoveStrategy : TransferStrategy<
        SAFPath, SAFPathLookup,      // Source types
        SAFPath, SAFPathLookup       // Destination types
        > {

    private val copyStrategy = SAFPathCopyStrategy()

    override suspend fun transferFile(
        sourceLookup: SAFPathLookup,
        destination: SAFPath,
        sourceOps: FileSystemOps<SAFPath, SAFPathLookup>,
        destOps: FileSystemOps<SAFPath, SAFPathLookup>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<SAFPath, SAFPath> {
        log(TAG, DEBUG) { "Moving SAF file: ${sourceLookup.lookedUp} -> $destination" }

        // Try atomic move first (most efficient)
        try {
            sourceOps.move(sourceLookup.lookedUp, destination)
            log(TAG, DEBUG) { "Atomic move succeeded: ${sourceLookup.lookedUp} -> $destination" }

            onProgress(sourceLookup.size ?: 0L)

            return TransferStrategy.TransferResult.Success(
                source = sourceLookup.lookedUp,
                destination = destination,
                bytesTransferred = sourceLookup.size ?: 0L
            )
        } catch (e: Exception) {
            log(TAG, DEBUG) { "Atomic move not supported or failed, falling back to copy+delete: ${e.message}" }
            // Fall through to copy+delete
        }

        // Atomic move failed - use copy+delete fallback
        // Step 1: Copy file to destination
        val copyResult = copyStrategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destination,
            sourceOps = sourceOps,
            destOps = destOps,
            options = options,
            onProgress = onProgress
        )

        if (copyResult is TransferStrategy.TransferResult.Success) {
            // Step 2: Delete source file
            try {
                val deleted = sourceOps.delete(sourceLookup.lookedUp)
                if (!deleted) {
                    log(TAG, WARN) { "Failed to delete source after copy: ${sourceLookup.lookedUp}" }
                    // Note: Source remains, but destination was created successfully
                    // This is acceptable behavior (not an error)
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Error deleting source after copy: ${sourceLookup.lookedUp} - $e" }
                // Don't fail the operation - destination was created successfully
            }

            return TransferStrategy.TransferResult.Success(
                source = sourceLookup.lookedUp,
                destination = copyResult.destination,
                bytesTransferred = copyResult.bytesTransferred
            )
        } else {
            return copyResult
        }
    }

    override suspend fun createDirectory(
        sourceLookup: SAFPathLookup,
        destination: SAFPath,
        sourceOps: FileSystemOps<SAFPath, SAFPathLookup>,
        destOps: FileSystemOps<SAFPath, SAFPathLookup>,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult<SAFPath, SAFPath> {
        log(TAG, DEBUG) { "Moving SAF directory: ${sourceLookup.lookedUp} -> $destination" }

        // For directories, we just create at destination
        // The move operation will handle cleanup of empty source directories
        val result = copyStrategy.createDirectory(
            sourceLookup = sourceLookup,
            destination = destination,
            sourceOps = sourceOps,
            destOps = destOps,
            options = options
        )

        // Note: Source directory cleanup happens in GenericPathMove.cleanupSourceDirectories()
        // after all children have been moved

        return result
    }

    companion object {
        private val TAG = logTag("PathOperation", "SAFMoveStrategy")
    }
}
