package eu.darken.butler.common.files.local.operations.strategies

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.FileSystemOps
import okio.buffer
import okio.sink
import okio.source

/**
 * Strategy for copying files and directories using LocalPath.
 *
 * Uses java.nio.Files for efficient copying with optional attribute preservation.
 * Handles symlinks based on options:
 * - followSymlinks=true: Copies the target file/directory
 * - followSymlinks=false: Recreates the symlink at the destination
 *
 * Note: This implements both the old TransferStrategy (for backward compatibility)
 * and the new generic TransferStrategy<LocalPath, LocalPathLookup> interface.
 */
class LocalPathCopyStrategy(private val fileSystemOps: LocalFileSystemOps) :
    TransferStrategy,
    eu.darken.butler.common.files.operations.TransferStrategy<
        LocalPath, LocalPathLookup, LocalPathLookupExtended,  // Source types
        LocalPath, LocalPathLookup, LocalPathLookupExtended   // Destination types
    > {

    // Old interface implementation (backward compatibility)
    override suspend fun transferFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult {
        return transferFileInternal(sourceLookup, destination, options, onProgress, fileSystemOps, fileSystemOps)
    }

    // New generic interface implementation
    override suspend fun transferFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        options: eu.darken.butler.common.files.operations.TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
        // Convert generic options to local options
        val localOptions = TransferStrategy.Options(
            preserveAttributes = options.preserveAttributes,
            followSymlinks = options.followSymlinks
        )
        val result = transferFileInternal(sourceLookup, destination, localOptions, onProgress, sourceOps, destOps)
        // Convert result to generic type
        return when (result) {
            is TransferStrategy.TransferResult.Success ->
                eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
                    source = result.source,
                    destination = result.destination,
                    bytesTransferred = result.bytesTransferred
                )
            is TransferStrategy.TransferResult.Skipped ->
                eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Skipped(
                    source = result.source,
                    reason = result.reason
                )
        }
    }

    // Shared implementation used by both interfaces
    private suspend fun transferFileInternal(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    ): TransferStrategy.TransferResult {
        log(TAG, DEBUG) { "Copying file: ${sourceLookup.lookedUp} -> $destination" }

        // Handle symlinks based on options
        if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
            return if (options.followSymlinks) {
                copySymlinkTarget(sourceLookup, destination, options, onProgress, sourceOps, destOps)
            } else {
                copySymlink(sourceLookup, destination, onProgress, sourceOps, destOps)
            }
        }

        // Regular file copy with progress tracking
        return copyRegularFile(sourceLookup, destination, options, onProgress, sourceOps, destOps)
    }

    // Old interface implementation (backward compatibility)
    override suspend fun createDirectory(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult {
        return createDirectoryInternal(sourceLookup, destination, options, fileSystemOps, fileSystemOps)
    }

    // New generic interface implementation
    override suspend fun createDirectory(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        options: eu.darken.butler.common.files.operations.TransferStrategy.Options
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
        // Convert generic options to local options
        val localOptions = TransferStrategy.Options(
            preserveAttributes = options.preserveAttributes,
            followSymlinks = options.followSymlinks
        )
        val result = createDirectoryInternal(sourceLookup, destination, localOptions, sourceOps, destOps)
        // Convert result to generic type
        return when (result) {
            is TransferStrategy.TransferResult.Success ->
                eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
                    source = result.source,
                    destination = result.destination,
                    bytesTransferred = result.bytesTransferred
                )
            is TransferStrategy.TransferResult.Skipped ->
                eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Skipped(
                    source = result.source,
                    reason = result.reason
                )
        }
    }

    // Shared implementation used by both interfaces
    private suspend fun createDirectoryInternal(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    ): TransferStrategy.TransferResult {
        log(TAG, DEBUG) { "Creating directory: $destination" }

        // If the source is a symlink and we're not following symlinks, recreate the symlink
        if (sourceLookup.fileType == FileType.SYMBOLIC_LINK && !options.followSymlinks) {
            val linkTarget = sourceOps.readSymbolicLink(sourceLookup.lookedUp)

            // Adjust symlink target if it's relative
            val newTarget = if (linkTarget.file.isAbsolute) {
                linkTarget
            } else {
                val sourceParent = sourceLookup.lookedUp.file.parentFile!!
                val absoluteTarget = sourceParent.resolve(linkTarget.file.path).normalize()
                val destParent = destination.file.parentFile!!
                val relativePath = destParent.toPath().relativize(absoluteTarget.toPath())
                LocalPath.build(relativePath.toFile())
            }

            // Delete existing destination if present (conflict resolution happens before this)
            if (destOps.exists(destination)) {
                destOps.delete(destination, recursive = false)
            }

            // Create symlink at destination
            destOps.createSymlink(destination, newTarget)
        } else {
            // Create regular directory
            destOps.createDir(destination)
        }

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = 0L
        )
    }

    private suspend fun copySymlink(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    ): TransferStrategy.TransferResult {
        val linkTarget = sourceOps.readSymbolicLink(sourceLookup.lookedUp)

        // Adjust symlink target if it's relative
        val newTarget = if (linkTarget.file.isAbsolute) {
            linkTarget
        } else {
            val sourceParent = sourceLookup.lookedUp.file.parentFile!!
            val absoluteTarget = sourceParent.resolve(linkTarget.file.path).normalize()
            val destParent = destination.file.parentFile!!
            val relativePath = destParent.toPath().relativize(absoluteTarget.toPath())
            LocalPath.build(relativePath.toFile())
        }

        // Delete existing destination if present (conflict resolution happens before this)
        if (destOps.exists(destination)) {
            destOps.delete(destination, recursive = false)
        }

        // Create new symlink at destination
        destOps.createSymlink(destination, newTarget)

        onProgress(sourceLookup.size)

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = sourceLookup.size
        )
    }

    private suspend fun copySymlinkTarget(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    ): TransferStrategy.TransferResult {
        // Read the symlink target
        val linkTarget = sourceOps.readSymbolicLink(sourceLookup.lookedUp)

        // Resolve to absolute path
        val resolvedPath = if (linkTarget.file.isAbsolute) {
            linkTarget
        } else {
            val sourceParent = sourceLookup.lookedUp.file.parentFile!!
            LocalPath.build(sourceParent.resolve(linkTarget.file.path).normalize())
        }

        // Copy the resolved target using stream-based approach
        sourceOps.openInputStream(resolvedPath).source().buffer().use { source ->
            destOps.openOutputStream(destination).sink().buffer().use { sink ->
                source.readAll(sink)
                sink.flush()
            }
        }

        // Copy attributes if requested
        if (options.preserveAttributes) {
            copyAttributes(resolvedPath, destination, sourceOps, destOps)
        }

        onProgress(sourceLookup.size)

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = sourceLookup.size
        )
    }

    private suspend fun copyRegularFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    ): TransferStrategy.TransferResult {
        var totalBytesTransferred = 0L

        // Chunked file copy with progress tracking
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

        return TransferStrategy.TransferResult.Success(
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
        private val TAG = logTag("PathOperation", "CopyStrategy")
        private const val BUFFER_SIZE = 64 * 1024 // 64KB chunks
    }
}
