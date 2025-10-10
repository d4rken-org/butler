package eu.darken.butler.common.files.local.operations.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.isAncestorOf
import eu.darken.butler.common.files.local.performLookup
import eu.darken.butler.common.files.local.relativeSegmentsTo
import eu.darken.butler.common.files.local.toNioPath
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files

/**
 * Utility functions for path operations.
 *
 * These are stateless helper functions used across copy/move operations.
 */
object PathOperationUtils {

    /**
     * Generates a unique filename by appending (1), (2), etc. until no conflict exists.
     *
     * Examples:
     * - "file.txt" exists → returns "file (1).txt"
     * - "file (1).txt" exists → returns "file (2).txt"
     * - "folder" exists → returns "folder (1)"
     *
     * @param originalName The original filename
     * @param parentDir The parent directory to check for conflicts
     * @return A unique filename that doesn't conflict with existing files
     */
    fun generateUniqueName(originalName: String, parentDir: File): String {
        val file = File(parentDir, originalName)
        if (!file.exists()) return originalName

        // Split into base name and extension
        val nameParts = originalName.split('.')
        val baseName = if (nameParts.size > 1) {
            nameParts.dropLast(1).joinToString(".")
        } else {
            originalName
        }
        val extension = if (nameParts.size > 1) ".${nameParts.last()}" else ""

        // Try (1), (2), (3), etc. until we find a unique name
        var counter = 1
        var newName: String
        do {
            newName = "$baseName ($counter)$extension"
            counter++
        } while (File(parentDir, newName).exists())

        return newName
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * Walks the directory tree in reverse order (children before parents)
     * to ensure directories are empty before deletion.
     *
     * @param path The path to delete recursively
     */
    fun deleteRecursively(path: LocalPath) {
        if (!Files.exists(path.toNioPath())) return

        Files.walk(path.toNioPath())
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    /**
     * Adjusts a destination path based on parent directory renames.
     *
     * When a parent directory is renamed during a copy/move operation,
     * all children must be adjusted to use the new parent path.
     *
     * Example:
     * - Original: /dest/folder → /dest/folder (1)
     * - Child: /dest/folder/file.txt → /dest/folder (1)/file.txt
     *
     * @param dest The original destination path
     * @param source The source path being processed
     * @param renamedDirs Map of renamed directories (original → renamed)
     * @return Adjusted destination path accounting for parent renames
     */
    fun adjustDestinationForRenames(
        dest: LocalPath,
        source: LocalPath,
        renamedDirs: Map<LocalPath, LocalPath>
    ): LocalPath {
        // Find if any ancestor of the source was renamed
        return renamedDirs.entries.find { (renamedSource, _) ->
            renamedSource.isAncestorOf(source)
        }?.let { (renamedSource, newDestDir) ->
            // Calculate relative path from renamed source to current source
            val relativeSegments = renamedSource.relativeSegmentsTo(source)
            val relativePath = relativeSegments.joinToString(File.separator)
            LocalPath.build(File(newDestDir.file, relativePath))
        } ?: dest
    }

    /**
     * Checks if a source path is a descendant of any skipped directory.
     *
     * When a directory is skipped during copy/move, all its children
     * should also be skipped automatically.
     *
     * @param source The source path to check
     * @param skippedDirs Set of directories that were skipped
     * @return true if source is a child/descendant of a skipped directory
     */
    fun isDescendantOfSkippedDir(
        source: LocalPath,
        skippedDirs: Set<LocalPath>
    ): Boolean {
        return skippedDirs.any { skippedDir ->
            skippedDir.isAncestorOf(source)
        }
    }

    /**
     * Calculates the total size of a directory recursively.
     *
     * @param path The directory path
     * @return Total size in bytes
     */
    fun calculateDirectorySize(path: LocalPath): Long {
        return try {
            Files.walk(path.toNioPath())
                .filter { Files.isRegularFile(it) }
                .mapToLong { file ->
                    try {
                        Files.size(file)
                    } catch (e: Exception) {
                        0L
                    }
                }
                .sum()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Ensures the destination directory exists and is ready for copy/move operations.
     *
     * Handles various scenarios:
     * - Creates destination if it doesn't exist
     * - Validates destination is a directory
     * - Resolves conflicts if destination is a file
     *
     * @param destination The destination directory path
     * @param sources The source paths being copied/moved (used for conflict resolution)
     * @param onIssue Issue handler callback (null = strict mode, throws on issues)
     * @throws eu.darken.butler.common.files.errors.WriteException if destination cannot be created or is invalid
     */
    suspend fun ensureDestinationExists(
        destination: LocalPath,
        sources: Collection<LocalPath>,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ) {
        if (!Files.exists(destination.toNioPath())) {
            // Detect rename: single source with same parent directory as destination
            // - Same parent = RENAME (e.g., /dir/old.txt → /dir/new.txt)
            // - Different parent = MOVE (e.g., /dir1/file.txt → /dir2/ or /dir2/file.txt)
            val isRename = sources.size == 1 &&
                sources.first().file.parentFile?.absolutePath == destination.file.parentFile?.absolutePath
            val parent = destination.file.parentFile

            try {
                if (isRename && parent != null) {
                    // Rename case: ensure parent directory exists, but don't create destination
                    if (!parent.exists()) {
                        Files.createDirectories(parent.toPath())
                        log(TAG, DEBUG) { "Created parent directory for rename: $parent" }
                    } else {
                        log(TAG, DEBUG) { "Rename operation detected, parent exists: $parent" }
                    }
                } else {
                    // Move/copy to different parent: destination must exist
                    throw eu.darken.butler.common.files.errors.WriteException(
                        path = destination,
                        cause = java.io.IOException("Destination directory does not exist: ${destination.path}")
                    )
                }
            } catch (e: AccessDeniedException) {
                throw eu.darken.butler.common.files.errors.WriteException(
                    path = destination,
                    cause = e
                )
            } catch (e: SecurityException) {
                throw eu.darken.butler.common.files.errors.WriteException(
                    path = destination,
                    cause = e
                )
            }
            return
        }

        if (Files.isDirectory(destination.toNioPath())) {
            log(TAG, DEBUG) { "Destination is an existing directory: $destination" }
            return
        }

        log(TAG, WARN) { "Destination exists but is not a directory: $destination" }

        if (onIssue == null) {
            throw eu.darken.butler.common.files.errors.WriteException(
                path = destination,
                cause = java.io.IOException("Destination exists but is not a directory: ${destination.path}")
            )
        }

        val existsError = java.nio.file.FileAlreadyExistsException(destination.path)
        val destLookup = destination.performLookup()
        val sourceLookup = sources.first().performLookup()

        val issue = PathActionIssue.PathAlreadyExists(
            source = sourceLookup,
            destination = destLookup,
            canOverwrite = true,
            canRenameDestination = true,
            suggestedName = generateUniqueName(destination.name, destination.file.parentFile!!),
        )

        when (val resolution = onIssue.invoke(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
            is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                log(TAG, DEBUG) { "Overwriting file at destination: $destination" }
                Files.delete(destination.toNioPath())
            }
            is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                log(TAG, DEBUG) { "Renaming existing file: $destination -> ${resolution.newName}" }
                val newDestPath = LocalPath.build(File(destination.file.parentFile!!, resolution.newName))
                Files.move(destination.toNioPath(), newDestPath.toNioPath())
            }
            is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> throw kotlin.coroutines.cancellation.CancellationException(
                "User cancelled",
                existsError
            )
            is PathActionIssue.PathAlreadyExists.Resolution.Skip,
            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource,
            is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                throw UnsupportedOperationException("Invalid resolution for destination conflict", existsError)
            }
        }

        try {
            Files.createDirectories(destination.toNioPath())
        } catch (e: AccessDeniedException) {
            throw eu.darken.butler.common.files.errors.WriteException(
                path = destination,
                cause = e
            )
        } catch (e: SecurityException) {
            throw eu.darken.butler.common.files.errors.WriteException(
                path = destination,
                cause = e
            )
        }
    }

    private val TAG = logTag("Gateway", "LocalPath", "Utils")
}
