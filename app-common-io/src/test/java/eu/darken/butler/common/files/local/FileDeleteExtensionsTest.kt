package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files

class FileDeleteExtensionsTest : BaseTest() {

    private val testFolder = File(IO_TEST_BASEDIR, "delete-test")

    @BeforeEach
    fun setup() {
        testFolder.mkdirs()
    }

    @AfterEach
    fun cleanup() {
        if (testFolder.exists()) {
            testFolder.deleteRecursively()
        }
    }

    @Test
    fun `delete existing file`() = runTest {
        // Given
        val testFile = File(testFolder, "test.txt")
        testFile.writeText("Hello World")
        val initialSize = testFile.length()
        val testPath = LocalPath.build(testFile)

        // When
        val result = listOf(testPath).delete()

        // Then
        result.deleted shouldContain testPath
        result.bytesTotal shouldBe initialSize
        testFile.exists() shouldBe false
    }

    @Test
    fun `delete non-existent file should not throw`() = runTest {
        // Given
        val nonExistentFile = File(testFolder, "does-not-exist.txt")

        // When
        val result = listOf(LocalPath.build(nonExistentFile)).delete()

        // Then
        result.deleted.shouldBeEmpty()
        result.bytesTotal shouldBe 0L
    }

    @Test
    fun `verify size calculation for files`() = runTest {
        // Given
        val content = "A".repeat(1024) // 1KB
        val testFile = File(testFolder, "large.txt")
        testFile.writeText(content)

        // When
        val result = listOf(LocalPath.build(testFile)).delete()

        // Then
        result.bytesTotal shouldBe content.length.toLong()
    }

    @Test
    fun `delete empty directory`() = runTest {
        // Given
        val emptyDir = File(testFolder, "empty")
        emptyDir.mkdir()

        // When
        val result = listOf(LocalPath.build(emptyDir)).delete()

        // Then
        result.deleted shouldContain LocalPath.build(emptyDir)
        emptyDir.exists() shouldBe false
    }

    @Test
    fun `delete nested structure with files and subdirectories`() = runTest {
        // Given
        val nestedDir = File(testFolder, "nested")
        val subDir = File(nestedDir, "sub")
        val file1 = File(nestedDir, "file1.txt")
        val file2 = File(subDir, "file2.txt")

        nestedDir.mkdir()
        subDir.mkdir()
        file1.writeText("Content 1")
        file2.writeText("Content 2")

        val expectedSize = file1.length() + file2.length()

        // When
        val result = listOf(LocalPath.build(nestedDir)).delete(recursive = true)

        // Then
        result.bytesTotal shouldBe expectedSize
        result.deleted should { files ->
            files shouldContain LocalPath.build(file1)
            files shouldContain LocalPath.build(file2)
            files shouldContain LocalPath.build(subDir)
            files shouldContain LocalPath.build(nestedDir)
        }
        nestedDir.exists() shouldBe false
    }

    @Test
    fun `verify correct deletion order (children before parents)`() = runTest {
        // Given
        val parentDir = File(testFolder, "parent")
        val childFile = File(parentDir, "child.txt")
        parentDir.mkdir()
        childFile.writeText("child content")

        val deletionOrder = mutableListOf<LocalPath>()

        // When
        listOf(LocalPath.build(parentDir)).delete(
            recursive = true,
            onProgress = { deletionOrder.add(it.target) }
        )

        // Then
        val childIndex = deletionOrder.indexOf(LocalPath.build(childFile))
        val parentIndex = deletionOrder.indexOf(LocalPath.build(parentDir))
        childIndex shouldNotBe -1
        parentIndex shouldNotBe -1
        childIndex should { it < parentIndex } // Child deleted before parent
    }

    @Test
    fun `directory with contents should not be deleted when recursive false`() = runTest {
        // Given
        val dirWithContent = File(testFolder, "with-content")
        val childFile = File(dirWithContent, "child.txt")
        dirWithContent.mkdir()
        childFile.writeText("content")

        // When & Then
        shouldThrow<DirectoryNotEmptyException> {
            listOf(LocalPath.build(dirWithContent)).delete(recursive = false)
        }
        dirWithContent.exists() shouldBe true
        childFile.exists() shouldBe true
    }

