package eu.darken.butler.common.files.operations

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps

/**
 * Generic strategy for moving files across different path types.
 *
 * This strategy works for ANY cross-type combination using copy+delete pattern:
 * - Local ↔ SAF
 * - Local ↔ FTP
 * - SAF ↔ FTP
 * - FTP ↔ SMB
 * - etc.
 *
 * ## Why Copy+Delete?
 *
 * Cross-type move operations cannot use atomic move operations because:
 * - No shared file system (can't use rename/mv)
 * - Different APIs (DocumentsContract vs Files vs FTP commands)
 * - Often different physical locations (local disk vs cloud storage)
 *
 * Therefore, ALL cross-type moves use copy+delete pattern:
 * 1. Copy file to destination using GenericCrossTypeCopyStrategy
 * 2. Delete source file after successful copy
 * 3. No rollback on deletion failure (destination already created)
 *
 * ## Same-Type vs Cross-Type
 *
 * **Same-type moves** (LocalPath → LocalPath):
 * - Can use atomic operations (Files.move with ATOMIC_MOVE)
 * - Fast (just renames inode on same filesystem)
 * - Use LocalPathMoveStrategy for optimization
 *
 * **Cross-type moves** (LocalPath → SAFPath):
 * - Must use copy+delete (no atomic operation possible)
 * - Slower (full data copy)
 * - Use this generic strategy (no type-specific optimizations possible)
 *
 * ## Implementation Strategy
 *
 * 1. Copy file using GenericCrossTypeCopyStrategy
 * 2. Delete source file after successful copy
 * 3. Log warning if deletion fails (don't fail operation - destination exists)
 * 4. For directories, defer cleanup to GenericPathMove.cleanupSourceDirectories()
 *
 * @param SP The source path type (LocalPath, SAFPath, FTPPath, etc.)
 * @param SPL The source path lookup type
 * @param DP The destination path type (LocalPath, SAFPath, FTPPath, etc.)
 * @param DPL The destination path lookup type
 */
class GenericCrossTypeMoveStrategy<
    SP : APath<SP>, SPL : APathLookup<SP>,
    DP : APath<DP>, DPL : APathLookup<DP>
> : TransferStrategy<SP, SPL, DP, DPL> {

    private val copyStrategy = GenericCrossTypeCopyStrategy<SP, SPL, DP, DPL>()

    override suspend fun transferFile(
        sourceLookup: SPL,
        destination: DP,
        sourceOps: FileSystemOps<SP, SPL>,
        destOps: FileSystemOps<DP, DPL>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<SP, DP> {
        log(TAG, DEBUG) { "Moving cross-type: ${sourceLookup.lookedUp} → $destination" }

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
            // Step 2: Delete source file after successful copy
            try {
                val deleted = sourceOps.delete(sourceLookup.lookedUp)
                if (!deleted) {
                    log(TAG, WARN) { "Failed to delete source after copy: ${sourceLookup.lookedUp}" }
                    // Note: Source remains, but destination was created successfully
                    // This is acceptable behavior - user has the file at destination
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Error deleting source after copy: ${sourceLookup.lookedUp} - $e" }
                // Don't fail the operation - destination was created successfully
            }

            return TransferStrategy.TransferResult.Success(
                source = sourceLookup.lookedUp,
                destination = copyResult.destination,
                bytesTransferred = copyResult.bytesTransferred,
                destinationLookup = copyResult.destinationLookup
            )
        } else {
            // Copy failed or was skipped - return the result without attempting delete
            return copyResult
        }
    }

    override suspend fun createDirectory(
        sourceLookup: SPL,
        destination: DP,
        sourceOps: FileSystemOps<SP, SPL>,
        destOps: FileSystemOps<DP, DPL>,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult<SP, DP> {
        log(TAG, DEBUG) { "Moving directory cross-type: ${sourceLookup.lookedUp} → $destination" }

        // For directories, we just create at destination
        // The move operation (GenericPathMove) will handle cleanup of empty source
        // directories after all children have been moved
        val result = copyStrategy.createDirectory(
            sourceLookup = sourceLookup,
            destination = destination,
            sourceOps = sourceOps,
            destOps = destOps,
            options = options
        )

        // Note: Source directory cleanup happens in GenericPathMove.cleanupSourceDirectories()
        // after all children have been moved. This ensures directories are empty before deletion.

        return result
    }

    companion object {
        private val TAG = logTag("PathOperation", "GenericCrossTypeMove")
    }
}
