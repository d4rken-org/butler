package eu.darken.butler.common.files.local.operations.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.isAncestorOf
import eu.darken.butler.common.files.local.relativeSegmentsTo
import eu.darken.butler.common.files.local.toNioPath
import java.io.File
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
}
