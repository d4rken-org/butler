package eu.darken.butler.common.files.local.operations.strategies

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import eu.darken.butler.common.files.metadata.FileType
import okio.buffer
import okio.sink
import okio.source

/**
 * Strategy for moving files and directories.
 *
 * Attempts atomic moves when possible, falls back to copy+delete for cross-device moves.
 * Handles symlinks by recreating them at the destination with adjusted targets.
 *
 * ## Comparison with SAF
 *
 * | Feature | LocalPath | SAFPath |
 * |---------|-----------|---------|
 * | Atomic move | Yes (same filesystem) | No |
 * | Fallback | Copy+delete | Always copy+delete |
 * | Performance | Fast (rename) | Slower (full copy) |
 * | Symlinks | Supported with target adjustment | Not supported |
 *
 * @see eu.darken.butler.common.files.saf.SAFPathMoveStrategy for comparison
 */
class LocalPathMoveStrategy(
    private val fileSystemOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
) : eu.darken.butler.common.files.operations.TransferStrategy<
    LocalPath, LocalPathLookup, LocalPathLookupExtended,  // Source types
    LocalPath, LocalPathLookup, LocalPathLookupExtended   // Destination types
    > {

    override suspend fun transferFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        options: eu.darken.butler.common.files.operations.TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
        log(TAG, DEBUG) { "Moving file: ${sourceLookup.lookedUp} -> $destination" }

        // Handle symlinks specially - atomic move doesn't adjust relative targets
        if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
            return moveSymlink(sourceLookup, destination, onProgress, sourceOps, destOps)
        }

        // Try atomic move first (most efficient)
        try {
            sourceOps.move(sourceLookup.lookedUp, destination)
            log(TAG, DEBUG) { "Atomic move succeeded: ${sourceLookup.lookedUp} -> $destination" }

            onProgress(sourceLookup.size)

            return eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
                source = sourceLookup.lookedUp,
                destination = destination,
                bytesTransferred = sourceLookup.size
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            log(TAG, DEBUG) { "Atomic move not supported, falling back to copy+delete" }
            // Fall through to copy+delete
        }

        // Atomic move failed - use copy+delete fallback
        return copyAndDeleteFile(sourceLookup, destination, options, onProgress, sourceOps, destOps)
    }

    override suspend fun createDirectory(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        options: eu.darken.butler.common.files.operations.TransferStrategy.Options
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
        log(TAG, DEBUG) { "Creating directory: $destination" }

        // Parent exists due to GenericPathMove's depth-first traversal
        destOps.createDir(destination)

        return eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = 0L
        )
    }

    private suspend fun moveSymlink(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
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
        sourceOps.delete(sourceLookup.lookedUp, recursive = false)
        log(TAG, DEBUG) { "Source symlink deleted" }

        onProgress(sourceLookup.size)

        return eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = sourceLookup.size
        )
    }

    private suspend fun copyAndDeleteFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: eu.darken.butler.common.files.operations.TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
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
            totalBytesTransferred = sourceLookup.size
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
                copyAttributes(sourceLookup.lookedUp, destination, sourceOps, destOps)
            }
        }

        // Delete source after successful copy
        sourceOps.delete(sourceLookup.lookedUp, recursive = false)

        return eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = totalBytesTransferred
        )
    }

    private suspend fun copyAttributes(
        source: LocalPath,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    ) {
        try {
            // Get source attributes
            val sourceLookup = sourceOps.lookup(source)
            val sourceExtended = sourceOps.lookupExtended(source)

            // Set modified time
            destOps.setModifiedAt(destination, sourceLookup.modifiedAt)

            // Copy POSIX permissions if available
            sourceExtended.permissions?.let { permissions ->
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
