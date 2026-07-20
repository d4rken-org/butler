package eu.darken.butler.common.files.saf

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.operations.TransferStrategy
import kotlinx.coroutines.CancellationException

/**
 * Strategy for moving files using SAFPath (Storage Access Framework).
 *
 * ## Implementation Strategy
 *
 * 1. Try an atomic file move (same-parent rename via renameDocument, or reparent via moveDocument)
 * 2. On [MoveOutcome.NotSupported] (provably nothing mutated), fall back to copy+delete:
 *    - Copy file using SAFPathCopyStrategy
 *    - Delete source file after successful copy
 * 3. Exceptions from move() may have side effects; the fallback only runs if the source
 *    still verifiably exists.
 *
 * Atomic **directory** moves are owned by GenericPathMove (tryAtomicMove), not this strategy —
 * by the time [createDirectory] runs, child work items are already queued, and moving the
 * subtree out from under them would orphan those items.
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
            when (val outcome = sourceOps.move(sourceLookup.lookedUp, destination)) {
                is MoveOutcome.Moved -> {
                    log(TAG, DEBUG) { "Atomic move succeeded: ${sourceLookup.lookedUp} -> $destination" }

                    onProgress(sourceLookup.size ?: 0L)

                    return TransferStrategy.TransferResult.Success(
                        source = sourceLookup.lookedUp,
                        destination = destination,
                        bytesTransferred = sourceLookup.size ?: 0L
                    )
                }

                is MoveOutcome.NotSupported ->
                    log(TAG, DEBUG) { "Atomic move not supported (${outcome.reason}), falling back to copy+delete" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The move may have had side effects; only fall back if the source is verifiably intact.
            val sourceIntact = try {
                sourceOps.exists(sourceLookup.lookedUp)
            } catch (inner: CancellationException) {
                throw inner
            } catch (inner: Exception) {
                false
            }
            if (!sourceIntact) throw e
            log(TAG, DEBUG) { "Atomic move failed with source intact, falling back to copy+delete: ${e.message}" }
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
            } catch (e: CancellationException) {
                throw e
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

        // No atomic attempt here: GenericPathMove.tryAtomicMove owns atomic directory moves.
        // Create empty directory (children moved separately by GenericPathMove)
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
