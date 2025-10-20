package eu.darken.butler.common.files.local.operations.strategies

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
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
 * ## Comparison with SAF
 *
 * | Feature | LocalPath | SAFPath |
 * |---------|-----------|---------|
 * | Copy API | `Files.copy()` + streams | ContentResolver streams |
 * | Attributes | `Files.copyAttributes()` | Limited (modTime only) |
 * | Symlinks | Supported | Not supported |
 * | Atomic ops | Available | Not available |
 *
 * @see eu.darken.butler.common.files.saf.SAFPathCopyStrategy for comparison
 */
class LocalPathCopyStrategy(
    private val fileSystemOps: LocalFileSystemOps
) : eu.darken.butler.common.files.operations.TransferStrategy<
    LocalPath, LocalPathLookup,  // Source types
    LocalPath, LocalPathLookup   // Destination types
    > {

    override suspend fun transferFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>,
        options: eu.darken.butler.common.files.operations.TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
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

    override suspend fun createDirectory(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>,
        options: eu.darken.butler.common.files.operations.TransferStrategy.Options
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
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

            // Create symlink at destination
            destOps.createSymlink(destination, newTarget)
        } else {
            // Create regular directory
            // Parent exists due to GenericPathCopy's depth-first traversal
            destOps.createDir(destination)
        }

        return eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = 0L
        )
    }

    private suspend fun copySymlink(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
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

        // Create new symlink at destination
        destOps.createSymlink(destination, newTarget)

        onProgress(sourceLookup.size ?: 0L)

        return eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = sourceLookup.size ?: 0L
        )
    }

    private suspend fun copySymlinkTarget(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: eu.darken.butler.common.files.operations.TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
        // Read the symlink target
        val linkTarget = sourceOps.readSymbolicLink(sourceLookup.lookedUp)

        // Resolve to absolute path
        val resolvedPath = if (linkTarget.file.isAbsolute) {
            linkTarget
        } else {
            val sourceParent = sourceLookup.lookedUp.file.parentFile!!
            LocalPath.build(sourceParent.resolve(linkTarget.file.path).normalize())
        }

        // Target is a file - copy using stream-based approach
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

        onProgress(sourceLookup.size ?: 0L)

        return eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = sourceLookup.size ?: 0L
        )
    }

    private suspend fun copyRegularFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: eu.darken.butler.common.files.operations.TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>
    ): eu.darken.butler.common.files.operations.TransferStrategy.TransferResult<LocalPath, LocalPath> {
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

        return eu.darken.butler.common.files.operations.TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = totalBytesTransferred
        )
    }

    private suspend fun copyAttributes(
        source: LocalPath,
        destination: LocalPath,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup>,
        destOps: FileSystemOps<LocalPath, LocalPathLookup>
    ) {
        try {
            // Get source attributes
            val sourceLookup = sourceOps.lookup(source, LookupOptions.MAX)

            // Set modified time
            sourceLookup.modifiedAt?.let { destOps.setModifiedAt(destination, it) }

            // Copy POSIX permissions if available
            sourceLookup.permissions?.let { permissions ->
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