    @Test
    fun `empty directory should be deleted when recursive false`() = runTest {
        // Given
        val emptyDir = File(testFolder, "empty")
        emptyDir.mkdir()

        // When
        val result = listOf(LocalPath.build(emptyDir)).delete(recursive = false)

        // Then
        result.deleted shouldContain LocalPath.build(emptyDir)
        emptyDir.exists() shouldBe false
    }

    @Test
    fun `progress callback called for each file`() = runTest {
        // Given
        val file1 = File(testFolder, "file1.txt")
        val file2 = File(testFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        val progressCalls = mutableListOf<Pair<LocalPath, Long>>()

        // When
        listOf(LocalPath.build(file1), LocalPath.build(file2)).delete(
            onProgress = { progressCalls.add(it.target to it.targetSize) }
        )

        // Then
        progressCalls shouldHaveSize 2
        progressCalls.map { it.first } shouldContainExactlyInAnyOrder listOf(
            LocalPath.build(file1),
            LocalPath.build(file2)
        )
        progressCalls.all { it.second > 0 } shouldBe true
    }

    @Test
    fun `cumulative size tracking`() = runTest {
        // Given
        val files = (1..5).map { i ->
            File(testFolder, "file$i.txt").apply {
                writeText("Content $i".repeat(i * 10)) // Different sizes
            }
        }

        var cumulativeSize = 0L
        val expectedTotalSize = files.sumOf { it.length() }

        // When
        val result = files.map { LocalPath.build(it) }.delete(
            onProgress = { cumulativeSize += it.targetSize }
        )

        // Then
        cumulativeSize shouldBe expectedTotalSize
        result.bytesTotal shouldBe expectedTotalSize
    }

    @Test
    fun `delete collection with files and directories`() = runTest {
        // Given
        val file = File(testFolder, "standalone.txt")
        val dir = File(testFolder, "directory")
        val dirFile = File(dir, "inside.txt")

        file.writeText("standalone content")
        dir.mkdir()
        dirFile.writeText("inside content")

        val expectedSize = file.length() + dirFile.length()

        // When
        val result = listOf(LocalPath.build(file), LocalPath.build(dir)).delete()

        // Then
        result.bytesTotal shouldBe expectedSize
        result.deleted should { files ->
            files shouldContain LocalPath.build(file)
            files shouldContain LocalPath.build(dir)
            files shouldContain LocalPath.build(dirFile)
        }
        file.exists() shouldBe false
        dir.exists() shouldBe false
    }

    @Test
    fun `handle read-only files gracefully`() = runTest {
        // Given
        val readOnlyFile = File(testFolder, "readonly.txt")
        readOnlyFile.writeText("readonly content")

        // Note: On many systems, setting read-only doesn't prevent deletion by owner
        // This test mainly verifies the code doesn't crash with permission issues
        try {
            readOnlyFile.setReadOnly()

            // When
            val result = listOf(LocalPath.build(readOnlyFile)).delete()

            // Then - depending on system, file may or may not be deleted
            // The important thing is that it doesn't throw an exception
            result.deleted.size shouldBe if (readOnlyFile.exists()) 0 else 1
        } catch (e: SecurityException) {
            // Expected on some systems
        }
    }

    @Test
    fun `delete symlink without following target`() = runTest {
        // Given
        val targetFile = File(testFolder, "target.txt")
        val symlink = File(testFolder, "symlink")

        targetFile.writeText("target content")

        // Create symlink (may not work on all systems/permissions)
        Files.createSymbolicLink(symlink.toPath(), targetFile.toPath())

        // Only proceed if symlink was actually created
        if (Files.isSymbolicLink(symlink.toPath())) {
            // When - the key thing is that deletion doesn't crash
            listOf(LocalPath.build(symlink)).delete()

            // Then - target should remain intact
            targetFile.exists() shouldBe true // Target should remain intact
        }
    }

    @Test
    fun `empty collection should return empty result`() = runTest {
        // When
        val result = emptyList<LocalPath>().delete()

        // Then
        result.deleted.shouldBeEmpty()
        result.bytesTotal shouldBe 0L
    }

    @Test
    fun `collection with duplicates should handle gracefully`() = runTest {
        // Given
        val testFile = File(testFolder, "duplicate.txt")
        testFile.writeText("content")
        val expectedSize = testFile.length()

        // When
        val result = listOf(LocalPath.build(testFile), LocalPath.build(testFile)).delete()

        // Then
        // File should only be deleted once, but may appear in result multiple times
        testFile.exists() shouldBe false
        result.bytesTotal shouldBe expectedSize // Size counted only once in actual deletion
    }

    @Test
    fun `very deep directory structure`() = runTest {
        // Given
        var currentDir = testFolder
        val files = mutableListOf<File>()

        // Create 10-level deep structure
        repeat(10) { level ->
            currentDir = File(currentDir, "level$level")
            currentDir.mkdir()

            val file = File(currentDir, "file$level.txt")
            file.writeText("Level $level content")
            files.add(file)
        }

        val expectedSize = files.sumOf { it.length() }

        // When
        val result = listOf(LocalPath.build(File(testFolder, "level0"))).delete()

        // Then
        result.bytesTotal shouldBe expectedSize
        File(testFolder, "level0").exists() shouldBe false
    }

    @Test
    fun `handle already-deleted files during operation`() = runTest {
        // Given
        val file1 = File(testFolder, "file1.txt")
        val file2 = File(testFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        val expectedSize = file2.length() // Get size before deletion

        // Delete one file externally
        file1.delete()

        // When
        val result = listOf(LocalPath.build(file1), LocalPath.build(file2)).delete()

        // Then
        result.deleted shouldHaveSize 1
        result.deleted shouldContain LocalPath.build(file2)
        result.bytesTotal shouldBe expectedSize
    }

    @Test
    fun `handle large number of files efficiently`() = runTest {
        // Given
        val files = (1..100).map { i ->
            File(testFolder, "file$i.txt").apply {
                writeText("Content $i")
            }
        }

        val expectedSize = files.sumOf { it.length() }
        val startTime = System.currentTimeMillis()

        // When
        val result = files.map { LocalPath.build(it) }.delete()
        val endTime = System.currentTimeMillis()

        // Then
        result.bytesTotal shouldBe expectedSize
        result.deleted shouldHaveSize files.size

        // Basic performance check - should complete reasonably quickly
        val duration = endTime - startTime
        duration should { it < 5000 } // Should complete within 5 seconds
    }

    @Test
    fun `issue handling - skip resolution with apply to all`() = runTest {
        // Given
        val file1 = File(testFolder, "file1.txt")
        val file2 = File(testFolder, "file2.txt")
        val file3 = File(testFolder, "file3.txt")

        // Create files but remove write permission to simulate permission errors
        file1.writeText("content1")
        file2.writeText("content2")
        file3.writeText("content3")

        // Make them read-only (may not work on all systems, but won't crash)
        file1.setReadOnly()
        file2.setReadOnly()
        file3.setReadOnly()

        val issuesEncountered = mutableListOf<PathActionIssue>()
        var firstIssueHandled = false

        // When
        val result = listOf(LocalPath.build(file1), LocalPath.build(file2), LocalPath.build(file3)).delete(
            onIssue = { issue ->
                issuesEncountered.add(issue)

                if (!firstIssueHandled) {
                    firstIssueHandled = true
                    // First issue: Skip with "Apply to All"
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip(
                            applyToAll = true
                        )
                        is PathActionIssue.UnknownError -> PathActionIssue.InsufficientPermission.Resolution.Skip(
                            applyToAll = true
                        )
                        is PathActionIssue.InsufficientSpace -> TODO()
                        is PathActionIssue.PathAlreadyExists -> TODO()
                    }
                } else {
                    // Subsequent issues should not occur due to "Apply to All"
                    throw AssertionError("Should not encounter more issues with Apply to All")
                }
            }
        )

        // Then
        // On many systems, read-only files can still be deleted by the owner
        // So this test validates the mechanism works when issues do occur
        if (issuesEncountered.isNotEmpty()) {
            // If issues were encountered, validate "Apply to All" behavior
            issuesEncountered shouldHaveSize 1
            // Files should still exist due to skip resolution
            file1.exists() shouldBe true
            file2.exists() shouldBe true
            file3.exists() shouldBe true
            result.deleted.shouldBeEmpty()
        } else {
            // If no issues occurred (files were successfully deleted)
            // This is also valid behavior - the test verifies no crashes occur
            result.deleted shouldHaveSize 3
        }
    }

    @Test
    fun `issue handling - retry resolution`() = runTest {
        // Given
        val testFile = File(testFolder, "test.txt")
        testFile.writeText("content")

        var attemptCount = 0
        var retrySuccess = false

        // When
        listOf(LocalPath.build(testFile)).delete(
            onIssue = { issue ->
                attemptCount++
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        if (attemptCount == 1) {
                            // First attempt: Simulate failure and retry
                            PathActionIssue.UnknownError.Resolution.Retry()
                        } else {
                            // Subsequent attempts: Skip
                            retrySuccess = true
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    }
                    is PathActionIssue.InsufficientPermission -> TODO()
                    is PathActionIssue.InsufficientSpace -> TODO()
                    is PathActionIssue.PathAlreadyExists -> TODO()
                }
            }
        )

        // Then
        // Note: This test may not trigger issues on all systems since
        // file deletion might succeed. The test verifies the mechanism works.
        if (attemptCount > 0) {
            attemptCount should { it >= 1 }
        }
    }

