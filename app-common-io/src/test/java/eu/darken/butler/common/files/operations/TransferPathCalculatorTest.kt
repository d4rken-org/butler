package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for TransferPathCalculator - Unix cp/mv path semantics and adjustments.
 *
 * Verifies that the calculator correctly:
 * - Implements Unix cp/mv semantics for single and multiple sources
 * - Handles rename operations (destination doesn't exist)
 * - Adjusts paths for renamed ancestor directories
 * - Checks if paths are descendants of skipped directories
 */
class TransferPathCalculatorTest : BaseTest() {

    private val calculator = TransferPathCalculator()

    // ============ SINGLE SOURCE - DESTINATION IS DIRECTORY ============

    @Test
    fun `single source - destination exists as directory - appends source name`() {
        // Given
        val source = LocalPath.build("/home/user/file.txt")
        val topLevelSource = source
        val destination = LocalPath.build("/backup")
        val sources = listOf(source)
        val destinationExistedAsDirectory = true

        // When
        val result = calculator.calculateDestinationPath(
            source = source,
            topLevelSource = topLevelSource,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )

        // Then - appends source name to destination
        result shouldBe LocalPath.build("/backup/file.txt")
    }

    // ============ SINGLE SOURCE - DESTINATION DOESN'T EXIST (RENAME) ============

    @Test
    fun `single source - destination doesn't exist - uses as final path (rename)`() {
        // Given
        val source = LocalPath.build("/home/user/oldname.txt")
        val topLevelSource = source
        val destination = LocalPath.build("/backup/newname.txt")
        val sources = listOf(source)
        val destinationExistedAsDirectory = false

        // When
        val result = calculator.calculateDestinationPath(
            source = source,
            topLevelSource = topLevelSource,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )

        // Then - uses destination as-is (rename semantics)
        result shouldBe LocalPath.build("/backup/newname.txt")
    }

    // ============ MULTIPLE SOURCES - ALWAYS COPY INTO ============

    @Test
    fun `multiple sources - always copies into destination directory`() {
        // Given
        val source1 = LocalPath.build("/home/user/file1.txt")
        val source2 = LocalPath.build("/home/user/file2.txt")
        val destination = LocalPath.build("/backup")
        val sources = listOf(source1, source2)
        val destinationExistedAsDirectory = true

        // When - processing first source
        val result1 = calculator.calculateDestinationPath(
            source = source1,
            topLevelSource = source1,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )

        // Then - appends source name
        result1 shouldBe LocalPath.build("/backup/file1.txt")

        // When - processing second source
        val result2 = calculator.calculateDestinationPath(
            source = source2,
            topLevelSource = source2,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )

        // Then - appends source name
        result2 shouldBe LocalPath.build("/backup/file2.txt")
    }

    // ============ NESTED PATHS (CHILDREN OF SOURCE) ============

    @Test
    fun `child of source - preserves relative path structure`() {
        // Given - copying /home/user/folder
        val topLevelSource = LocalPath.build("/home/user/folder")
        val childSource = LocalPath.build("/home/user/folder/subfolder/file.txt")
        val destination = LocalPath.build("/backup")
        val sources = listOf(topLevelSource)
        val destinationExistedAsDirectory = true

        // When
        val result = calculator.calculateDestinationPath(
            source = childSource,
            topLevelSource = topLevelSource,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )

        // Then - preserves relative structure: /backup/folder/subfolder/file.txt
        result shouldBe LocalPath.build("/backup/folder/subfolder/file.txt")
    }

    @Test
    fun `deeply nested child - preserves full relative path`() {
        // Given
        val topLevelSource = LocalPath.build("/src/project")
        val deepChild = LocalPath.build("/src/project/a/b/c/d/file.txt")
        val destination = LocalPath.build("/dest")
        val sources = listOf(topLevelSource)
        val destinationExistedAsDirectory = true

        // When
        val result = calculator.calculateDestinationPath(
            source = deepChild,
            topLevelSource = topLevelSource,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )

        // Then
        result shouldBe LocalPath.build("/dest/project/a/b/c/d/file.txt")
    }

    // ============ ADJUST DESTINATION FOR RENAMES ============

    @Test
    fun `adjustDestinationForRenames - no renames - returns original destination`() {
        // Given
        val dest = LocalPath.build("/backup/folder/file.txt")
        val source = LocalPath.build("/src/folder/file.txt")
        val renamedSourceDirs = emptyMap<LocalPath, LocalPath>()

        // When
        val result = calculator.adjustDestinationForRenames(
            dest = dest,
            source = source,
            renamedSourceDirs = renamedSourceDirs
        )

        // Then
        result shouldBe dest
    }

    @Test
    fun `adjustDestinationForRenames - parent directory was renamed - adjusts path`() {
        // Given - /src/folder was renamed to /dest/folder (1)
        val renamedSourceDirs = mapOf(
            LocalPath.build("/src/folder") to LocalPath.build("/dest/folder (1)")
        )

        // Child file's original destination
        val dest = LocalPath.build("/dest/folder/file.txt")
        val source = LocalPath.build("/src/folder/file.txt")

        // When
        val result = calculator.adjustDestinationForRenames(
            dest = dest,
            source = source,
            renamedSourceDirs = renamedSourceDirs
        )

        // Then - path adjusted to use renamed parent
        result shouldBe LocalPath.build("/dest/folder (1)/file.txt")
    }

    @Test
    fun `adjustDestinationForRenames - source is the renamed directory itself`() {
        // Given - directory was renamed
        val renamedSourceDirs = mapOf(
            LocalPath.build("/src/folder") to LocalPath.build("/dest/folder (1)")
        )

        val dest = LocalPath.build("/dest/folder")
        val source = LocalPath.build("/src/folder")

        // When
        val result = calculator.adjustDestinationForRenames(
            dest = dest,
            source = source,
            renamedSourceDirs = renamedSourceDirs
        )

        // Then - returns the renamed destination directly
        result shouldBe LocalPath.build("/dest/folder (1)")
    }

    @Test
    fun `adjustDestinationForRenames - deeply nested child of renamed directory`() {
        // Given
        val renamedSourceDirs = mapOf(
            LocalPath.build("/src/project") to LocalPath.build("/backup/project (2)")
        )

        val dest = LocalPath.build("/backup/project/a/b/c/file.txt")
        val source = LocalPath.build("/src/project/a/b/c/file.txt")

        // When
        val result = calculator.adjustDestinationForRenames(
            dest = dest,
            source = source,
            renamedSourceDirs = renamedSourceDirs
        )

        // Then
        result shouldBe LocalPath.build("/backup/project (2)/a/b/c/file.txt")
    }

    @Test
    fun `adjustDestinationForRenames - multiple renamed ancestors - uses most specific`() {
        // Given - both parent and grandparent were renamed
        val renamedSourceDirs = mapOf(
            LocalPath.build("/src/grandparent") to LocalPath.build("/dest/grandparent (1)"),
            LocalPath.build("/src/grandparent/parent") to LocalPath.build("/dest/grandparent (1)/parent (2)")
        )

        val dest = LocalPath.build("/dest/grandparent/parent/file.txt")
        val source = LocalPath.build("/src/grandparent/parent/file.txt")

        // When
        val result = calculator.adjustDestinationForRenames(
            dest = dest,
            source = source,
            renamedSourceDirs = renamedSourceDirs
        )

        // Then - uses the most specific match (parent)
        result shouldBe LocalPath.build("/dest/grandparent (1)/parent (2)/file.txt")
    }

    // ============ IS DESCENDANT OF SKIPPED DIR ============

    @Test
    fun `isDescendantOfSkippedDir - no skipped directories - returns false`() {
        // Given
        val path = LocalPath.build("/src/folder/file.txt")
        val skippedSourceDirs = emptySet<LocalPath>()

        // When
        val result = calculator.isDescendantOfSkippedDir(
            path = path,
            skippedSourceDirs = skippedSourceDirs
        )

        // Then
        result shouldBe false
    }

    @Test
    fun `isDescendantOfSkippedDir - path is direct child of skipped directory - returns true`() {
        // Given
        val skippedSourceDirs = setOf(LocalPath.build("/src/folder"))
        val path = LocalPath.build("/src/folder/file.txt")

        // When
        val result = calculator.isDescendantOfSkippedDir(
            path = path,
            skippedSourceDirs = skippedSourceDirs
        )

        // Then
        result shouldBe true
    }

    @Test
    fun `isDescendantOfSkippedDir - path is deeply nested descendant - returns true`() {
        // Given
        val skippedSourceDirs = setOf(LocalPath.build("/src/folder"))
        val path = LocalPath.build("/src/folder/a/b/c/d/file.txt")

        // When
        val result = calculator.isDescendantOfSkippedDir(
            path = path,
            skippedSourceDirs = skippedSourceDirs
        )

        // Then
        result shouldBe true
    }

    @Test
    fun `isDescendantOfSkippedDir - path is the skipped directory itself - returns true`() {
        // Given
        val path = LocalPath.build("/src/folder")
        val skippedSourceDirs = setOf(path)

        // When
        val result = calculator.isDescendantOfSkippedDir(
            path = path,
            skippedSourceDirs = skippedSourceDirs
        )

        // Then - the directory itself is considered a match
        result shouldBe true
    }

    @Test
    fun `isDescendantOfSkippedDir - path is not related to skipped directory - returns false`() {
        // Given
        val skippedSourceDirs = setOf(LocalPath.build("/src/folder1"))
        val path = LocalPath.build("/src/folder2/file.txt")

        // When
        val result = calculator.isDescendantOfSkippedDir(
            path = path,
            skippedSourceDirs = skippedSourceDirs
        )

        // Then
        result shouldBe false
    }

    @Test
    fun `isDescendantOfSkippedDir - path has similar prefix but is not descendant - returns false`() {
        // Given - /src/folder vs /src/folder2 (not a descendant, just similar prefix)
        val skippedSourceDirs = setOf(LocalPath.build("/src/folder"))
        val path = LocalPath.build("/src/folder2/file.txt")

        // When
        val result = calculator.isDescendantOfSkippedDir(
            path = path,
            skippedSourceDirs = skippedSourceDirs
        )

        // Then - should not match (needs exact path separator)
        result shouldBe false
    }

    @Test
    fun `isDescendantOfSkippedDir - multiple skipped directories - matches any`() {
        // Given
        val skippedSourceDirs = setOf(
            LocalPath.build("/src/folder1"),
            LocalPath.build("/src/folder2"),
            LocalPath.build("/src/folder3")
        )

        // When - path is descendant of second skipped directory
        val result = calculator.isDescendantOfSkippedDir(
            path = LocalPath.build("/src/folder2/file.txt"),
            skippedSourceDirs = skippedSourceDirs
        )

        // Then
        result shouldBe true
    }

    // ============ EDGE CASES ============

    @Test
    fun `handles root paths correctly`() {
        // Given
        val source = LocalPath.build("/file.txt")
        val topLevelSource = source
        val destination = LocalPath.build("/backup")
        val sources = listOf(source)
        val destinationExistedAsDirectory = true

        // When
        val result = calculator.calculateDestinationPath(
            source = source,
            topLevelSource = topLevelSource,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )

        // Then
        result shouldBe LocalPath.build("/backup/file.txt")
    }

    @Test
    fun `handles paths with special characters`() {
        // Given
        val source = LocalPath.build("/home/user/file (1).txt")
        val topLevelSource = source
        val destination = LocalPath.build("/backup/folder (2)")
        val sources = listOf(source)
        val destinationExistedAsDirectory = true

        // When
        val result = calculator.calculateDestinationPath(
            source = source,
            topLevelSource = topLevelSource,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )

        // Then
        result shouldBe LocalPath.build("/backup/folder (2)/file (1).txt")
    }

    @Test
    fun `handles paths with spaces and unicode`() {
        // Given
        val source = LocalPath.build("/home/user/My File 文件.txt")
        val topLevelSource = source
        val destination = LocalPath.build("/backup")
        val sources = listOf(source)
        val destinationExistedAsDirectory = true

        // When
        val result = calculator.calculateDestinationPath(
            source = source,
            topLevelSource = topLevelSource,
            destination = destination,
            sources = sources,
            destinationExistedAsDirectory = destinationExistedAsDirectory
        )

        // Then
        result shouldBe LocalPath.build("/backup/My File 文件.txt")
    }
}
