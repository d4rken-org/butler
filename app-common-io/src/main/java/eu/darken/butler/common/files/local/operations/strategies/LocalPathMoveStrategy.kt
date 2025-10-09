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
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributeView

/**
 * Strategy for moving files and directories.
 *
 * Attempts atomic moves when possible, falls back to copy+delete for cross-device moves.
 * Handles symlinks by recreating them at the destination with adjusted targets.
 */
class LocalPathMoveStrategy : TransferStrategy {

    override suspend fun transferFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult {
        log(TAG, DEBUG) { "Moving file: ${sourceLookup.lookedUp} -> $destination" }

        val sourcePath = sourceLookup.lookedUp.toNioPath()

        // Handle symlinks specially - atomic move doesn't adjust relative targets
        if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
            return moveSymlink(sourceLookup, destination, onProgress)
        }

        // Try atomic move first (most efficient)
        try {
            Files.move(sourcePath, destination.toNioPath(), LinkOption.NOFOLLOW_LINKS)
            log(TAG, DEBUG) { "Atomic move succeeded: ${sourceLookup.lookedUp} -> $destination" }

            onProgress(sourceLookup.size)

            return TransferStrategy.TransferResult.Success(
                source = sourceLookup.lookedUp,
                destination = destination,
                bytesTransferred = sourceLookup.size
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            log(TAG, DEBUG) { "Atomic move not supported, falling back to copy+delete" }
            // Fall through to copy+delete
        }

        // Atomic move failed - use copy+delete fallback
        return copyAndDeleteFile(sourceLookup, destination, options, onProgress)
    }

    override suspend fun createDirectory(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult {
        log(TAG, DEBUG) { "Creating directory: $destination" }

        Files.createDirectories(destination.toNioPath())

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = 0L
        )
    }

    private suspend fun moveSymlink(
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
            // Relative symlink targets are relative to CWD, not to the link's directory
            // Resolve to absolute path first, then make relative to destination
            val absoluteTarget = java.nio.file.Paths.get("").toAbsolutePath().resolve(linkTarget).normalize()
            val absoluteDest = destination.toNioPath().parent.toAbsolutePath().normalize()
            absoluteDest.relativize(absoluteTarget)
        }

        // Create new symlink at destination
        Files.createSymbolicLink(destination.toNioPath(), newTarget)

        // Delete source
        Files.delete(sourcePath)

        onProgress(sourceLookup.size)

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = sourceLookup.size
        )
    }

    private suspend fun copyAndDeleteFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult {
        val sourcePath = sourceLookup.lookedUp.toNioPath()
        var totalBytesTransferred = 0L

        if (sourceLookup.fileType == FileType.SYMBOLIC_LINK) {
            // Symlink copy+delete
            val linkTarget = Files.readSymbolicLink(sourcePath)
            val newTarget = if (linkTarget.isAbsolute) {
                linkTarget
            } else {
                val absoluteTarget = java.nio.file.Paths.get("").toAbsolutePath().resolve(linkTarget).normalize()
                val absoluteDest = destination.toNioPath().parent.toAbsolutePath().normalize()
                absoluteDest.relativize(absoluteTarget)
            }
            Files.createSymbolicLink(destination.toNioPath(), newTarget)
            totalBytesTransferred = sourceLookup.size
        } else {
            // Regular file copy with progress tracking
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
        }

        // Delete source after successful copy
        Files.delete(sourcePath)

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
        private val TAG = logTag("PathOperation", "MoveStrategy")
        private const val BUFFER_SIZE = 64 * 1024 // 64KB chunks
    }
}
