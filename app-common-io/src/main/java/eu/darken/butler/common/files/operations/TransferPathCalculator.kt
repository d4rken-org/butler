package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath

/**
 * Shared path calculation utilities for copy and move operations.
 *
 * Provides Unix cp/mv semantics for destination path calculation, handling
 * both single and multiple source scenarios, with support for rename semantics.
 *
 * ## Unix cp/mv Semantics
 *
 * Single source scenarios:
 * - Source + existing directory dest: copy/move INTO directory
 * - Source + non-existent dest: use as final path (rename)
 *
 * Multiple source scenarios:
 * - Always copy/move INTO destination directory
 *
 * ## Path Adjustments
 *
 * Supports adjusting paths when directories are renamed during conflict resolution,
 * ensuring child paths are updated to reflect parent renames.
 *
 * ## Usage Pattern
 *
 * ```kotlin
 * private val pathCalculator = TransferPathCalculator()
 * val destPath = pathCalculator.calculateDestinationPath(...)
 * ```
 */
class TransferPathCalculator {

    /**
     * Calculates the destination path for a source file/directory.
     *
     * Implements Unix cp/mv semantics:
     * - Single source + destination is existing directory: copy INTO it (append name)
     * - Single source + destination doesn't exist: use as final path (rename)
     * - Multiple sources: always copy INTO destination directory
     *
     * ## Examples
     *
     * ```
     * // Single source, destination exists as directory
     * calculateDestinationPath(
     *     source = /home/user/file.txt,
     *     topLevelSource = /home/user/file.txt,
     *     destination = /backup/,
     *     sources = [/home/user/file.txt],
     *     destinationExistedAsDirectory = true
     * ) → /backup/file.txt
     *
     * // Single source, destination doesn't exist (rename)
     * calculateDestinationPath(
     *     source = /home/user/oldname.txt,
     *     topLevelSource = /home/user/oldname.txt,
     *     destination = /backup/newname.txt,
     *     sources = [/home/user/oldname.txt],
     *     destinationExistedAsDirectory = false
     * ) → /backup/newname.txt
     *
     * // Multiple sources
     * calculateDestinationPath(
     *     source = /home/user/file1.txt,
     *     topLevelSource = /home/user/file1.txt,
     *     destination = /backup/,
     *     sources = [/home/user/file1.txt, /home/user/file2.txt],
     *     destinationExistedAsDirectory = true
     * ) → /backup/file1.txt
     *
     * // Child of source (nested path)
     * calculateDestinationPath(
     *     source = /home/user/folder/subfolder/file.txt,
     *     topLevelSource = /home/user/folder,
     *     destination = /backup/,
     *     sources = [/home/user/folder],
     *     destinationExistedAsDirectory = true
     * ) → /backup/folder/subfolder/file.txt
     * ```
     *
     * @param P Path type (LocalPath, SAFPath, etc.)
     * @param DP Destination path type (may be same or different from source)
     * @param source Current source path being processed
     * @param topLevelSource Top-level source (for relative path calculation)
     * @param destination Base destination path
     * @param sources Collection of all top-level sources
     * @param destinationExistedAsDirectory Whether destination existed as directory before operation
     * @return Calculated destination path
     */
    fun <P : APath<P>, DP : APath<DP>> calculateDestinationPath(
        source: P,
        topLevelSource: P,
        destination: DP,
        sources: Collection<P>,
        destinationExistedAsDirectory: Boolean
    ): DP {
        if (sources.size == 1 && source == topLevelSource && !destinationExistedAsDirectory) {
            // Single source + destination didn't exist as directory: use as final path (rename)
            return destination
        }

        // Multiple sources or processing children: append relative path to destination
        val topLevelSegments = topLevelSource.segments
        val sourceSegments = source.segments

        // Drop parent segments of top-level source
        val segmentsToDrop = if (topLevelSegments.isEmpty()) {
            0
        } else if (sources.size == 1 && !destinationExistedAsDirectory) {
            // Rename semantics: drop ALL top-level segments (including the name itself)
            topLevelSegments.size
        } else {
            // Copy INTO semantics: drop parent segments, keep top-level name
            topLevelSegments.size - 1
        }
        val relativeSegments = sourceSegments.drop(segmentsToDrop)

        // Build destination path with relative segments
        return destination.child(*relativeSegments.toTypedArray())
    }

    /**
     * Adjusts destination path to account for renamed ancestor directories.
     *
     * When a directory is renamed during conflict resolution, all its children
     * must have their destination paths adjusted to reflect the new parent path.
     *
     * ## Example
     *
     * ```
     * // Directory was renamed: /dest/folder → /dest/folder (1)
     * renamedSourceDirs = {/src/folder: /dest/folder (1)}
     *
     * // Child file adjustment
     * adjustDestinationForRenames(
     *     dest = /dest/folder/file.txt,
     *     source = /src/folder/file.txt,
     *     renamedSourceDirs = renamedSourceDirs
     * ) → /dest/folder (1)/file.txt
     * ```
     *
     * @param P Source path type
     * @param DP Destination path type
     * @param dest Original destination path (before adjustment)
     * @param source Source path to check for renamed ancestors
     * @param renamedSourceDirs Map of source paths to their renamed destinations
     * @return Adjusted destination path (may be same as input if no renames apply)
     */
    fun <P : APath<P>, DP : APath<DP>> adjustDestinationForRenames(
        dest: DP,
        source: P,
        renamedSourceDirs: Map<P, DP>
    ): DP {
        // Find the most specific (longest path) renamed ancestor using maxByOrNull
        val bestMatch = renamedSourceDirs
            .filter { (renamedSource, _) ->
                source.path == renamedSource.path || source.path.startsWith(renamedSource.path + "/")
            }
            .maxByOrNull { (renamedSource, _) -> renamedSource.path.length }

        // Apply the best match if found
        return bestMatch?.let { (renamedSource, renamedDest) ->
            // Calculate the relative path from the renamed source
            val relativePath = source.path.removePrefix(renamedSource.path).removePrefix("/")

            if (relativePath.isEmpty()) {
                // Source is the renamed directory itself
                renamedDest
            } else {
                // Source is a child - append relative path to renamed dest
                val segments = relativePath.split("/").filter { it.isNotEmpty() }
                renamedDest.child(*segments.toTypedArray())
            }
        } ?: dest
    }

    /**
     * Checks if a path is a descendant of any skipped directory.
     *
     * When a directory is skipped during transfer, all its children should
     * also be skipped automatically. This method checks if a given path is
     * within any skipped directory tree.
     *
     * ## Example
     *
     * ```
     * skippedSourceDirs = [/src/folder1, /src/folder2]
     *
     * isDescendantOfSkippedDir(/src/folder1/file.txt, skippedSourceDirs) → true
     * isDescendantOfSkippedDir(/src/folder1/sub/file.txt, skippedSourceDirs) → true
     * isDescendantOfSkippedDir(/src/folder3/file.txt, skippedSourceDirs) → false
     * ```
     *
     * @param P Path type
     * @param path Path to check
     * @param skippedSourceDirs Set of skipped directory paths
     * @return true if path is within a skipped directory
     */
    fun <P : APath<P>> isDescendantOfSkippedDir(
        path: P,
        skippedSourceDirs: Set<P>
    ): Boolean = skippedSourceDirs.any { skippedDir ->
        // Exact match or descendant (requires path separator after skipped dir)
        path.path == skippedDir.path || path.path.startsWith("${skippedDir.path}/")
    }
}
