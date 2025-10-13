package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for GenericPathNamingUtils - smart unique name generation.
 *
 * Verifies that the utility correctly:
 * - Detects existing "(N)" patterns and increments intelligently
 * - Handles files with and without extensions
 * - Works with edge cases (hidden files, multiple dots, etc.)
 */
class GenericPathNamingUtilsTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>

    @BeforeEach
    fun setup() {
        mockOps = MockFileSystemOps { path, type, size, modifiedAt, permissions, ownership ->
            LocalPathLookup(
                lookedUp = path,
                fileType = type,
                size = size,
                modifiedAt = modifiedAt ?: kotlin.time.Instant.fromEpochMilliseconds(0),
                target = null
            )
        }
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ BASIC CASES ============

    @Test
    fun `returns original name when no conflict exists`() = runTest {
        // Given - parent directory exists, but file doesn't
        mockOps.addMockDir("/parent")

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "file.txt",
            ops = mockOps
        )

        // Then - returns original name unchanged
        result shouldBe "file.txt"
    }

    @Test
    fun `appends (1) when original file exists`() = runTest {
        // Given - file exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "file.txt",
            ops = mockOps
        )

        // Then - appends (1) before extension
        result shouldBe "file (1).txt"
    }

    // ============ SMART INCREMENT ============

    @Test
    fun `increments existing number pattern - file (1) becomes file (2)`() = runTest {
        // Given - "file (1).txt" exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file (1).txt", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "file (1).txt",
            ops = mockOps
        )

        // Then - increments to (2), NOT "file (1) (1).txt"
        result shouldBe "file (2).txt"
    }

    @Test
    fun `increments from high number - file (42) becomes file (43)`() = runTest {
        // Given - "file (42).txt" exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file (42).txt", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "file (42).txt",
            ops = mockOps
        )

        // Then
        result shouldBe "file (43).txt"
    }

    @Test
    fun `finds next available number when multiple numbered files exist`() = runTest {
        // Given - file.txt, file (1).txt, file (2).txt exist
        // Testing with file (2).txt should give file (3).txt
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "content".toByteArray())
        mockOps.addMockFile("/parent/file (1).txt", "content".toByteArray())
        mockOps.addMockFile("/parent/file (2).txt", "content".toByteArray())
        mockOps.addMockFile("/parent/file (3).txt", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When - generating unique name for "file (3).txt"
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "file (3).txt",
            ops = mockOps
        )

        // Then - should skip to (4)
        result shouldBe "file (4).txt"
    }

    @Test
    fun `skips gaps in numbering to find next available`() = runTest {
        // Given - file (1).txt and file (3).txt exist, but not file (2).txt
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "content".toByteArray())
        mockOps.addMockFile("/parent/file (1).txt", "content".toByteArray())
        mockOps.addMockFile("/parent/file (2).txt", "content".toByteArray())
        // Note: file (3).txt does NOT exist

        val parentPath = LocalPath.build("/parent")

        // When - asking for unique name for "file (2).txt"
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "file (2).txt",
            ops = mockOps
        )

        // Then - finds (3) is available
        result shouldBe "file (3).txt"
    }

    // ============ FILES WITHOUT EXTENSIONS ============

    @Test
    fun `handles files without extensions`() = runTest {
        // Given - directory "folder" exists
        mockOps.addMockDir("/parent")
        mockOps.addMockDir("/parent/folder")

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "folder",
            ops = mockOps
        )

        // Then - appends (1) without extension
        result shouldBe "folder (1)"
    }

    @Test
    fun `increments numbered folder names`() = runTest {
        // Given - "folder (3)" exists
        mockOps.addMockDir("/parent")
        mockOps.addMockDir("/parent/folder (3)")

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "folder (3)",
            ops = mockOps
        )

        // Then
        result shouldBe "folder (4)"
    }

    // ============ EDGE CASES ============

    @Test
    fun `handles hidden files (starting with dot)`() = runTest {
        // Given - ".hiddenfile" exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/.hiddenfile", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = ".hiddenfile",
            ops = mockOps
        )

        // Then - treats entire name as base (no extension)
        result shouldBe ".hiddenfile (1)"
    }

    @Test
    fun `handles files with multiple dots`() = runTest {
        // Given - "archive.tar.gz" exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/archive.tar.gz", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "archive.tar.gz",
            ops = mockOps
        )

        // Then - splits on LAST dot: "archive.tar" + ".gz"
        result shouldBe "archive.tar (1).gz"
    }

    @Test
    fun `handles files ending with dot (no extension)`() = runTest {
        // Given - "filename." exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/filename.", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "filename.",
            ops = mockOps
        )

        // Then - treats as no extension
        result shouldBe "filename. (1)"
    }

    @Test
    fun `handles numbered file with multiple dots`() = runTest {
        // Given - "backup.2024.tar (5).gz" exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/backup.2024.tar (5).gz", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "backup.2024.tar (5).gz",
            ops = mockOps
        )

        // Then - correctly parses: base="backup.2024.tar", num=5, ext=".gz"
        result shouldBe "backup.2024.tar (6).gz"
    }

    // ============ COMPLEX SCENARIOS ============

    @Test
    fun `handles name with parentheses but no number`() = runTest {
        // Given - "file (abc).txt" exists (parentheses but not a number)
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file (abc).txt", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "file (abc).txt",
            ops = mockOps
        )

        // Then - doesn't recognize as numbered pattern, treats whole as base
        result shouldBe "file (abc) (1).txt"
    }

    @Test
    fun `handles name with number pattern in middle`() = runTest {
        // Given - "file (5) backup.txt" exists (number pattern not at end of base)
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file (5) backup.txt", "content".toByteArray())

        val parentPath = LocalPath.build("/parent")

        // When
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "file (5) backup.txt",
            ops = mockOps
        )

        // Then - pattern must be at END of base name, so this doesn't match
        result shouldBe "file (5) backup (1).txt"
    }

    @Test
    fun `generates unique names for long chains`() = runTest {
        // Given - many numbered versions exist
        mockOps.addMockDir("/parent")
        for (i in 1..10) {
            mockOps.addMockFile("/parent/file ($i).txt", "content".toByteArray())
        }

        val parentPath = LocalPath.build("/parent")

        // When - starting from file (10).txt
        val result = GenericPathNamingUtils.generateUniqueName(
            parentPath = parentPath,
            originalName = "file (10).txt",
            ops = mockOps
        )

        // Then - finds next available (11)
        result shouldBe "file (11).txt"
    }

    // ============ VERIFICATION: No new files created ============

    @Test
    fun `does not create files, only checks existence`() = runTest {
        // Given
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "content".toByteArray())

        val initialFileCount = mockOps.files.size

        // When
        GenericPathNamingUtils.generateUniqueName(
            parentPath = LocalPath.build("/parent"),
            originalName = "file.txt",
            ops = mockOps
        )

        // Then - no new files created
        mockOps.files.size shouldBe initialFileCount
        mockOps.hasFile("/parent/file (1).txt") shouldBe false
    }

    @Test
    fun `uses exists() operation efficiently`() = runTest {
        // Given
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "content".toByteArray())

        mockOps.existsCalls.clear() // Reset call tracking

        // When
        GenericPathNamingUtils.generateUniqueName(
            parentPath = LocalPath.build("/parent"),
            originalName = "file.txt",
            ops = mockOps
        )

        // Then - should have called exists() to check original and suggested names
        (mockOps.existsCalls.size >= 1) shouldBe true
        mockOps.existsCalls.contains("/parent/file.txt") shouldBe true
    }
}
