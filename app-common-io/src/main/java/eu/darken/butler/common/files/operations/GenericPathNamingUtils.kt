package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath

/**
 * Utility for generating unique filenames in generic path operations.
 *
 * Provides intelligent name conflict resolution that works with any APath type
 * (LocalPath, SAFPath, RootPath, etc.) using the FileSystemOps abstraction.
 */
object GenericPathNamingUtils {

    /**
     * Generates a unique filename by appending (1), (2), etc. until no conflict exists.
     *
     * Intelligently handles files that already have numbering:
     * - "file.txt" exists → returns "file (1).txt"
     * - "file (1).txt" exists → returns "file (2).txt" (not "file (1) (1).txt")
     * - "folder" exists → returns "folder (1)"
     *
     * ## Algorithm
     *
     * 1. Parse filename into base name and extension
     * 2. Detect existing " (N)" pattern in base name
     * 3. If pattern exists, extract N and start checking from N+1
     * 4. If no pattern, start from 1
     * 5. Check each candidate with ops.exists() until unique name found
     *
     * ## Examples
     *
     * ```
     * generateUniqueName("/dest/file.txt", ops)     // No conflict
     * → returns "file.txt"
     *
     * generateUniqueName("/dest/file.txt", ops)     // file.txt exists
     * → checks file.txt, finds conflict
     * → returns "file (1).txt"
     *
     * generateUniqueName("/dest/file (5).txt", ops) // file (5).txt exists
     * → detects "(5)" pattern, extracts base="file", N=5
     * → checks file (6).txt, file (7).txt, ...
     * → returns first available "file (N).txt"
     * ```
     *
     * @param parentPath The parent directory where the file will be created
     * @param originalName The original filename to make unique
     * @param ops FileSystemOps instance to check for existing paths
     * @return A unique filename that doesn't conflict with existing files
     */
    suspend fun <P : APath> generateUniqueName(
        parentPath: P,
        originalName: String,
        ops: FileSystemOps<P, *, *>
    ): String {
        // Check if original name is already unique
        @Suppress("UNCHECKED_CAST")
        val testPath = parentPath.child(originalName) as P
        if (!ops.exists(testPath)) return originalName

        // Split into base name and extension
        val lastDotIndex = originalName.lastIndexOf('.')
        val baseName: String
        val extension: String

        if (lastDotIndex > 0 && lastDotIndex < originalName.length - 1) {
            // Has extension (e.g., "file.txt" → base="file", ext=".txt")
            baseName = originalName.take(lastDotIndex)
            extension = originalName.substring(lastDotIndex) // includes the dot
        } else {
            // No extension (e.g., "folder" or ".hiddenfile")
            baseName = originalName
            extension = ""
        }

        // Check if baseName already ends with " (N)" pattern
        // Regex matches: "file (5)" → groups: ["file (5)", "file", "5"]
        val numberPattern = Regex("""^(.+)\s\((\d+)\)$""")
        val match = numberPattern.matchEntire(baseName)

        val actualBase: String
        val startCounter: Int

        if (match != null) {
            // Already has numbering: "file (5)" → base="file", start from 6
            actualBase = match.groupValues[1]
            startCounter = match.groupValues[2].toInt() + 1
        } else {
            // No numbering yet: "file" → base="file", start from 1
            actualBase = baseName
            startCounter = 1
        }

        // Try incrementing counter until we find a unique name
        var counter = startCounter
        do {
            val candidateName = "$actualBase ($counter)$extension"

            @Suppress("UNCHECKED_CAST")
            val candidatePath = parentPath.child(candidateName) as P
            if (!ops.exists(candidatePath)) {
                return candidateName
            }
            counter++
        } while (counter < 10000) // Safety limit to prevent infinite loops

        // Fallback if we somehow hit the limit (extremely unlikely)
        return "$actualBase (${System.currentTimeMillis()})$extension"
    }
}