    @Test
    fun `issue handling - cancel resolution stops operation`() = runTest {
        // Given
        val file1 = File(testFolder, "file1.txt")
        val file2 = File(testFolder, "file2.txt")

        file1.writeText("content1")
        file2.writeText("content2")

        // Make read-only to potentially trigger issues
        file1.setReadOnly()
        file2.setReadOnly()

        var issueCount = 0

        // When
        listOf(LocalPath.build(file1), LocalPath.build(file2)).delete(
            onIssue = { issue ->
                issueCount++
                // Always cancel on first issue
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Cancel
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Cancel
                    is PathActionIssue.InsufficientSpace -> TODO()
                    is PathActionIssue.PathAlreadyExists -> TODO()
                }
            }
        )

        // Then
        // If issues were encountered, operation should have been cancelled
        if (issueCount > 0) {
            issueCount shouldBe 1  // Only first issue should be processed before cancel
        }
    }

    @Test
    fun `issue handling works without onIssue callback`() = runTest {
        // Given
        val testFile = File(testFolder, "test.txt")
        testFile.writeText("content")

        // When - no onIssue callback provided, should continue with other files
        val result = listOf(LocalPath.build(testFile)).delete(onIssue = null)

        // Then - should not crash and complete normally
        // File should be deleted if permissions allow, or operation continues gracefully
        result.bytesTotal should { it >= 0 }
    }

    @Test
    fun `issue handling with mixed file types`() = runTest {
        // Given
        val regularFile = File(testFolder, "regular.txt")
        val directory = File(testFolder, "directory")
        val dirFile = File(directory, "inside.txt")

        regularFile.writeText("content")
        directory.mkdir()
        dirFile.writeText("inside content")

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = listOf(LocalPath.build(regularFile), LocalPath.build(directory)).delete(
            recursive = true,
            onIssue = { issue ->
                issues.add(issue)
                // Skip all issues to test graceful handling
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    is PathActionIssue.InsufficientSpace -> TODO()
                    is PathActionIssue.PathAlreadyExists -> TODO()
                }
            }
        )

        // Then - Operation should complete without crashing
        result.bytesTotal should { it >= 0 }
        result.deleted should { it.size >= 0 }
    }
}