package eu.darken.butler.common.files.local.operations.strategies

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.toNioPath
import eu.darken.butler.common.files.metadata.FileType
import okio.buffer
import okio.sink
import okio.source
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView

/**
 * Strategy for copying files and directories.
 *
 * Copies files while preserving the source. Handles symlinks based on options:
 * - followSymlinks=true: Copies the target file/directory
 * - followSymlinks=false: Recreates the symlink at the destination
 */
class LocalPathCopyStrategy : TransferStrategy {

    override suspend fun transferFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult {
        log(TAG, DEBUG) { "Copying file: ${sourceLookup.lookedUp} -> $destination" }

        sourceLookup.lookedUp.toNioPath()

        // Handle symlinks based on options
        if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
            return if (options.followSymlinks) {
                copySymlinkTarget(sourceLookup, destination, options, onProgress)
            } else {
                copySymlink(sourceLookup, destination, onProgress)
            }
        }

        // Regular file copy with progress tracking
        return copyRegularFile(sourceLookup, destination, options, onProgress)
    }

    override suspend fun createDirectory(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult {
        log(TAG, DEBUG) { "Creating directory: $destination" }

        // If the source is a symlink and we're not following symlinks, recreate the symlink
        if (sourceLookup.fileType == FileType.SYMBOLIC_LINK && !options.followSymlinks) {
            val sourcePath = sourceLookup.lookedUp.toNioPath()
            val linkTarget = Files.readSymbolicLink(sourcePath)

            // Adjust symlink target if it's relative
            val newTarget = if (linkTarget.isAbsolute) {
                linkTarget
            } else {
                val absoluteTarget = sourcePath.parent.resolve(linkTarget).normalize()
                destination.toNioPath().parent.relativize(absoluteTarget)
            }

            // Delete existing destination if present (conflict resolution happens before this)
            if (Files.exists(destination.toNioPath())) {
                Files.delete(destination.toNioPath())
            }

            Files.createSymbolicLink(destination.toNioPath(), newTarget)
        } else {
            // Create regular directory
            Files.createDirectories(destination.toNioPath())
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
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult {
        val sourcePath = sourceLookup.lookedUp.toNioPath()
        val linkTarget = Files.readSymbolicLink(sourcePath)

        // Adjust symlink target if it's relative
        val newTarget = if (linkTarget.isAbsolute) {
            linkTarget
        } else {
            val absoluteTarget = sourcePath.parent.resolve(linkTarget).normalize()
            destination.toNioPath().parent.relativize(absoluteTarget)
        }

        // Delete existing destination if present (conflict resolution happens before this)
        if (Files.exists(destination.toNioPath())) {
            Files.delete(destination.toNioPath())
        }

        // Create new symlink at destination
        Files.createSymbolicLink(destination.toNioPath(), newTarget)

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
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult {
        val sourcePath = sourceLookup.lookedUp.toNioPath()
        val linkTarget = Files.readSymbolicLink(sourcePath)
        val resolvedPath = sourcePath.parent.resolve(linkTarget).normalize()

        // Copy the resolved target (file or directory)
        Files.copy(
            resolvedPath,
            destination.toNioPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES
        )

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
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult {
        val sourcePath = sourceLookup.lookedUp.toNioPath()
        var totalBytesTransferred = 0L

        // Chunked file copy with progress tracking
        Files.newInputStream(sourcePath).source().buffer().use { source ->
            Files.newOutputStream(destination.toNioPath()).sink().buffer().use { sink ->
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
            copyAttributes(sourcePath, destination)
        }

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = totalBytesTransferred
        )
    }

    private fun copyAttributes(sourcePath: java.nio.file.Path, destination: LocalPath) {
        try {
            val lastModified = Files.getLastModifiedTime(sourcePath)
            Files.setLastModifiedTime(destination.toNioPath(), lastModified)

            // Copy POSIX permissions if available
            if (Files.getFileAttributeView(sourcePath, PosixFileAttributeView::class.java) != null) {
                val permissions = Files.getPosixFilePermissions(sourcePath)
                Files.setPosixFilePermissions(destination.toNioPath(), permissions)
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
