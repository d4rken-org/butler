package eu.darken.butler.common.files.local.operations.strategies

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.TransferStrategy
import okio.buffer
import okio.sink
import okio.source

/**
 * Strategy for moving files and directories.
 *
 * Attempts atomic file moves when possible ([MoveOutcome.NotSupported] falls back to copy+delete
 * for cross-device moves). Handles symlinks by recreating them at the destination with adjusted
 * targets.
 *
 * Atomic **directory** moves are owned by GenericPathMove (tryAtomicMove), not this strategy —
 * by the time [createDirectory] runs, child work items are already queued, and moving the
 * subtree out from under them would orphan those items.
 *
 * @see eu.darken.butler.common.files.saf.SAFPathMoveStrategy for comparison
 */
class LocalPathMoveStrategy(
    private val fileSystemOps: FileSystemOps<LocalPath, LocalPathLookup>
) : TransferStrategy<
    LocalPath, LocalPathLookup,  // Source types
    LocalPath, LocalPathLookup   // Destination types
    > {

    override suspend fun transferFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<LocalPath, LocalPath> {
        log(TAG, DEBUG) { "Moving file: ${sourceLookup.lookedUp} -> $destination" }

        // Handle symlinks specially - atomic move doesn't adjust relative targets
        if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
            return moveSymlink(sourceLookup, destination, onProgress, sourceOps, destOps)
        }

        // Try atomic move first (most efficient)
        when (val outcome = sourceOps.move(sourceLookup.lookedUp, destination)) {
            is MoveOutcome.Moved -> {
                log(TAG, DEBUG) { "Atomic move succeeded: ${sourceLookup.lookedUp} -> $destination" }

                onProgress(sourceLookup.size ?: 0L)

                // Lookup moved destination to avoid redundant stat in caller
                val destLookup = destOps.lookup(destination, LookupOptions.BASE)

                return TransferStrategy.TransferResult.Success(
                    source = sourceLookup.lookedUp,
                    destination = destination,
                    bytesTransferred = sourceLookup.size ?: 0L,
                    destinationLookup = destLookup
                )
            }

            is MoveOutcome.NotSupported ->
                log(TAG, DEBUG) { "Atomic move not supported (${outcome.reason}), falling back to copy+delete" }
        }

        // Atomic move not supported - use copy+delete fallback
        return copyAndDeleteFile(sourceLookup, destination, options, onProgress, sourceOps, destOps)
    }

    override suspend fun createDirectory(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult<LocalPath, LocalPath> {
        log(TAG, DEBUG) { "Creating directory: $destination" }

        // No atomic attempt here: GenericPathMove.tryAtomicMove owns atomic directory moves.
        // Create empty directory (children moved separately by GenericPathMove)
        // Parent exists due to GenericPathMove's depth-first traversal
        destOps.createDir(destination)

        // Lookup created destination to avoid redundant stat in caller
        val destLookup = destOps.lookup(destination, LookupOptions.BASE)

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = 0L,
            destinationLookup = destLookup
        )
    }

    private suspend fun moveSymlink(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>
    ): TransferStrategy.TransferResult<LocalPath, LocalPath> {
        log(TAG, DEBUG) { "Moving symlink: ${sourceLookup.lookedUp} -> $destination" }

        val linkTarget = sourceOps.readSymbolicLink(sourceLookup.lookedUp)
        log(TAG, DEBUG) { "Read symlink target: $linkTarget (isAbsolute=${linkTarget.file.isAbsolute})" }

        // Adjust symlink target if it's relative
        val newTarget = if (linkTarget.file.isAbsolute) {
            log(TAG, DEBUG) { "Target is absolute, using as-is" }
            linkTarget
        } else {
            log(TAG, DEBUG) { "Target is relative, adjusting for new location" }
            // Convert relative target to absolute
            val absoluteTarget = linkTarget.file.absoluteFile.toPath().normalize()
            log(TAG, DEBUG) { "Absolute target: $absoluteTarget" }

            // Make it relative to destination parent
            val destParent = destination.file.parentFile!!.absoluteFile
            val relativePath = destParent.toPath().relativize(absoluteTarget)
            log(TAG, DEBUG) { "Relative path from dest: $relativePath" }
            LocalPath.build(relativePath.toFile())
        }
        log(TAG, DEBUG) { "New symlink target: $newTarget" }

        // Create new symlink at destination
        log(TAG, DEBUG) { "Creating symlink at $destination pointing to $newTarget" }
        destOps.createSymlink(destination, newTarget)
        log(TAG, DEBUG) { "Symlink created successfully" }

        // Delete source
        log(TAG, DEBUG) { "Deleting source symlink: ${sourceLookup.lookedUp}" }
        if (!sourceOps.delete(sourceLookup.lookedUp, recursive = false)) {
            log(TAG, WARN) { "Failed to delete source symlink after copy: ${sourceLookup.lookedUp}" }
        }

        onProgress(sourceLookup.size ?: 0L)

        // Lookup moved destination to avoid redundant stat in caller
        val destLookup = destOps.lookup(destination, LookupOptions.BASE)

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = sourceLookup.size ?: 0L,
            destinationLookup = destLookup
        )
    }

    private suspend fun copyAndDeleteFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>
    ): TransferStrategy.TransferResult<LocalPath, LocalPath> {
        // move() refuses an occupied destination; the fallback's truncating copy must not then
        // silently overwrite it either — route it through conflict handling instead. The plain
        // lookup maps a FIFO, socket or device node to FileType.UNKNOWN, i.e. to "absent", which
        // is exactly what the strict probe tells apart.
        if (!options.overwrite) {
            when (destOps.existsStrict(destination)) {
                Existence.PRESENT -> throw PathAlreadyExistsException(path = destination)
                Existence.UNKNOWN -> throw WriteException("Cannot tell whether $destination exists", destination)
                Existence.ABSENT -> Unit
            }
        }

        var totalBytesTransferred = 0L

        if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
            // Symlink copy+delete
            val linkTarget = sourceOps.readSymbolicLink(sourceLookup.lookedUp)
            val newTarget = if (linkTarget.file.isAbsolute) {
                linkTarget
            } else {
                // Convert relative target to absolute
                val absoluteTarget = linkTarget.file.absoluteFile.toPath().normalize()

                // Make it relative to destination parent
                val destParent = destination.file.parentFile!!.absoluteFile
                val relativePath = destParent.toPath().relativize(absoluteTarget)
                LocalPath.build(relativePath.toFile())
            }
            destOps.createSymlink(destination, newTarget)
            totalBytesTransferred = sourceLookup.size ?: 0L
        } else {
            // Regular file copy with progress tracking
            sourceOps.openInputStream(sourceLookup.lookedUp).source().buffer().use { source ->
                destOps.openOutputStream(destination).sink().buffer().use { sink ->
                    val buffer = okio.Buffer()
                    var bytesRead: Long

                    while (source.read(buffer, BUFFER_SIZE.toLong()).also { bytesRead = it } != -1L) {
                        sink.write(buffer, bytesRead)
                        totalBytesTransferred += bytesRead
                        onProgress(bytesRead)
                    }
                    sink.flush()
                }
            }

            // Copy file attributes if requested
            if (options.preserveAttributes) {
                copyAttributes(sourceLookup, destination, sourceOps, destOps)
            }
        }

        // Delete source after successful copy
        if (!sourceOps.delete(sourceLookup.lookedUp, recursive = false)) {
            // Destination is complete; a surviving source is an annoyance, not data loss
            log(TAG, WARN) { "Failed to delete source after copy: ${sourceLookup.lookedUp}" }
        }

        // Lookup moved destination to avoid redundant stat in caller
        val destLookup = destOps.lookup(destination, LookupOptions.BASE)

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = totalBytesTransferred,
            destinationLookup = destLookup
        )
    }

    private suspend fun copyAttributes(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>
    ) {
        try {
            // Re-lookup with MAX if permissions not already fetched
            val lookupWithAttributes = if (sourceLookup.permissions == null) {
                sourceOps.lookup(sourceLookup.lookedUp, LookupOptions.MAX)
            } else {
                sourceLookup
            }

            // Set modified time
            lookupWithAttributes.modifiedAt?.let { destOps.setModifiedAt(destination, it) }

            // Copy POSIX permissions if available
            lookupWithAttributes.permissions?.let { permissions ->
                destOps.setPermissions(destination, permissions)
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to copy attributes: $e" }
        }
    }

    companion object {
        private val TAG = logTag("PathOperation", "MoveStrategy")
        private const val BUFFER_SIZE = 64 * 1024 // 64KB chunks
    }
}
