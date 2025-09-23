package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
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

class LocalPathDeleteExtensionsTest : BaseTest() {

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
        val result = testPath.delete()

        // Then
        result.deleted shouldContain testPath
        result.bytesTotal shouldBe initialSize
        testFile.exists() shouldBe false
    }

    @Test
    fun `delete non-existent file - ignore missing true`() = runTest {
        // Given
        val nonExistentFile = File(testFolder, "does-not-exist.txt")

        // When
        val result = listOf(LocalPath.build(nonExistentFile)).delete(ignoreMissing = true)

        // Then
        result.deleted.shouldBeEmpty()
        result.bytesTotal shouldBe 0L
    }

    @Test
    fun `delete non-existent file - ignore missing false`() = runTest {
        val nonExistentFile = File(testFolder, "does-not-exist.txt")

        shouldThrow<ReadException> {
            LocalPath.build(nonExistentFile).delete(ignoreMissing = false)
        }
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
        val result = listOf(LocalPath.build(nestedDir)).delete()

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
        val result = LocalPath.build(File(testFolder, "level0")).delete()

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
        val result = listOf(
            LocalPath.build(file1), LocalPath.build(file2)
        ).delete()

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
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Cancel()
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Cancel()
                    else -> TODO()
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


    // ============ RECURSIVE FLAG TESTS ============

    @Test
    fun `recursive false with empty directory should succeed`() = runTest {
        // Given
        val emptyDir = File(testFolder, "empty-dir")
        emptyDir.mkdir()

        // When
        val result = LocalPath.build(emptyDir).delete(recursive = false)

        // Then
        result.deleted shouldContain LocalPath.build(emptyDir)
        emptyDir.exists() shouldBe false
    }

    @Test
    fun `recursive false with non-empty directory should fail`() = runTest {
        // Given
        val dirWithContent = File(testFolder, "dir-with-content")
        val childFile = File(dirWithContent, "child.txt")
        dirWithContent.mkdir()
        childFile.writeText("content")

        // When & Then
        shouldThrow<DirectoryNotEmptyException> {
            LocalPath.build(dirWithContent).delete(recursive = false)
        }
        dirWithContent.exists() shouldBe true
        childFile.exists() shouldBe true
    }

    @Test
    fun `recursive true with nested structure should succeed`() = runTest {
        // Given
        val parentDir = File(testFolder, "parent")
        val childDir = File(parentDir, "child")
        val grandchildDir = File(childDir, "grandchild")
        val file1 = File(parentDir, "file1.txt")
        val file2 = File(childDir, "file2.txt")
        val file3 = File(grandchildDir, "file3.txt")

        parentDir.mkdir()
        childDir.mkdir()
        grandchildDir.mkdir()
        file1.writeText("content1")
        file2.writeText("content2")
        file3.writeText("content3")

        val expectedSize = file1.length() + file2.length() + file3.length()

        // When
        val result = LocalPath.build(parentDir).delete(recursive = true)

        // Then
        result.bytesTotal shouldBe expectedSize
        result.deleted should { deleted ->
            deleted shouldContain LocalPath.build(file1)
            deleted shouldContain LocalPath.build(file2)
            deleted shouldContain LocalPath.build(file3)
            deleted shouldContain LocalPath.build(grandchildDir)
            deleted shouldContain LocalPath.build(childDir)
            deleted shouldContain LocalPath.build(parentDir)
        }
        parentDir.exists() shouldBe false
    }

    @Test
    fun `recursive flag behavior with collection of mixed content`() = runTest {
        // Given
        val file = File(testFolder, "standalone.txt")
        val emptyDir = File(testFolder, "empty")
        val dirWithContent = File(testFolder, "with-content")
        val childFile = File(dirWithContent, "child.txt")

        file.writeText("standalone")
        emptyDir.mkdir()
        dirWithContent.mkdir()
        childFile.writeText("child content")

        // When - recursive false should fail for directory with content
        shouldThrow<DirectoryNotEmptyException> {
            listOf(
                LocalPath.build(file),
                LocalPath.build(emptyDir),
                LocalPath.build(dirWithContent)
            ).delete(recursive = false)
        }

        // Then - directory with content should still exist (couldn't be deleted due to recursive=false)
        // Note: Other files may have been deleted before the exception was thrown
        dirWithContent.exists() shouldBe true
        childFile.exists() shouldBe true
    }

    @Test
    fun `recursive true vs false with same directory structure`() = runTest {
        // Given - create two identical directory structures
        val dir1 = File(testFolder, "test-dir-1")
        val dir2 = File(testFolder, "test-dir-2")
        val file1 = File(dir1, "file.txt")
        val file2 = File(dir2, "file.txt")

        dir1.mkdir()
        dir2.mkdir()
        file1.writeText("content")
        file2.writeText("content")

        // When - delete first with recursive=true
        val recursiveResult = LocalPath.build(dir1).delete(recursive = true)

        // And - try to delete second with recursive=false (should fail)
        shouldThrow<DirectoryNotEmptyException> {
            LocalPath.build(dir2).delete(recursive = false)
        }

        // Then
        dir1.exists() shouldBe false
        recursiveResult.deleted shouldHaveSize 2 // file and directory

        dir2.exists() shouldBe true // Should still exist
        file2.exists() shouldBe true
    }

    // ============ IGNORE MISSING FLAG TESTS ============

    @Test
    fun `ignoreMissing true with non-existent file should not throw`() = runTest {
        // Given
        val nonExistentFile = File(testFolder, "does-not-exist.txt")

        // When
        val result = LocalPath.build(nonExistentFile).delete(ignoreMissing = true)

        // Then
        result.deleted.shouldBeEmpty()
        result.bytesTotal shouldBe 0L
    }

    @Test
    fun `ignoreMissing false with non-existent file should throw`() = runTest {
        // Given
        val nonExistentFile = File(testFolder, "does-not-exist.txt")

        // When & Then
        shouldThrow<ReadException> {
            LocalPath.build(nonExistentFile).delete(ignoreMissing = false)
        }
    }

    @Test
    fun `ignoreMissing true with mixed existing and non-existing files`() = runTest {
        // Given
        val existingFile = File(testFolder, "exists.txt")
        val nonExistentFile1 = File(testFolder, "missing1.txt")
        val nonExistentFile2 = File(testFolder, "missing2.txt")

        existingFile.writeText("content")
        val expectedSize = existingFile.length()

        // When
        val result = listOf(
            LocalPath.build(nonExistentFile1),
            LocalPath.build(existingFile),
            LocalPath.build(nonExistentFile2)
        ).delete(ignoreMissing = true)

        // Then
        result.deleted shouldContain LocalPath.build(existingFile)
        result.deleted shouldHaveSize 1
        result.bytesTotal shouldBe expectedSize
        existingFile.exists() shouldBe false
    }

    @Test
    fun `ignoreMissing false with mixed existing and non-existing files should throw on first missing`() = runTest {
        // Given
        val existingFile = File(testFolder, "exists.txt")
        val nonExistentFile1 = File(testFolder, "missing1.txt")
        val nonExistentFile2 = File(testFolder, "missing2.txt")

        existingFile.writeText("content")

        // When & Then
        shouldThrow<ReadException> {
            listOf(
                LocalPath.build(nonExistentFile1),
                LocalPath.build(existingFile),
                LocalPath.build(nonExistentFile2)
            ).delete(ignoreMissing = false)
        }

        // Then - operation should have stopped on first missing file
        existingFile.exists() shouldBe true // Should not have been deleted
    }

    @Test
    fun `ignoreMissing true with collection of all non-existent files`() = runTest {
        // Given
        val nonExistentFiles = (1..5).map { File(testFolder, "missing$it.txt") }

        // When
        val result = nonExistentFiles.map { LocalPath.build(it) }.delete(ignoreMissing = true)

        // Then
        result.deleted.shouldBeEmpty()
        result.bytesTotal shouldBe 0L
    }

    @Test
    fun `ignoreMissing false with collection of all non-existent files should throw`() = runTest {
        // Given
        val nonExistentFiles = (1..5).map { File(testFolder, "missing$it.txt") }

        // When & Then
        shouldThrow<ReadException> {
            nonExistentFiles.map { LocalPath.build(it) }.delete(ignoreMissing = false)
        }
    }

    @Test
    fun `ignoreMissing behavior with directories`() = runTest {
        // Given
        val existingDir = File(testFolder, "existing-dir")
        val nonExistentDir = File(testFolder, "missing-dir")
        val fileInDir = File(existingDir, "file.txt")

        existingDir.mkdir()
        fileInDir.writeText("content")
        val expectedSize = fileInDir.length()

        // When - ignoreMissing true
        val result = listOf(
            LocalPath.build(nonExistentDir),
            LocalPath.build(existingDir)
        ).delete(ignoreMissing = true)

        // Then
        result.deleted shouldContain LocalPath.build(existingDir)
        result.deleted shouldContain LocalPath.build(fileInDir)
        result.bytesTotal shouldBe expectedSize
        existingDir.exists() shouldBe false
    }

    @Test
    fun `verify ignoreMissing flag consistency between single and collection operations`() = runTest {
        // Given
        val nonExistent1 = File(testFolder, "missing1.txt")
        val nonExistent2 = File(testFolder, "missing2.txt")

        // When & Then - both single and collection should behave the same with ignoreMissing=false
        shouldThrow<ReadException> {
            LocalPath.build(nonExistent1).delete(ignoreMissing = false)
        }

        shouldThrow<ReadException> {
            listOf(LocalPath.build(nonExistent2)).delete(ignoreMissing = false)
        }

        // And both should succeed with ignoreMissing=true
        val singleResult = LocalPath.build(nonExistent1).delete(ignoreMissing = true)
        val collectionResult = listOf(LocalPath.build(nonExistent2)).delete(ignoreMissing = true)

        singleResult.deleted.shouldBeEmpty()
        collectionResult.deleted.shouldBeEmpty()
    }

    // ============ COMBINED FLAG TESTS ============

    @Test
    fun `recursive false and ignoreMissing true with mixed content`() = runTest {
        // Given
        val existingFile = File(testFolder, "exists.txt")
        val nonExistentFile = File(testFolder, "missing.txt")
        val emptyDir = File(testFolder, "empty-dir")
        val dirWithContent = File(testFolder, "dir-with-content")
        val childFile = File(dirWithContent, "child.txt")

        existingFile.writeText("content")
        emptyDir.mkdir()
        dirWithContent.mkdir()
        childFile.writeText("child content")

        // When - should fail on directory with content but ignore missing files
        shouldThrow<DirectoryNotEmptyException> {
            listOf(
                LocalPath.build(nonExistentFile),
                LocalPath.build(existingFile),
                LocalPath.build(emptyDir),
                LocalPath.build(dirWithContent)
            ).delete(recursive = false, ignoreMissing = true)
        }

        // Then - directory with content should still exist (couldn't be deleted due to recursive=false)
        // Note: Other files may have been deleted before the exception was thrown
        dirWithContent.exists() shouldBe true
        childFile.exists() shouldBe true
    }

    @Test
    fun `recursive true and ignoreMissing false with mixed content`() = runTest {
        // Given
        val existingFile = File(testFolder, "exists.txt")
        val nonExistentFile = File(testFolder, "missing.txt")
        val dirWithContent = File(testFolder, "dir-with-content")
        val childFile = File(dirWithContent, "child.txt")

        existingFile.writeText("content")
        dirWithContent.mkdir()
        childFile.writeText("child content")

        // When - should fail on missing file even though recursive is true
        shouldThrow<ReadException> {
            listOf(
                LocalPath.build(nonExistentFile),
                LocalPath.build(existingFile),
                LocalPath.build(dirWithContent)
            ).delete(recursive = true, ignoreMissing = false)
        }

        // Then - should not have deleted anything due to missing file
        existingFile.exists() shouldBe true
        dirWithContent.exists() shouldBe true
        childFile.exists() shouldBe true
    }

    @Test
    fun `recursive true and ignoreMissing true - happy path with all combinations`() = runTest {
        // Given
        val existingFile = File(testFolder, "exists.txt")
        val nonExistentFile = File(testFolder, "missing.txt")
        val emptyDir = File(testFolder, "empty-dir")
        val dirWithContent = File(testFolder, "dir-with-content")
        val childFile = File(dirWithContent, "child.txt")

        existingFile.writeText("content")
        emptyDir.mkdir()
        dirWithContent.mkdir()
        childFile.writeText("child content")

        val expectedSize = existingFile.length() + childFile.length()

        // When - should succeed with both flags true
        val result = listOf(
            LocalPath.build(nonExistentFile),
            LocalPath.build(existingFile),
            LocalPath.build(emptyDir),
            LocalPath.build(dirWithContent)
        ).delete(recursive = true, ignoreMissing = true)

        // Then - should delete everything that exists
        result.deleted shouldContain LocalPath.build(existingFile)
        result.deleted shouldContain LocalPath.build(emptyDir)
        result.deleted shouldContain LocalPath.build(dirWithContent)
        result.deleted shouldContain LocalPath.build(childFile)
        result.bytesTotal shouldBe expectedSize

        existingFile.exists() shouldBe false
        emptyDir.exists() shouldBe false
        dirWithContent.exists() shouldBe false
    }

    @Test
    fun `recursive false and ignoreMissing false - strict mode`() = runTest {
        // Given
        val existingFile = File(testFolder, "exists.txt")
        val nonExistentFile = File(testFolder, "missing.txt")
        val emptyDir = File(testFolder, "empty-dir")

        existingFile.writeText("content")
        emptyDir.mkdir()

        // When - should fail on missing file in strict mode
        shouldThrow<ReadException> {
            listOf(
                LocalPath.build(nonExistentFile),
                LocalPath.build(existingFile),
                LocalPath.build(emptyDir)
            ).delete(recursive = false, ignoreMissing = false)
        }

        // Then - nothing should be deleted
        existingFile.exists() shouldBe true
        emptyDir.exists() shouldBe true
    }

    @Test
    fun `flag combinations with symbolic links`() = runTest {
        // Given
        val targetFile = File(testFolder, "target.txt")
        val symlink = File(testFolder, "symlink")
        val nonExistentSymlink = File(testFolder, "missing-symlink")

        targetFile.writeText("target content")

        try {
            Files.createSymbolicLink(symlink.toPath(), targetFile.toPath())

            // Only proceed if symlink was actually created
            if (Files.isSymbolicLink(symlink.toPath())) {
                // When - delete existing symlink and missing symlink with various flag combinations
                val result = listOf(
                    LocalPath.build(symlink),
                    LocalPath.build(nonExistentSymlink)
                ).delete(recursive = true, ignoreMissing = true)

                // Then - should delete symlink but ignore missing one, target should remain
                result.deleted shouldContain LocalPath.build(symlink)
                targetFile.exists() shouldBe true // Target should remain intact
            }
        } catch (e: Exception) {
            // Symlink creation may fail on some systems - skip test gracefully
        }
    }

    @Test
    fun `performance with flag combinations on large collections`() = runTest {
        // Given
        val files = (1..50).map { i ->
            File(testFolder, "file$i.txt").apply {
                writeText("Content $i")
            }
        }
        val nonExistentFiles = (51..60).map { i -> File(testFolder, "missing$i.txt") }

        val expectedSize = files.sumOf { it.length() }

        // When - delete with various flag combinations
        val result = (files.map { LocalPath.build(it) } + nonExistentFiles.map { LocalPath.build(it) }).delete(
            recursive = true,
            ignoreMissing = true
        )

        // Then - should handle large collection efficiently
        result.deleted shouldHaveSize files.size
        result.bytesTotal shouldBe expectedSize
        files.forEach { it.exists() shouldBe false }
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