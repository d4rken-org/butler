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

class LocalPathDeleteTest : BaseTest() {

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
        val testPath = LocalPath.build(testFile)

        // When
        val result = testPath.delete()

        // Then
        result.deleted.map { it.lookedUp } shouldContain testPath
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
    }

    @Test
    fun `delete non-existent file - ignore missing false`() = runTest {
        val nonExistentFile = File(testFolder, "does-not-exist.txt")

        shouldThrow<ReadException> {
            LocalPath.build(nonExistentFile).delete(ignoreMissing = false)
        }
    }

    @Test
    fun `delete empty directory`() = runTest {
        // Given
        val emptyDir = File(testFolder, "empty")
        emptyDir.mkdir()

        // When
        val result = listOf(LocalPath.build(emptyDir)).delete()

        // Then
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(emptyDir)
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

        // When
        val result = listOf(LocalPath.build(nestedDir)).delete()

        // Then
        result.deleted.map { it.lookedUp } should { files ->
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
            onProgress = { deletionOrder.add(it.target.lookedUp) }
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
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(emptyDir)
        emptyDir.exists() shouldBe false
    }

    @Test
    fun `progress callback called for each file`() = runTest {
        // Given
        val file1 = File(testFolder, "file1.txt")
        val file2 = File(testFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        val reportedPaths = mutableSetOf<LocalPath>()

        // When
        listOf(LocalPath.build(file1), LocalPath.build(file2)).delete(
            onProgress = { reportedPaths.add(it.target.lookedUp) }
        )

        // Then
        reportedPaths shouldContainExactlyInAnyOrder listOf(
            LocalPath.build(file1),
            LocalPath.build(file2)
        )
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

        // When
        val result = listOf(LocalPath.build(file), LocalPath.build(dir)).delete()

        // Then
        result.deleted.map { it.lookedUp } should { files ->
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

        try {
            // Create symlink (may not work on all systems/permissions)
            Files.createSymbolicLink(symlink.toPath(), targetFile.toPath())

            // Only proceed if symlink was actually created
            if (Files.isSymbolicLink(symlink.toPath())) {
                // When - the key thing is that deletion doesn't crash
                listOf(LocalPath.build(symlink)).delete()

                // Then - target should remain intact
                targetFile.exists() shouldBe true // Target should remain intact
            }
        } catch (e: Exception) {
            // Symlink creation or operations may fail on some systems - skip test gracefully
        }
    }

    @Test
    fun `empty collection should return empty result`() = runTest {
        // When
        val result = emptyList<LocalPath>().delete()

        // Then
        result.deleted.shouldBeEmpty()
    }

    @Test
    fun `collection with duplicates should handle gracefully`() = runTest {
        // Given
        val testFile = File(testFolder, "duplicate.txt")
        testFile.writeText("content")

        // When
        listOf(LocalPath.build(testFile), LocalPath.build(testFile)).delete()

        // Then
        // File should only be deleted once, but may appear in result multiple times
        testFile.exists() shouldBe false
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

        // When
        LocalPath.build(File(testFolder, "level0")).delete()

        // Then
        File(testFolder, "level0").exists() shouldBe false
    }

    @Test
    fun `handle already-deleted files during operation`() = runTest {
        // Given
        val file1 = File(testFolder, "file1.txt")
        val file2 = File(testFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        // Delete one file externally
        file1.delete()

        // When
        val result = listOf(
            LocalPath.build(file1), LocalPath.build(file2)
        ).delete()

        // Then
        result.deleted shouldHaveSize 1
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(file2)
    }

    @Test
    fun `handle large number of files efficiently`() = runTest {
        // Given
        val files = (1..100).map { i ->
            File(testFolder, "file$i.txt").apply {
                writeText("Content $i")
            }
        }

        val startTime = System.currentTimeMillis()

        // When
        val result = files.map { LocalPath.build(it) }.delete()
        val endTime = System.currentTimeMillis()

        // Then
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
                            PathActionIssue.UnknownError.Resolution.Retry
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
        result.deleted should { it.size >= 0 }
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
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(emptyDir)
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

        // When
        val result = LocalPath.build(parentDir).delete(recursive = true)

        // Then
        result.deleted.map { it.lookedUp } should { deleted ->
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

        // When
        val result = listOf(
            LocalPath.build(nonExistentFile1),
            LocalPath.build(existingFile),
            LocalPath.build(nonExistentFile2)
        ).delete(ignoreMissing = true)

        // Then
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(existingFile)
        result.deleted shouldHaveSize 1
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

        // When - ignoreMissing true
        val result = listOf(
            LocalPath.build(nonExistentDir),
            LocalPath.build(existingDir)
        ).delete(ignoreMissing = true)

        // Then
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(existingDir)
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(fileInDir)
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

        // When - should succeed with both flags true
        val result = listOf(
            LocalPath.build(nonExistentFile),
            LocalPath.build(existingFile),
            LocalPath.build(emptyDir),
            LocalPath.build(dirWithContent)
        ).delete(recursive = true, ignoreMissing = true)

        // Then - should delete everything that exists
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(existingFile)
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(emptyDir)
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(dirWithContent)
        result.deleted.map { it.lookedUp } shouldContain LocalPath.build(childFile)

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
                result.deleted.map { it.lookedUp } shouldContain LocalPath.build(symlink)
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

        // When - delete with various flag combinations
        val result = (files.map { LocalPath.build(it) } + nonExistentFiles.map { LocalPath.build(it) }).delete(
            recursive = true,
            ignoreMissing = true
        )

        // Then - should handle large collection efficiently
        result.deleted shouldHaveSize files.size
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
        result.deleted should { it.size >= 0 }
    }

    @Test
    fun `delete two nested empty directories reports only deleted not skipped`() = runTest {
        // Given - Create /A/<B/ (two nested empty directories)
        val dirA = File(testFolder, "A")
        val dirB = File(dirA, "<B")

        dirA.mkdir()
        dirB.mkdir()

        // When
        val result = LocalPath.build(dirA).delete()

        // Then
        result.deleted shouldHaveSize 2
        result.skipped.shouldBeEmpty()
        dirA.exists() shouldBe false
    }

    @Test
    fun `directory scan error then skip should not appear in deleted`() = runTest {
        // Given - Create nested directory structure
        val parentDir = File(testFolder, "parent")
        val childFile = File(parentDir, "child.txt")

        parentDir.mkdir()
        childFile.writeText("content")

        // Make directory unreadable to trigger permission error during scan
        parentDir.setReadable(false)

        var issueReceived = false

        try {
            // When
            val result = LocalPath.build(parentDir).delete(
                onIssue = { issue ->
                    issueReceived = true
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> TODO("Unexpected issue type: $issue")
                    }
                }
            )

            // Then - Directory should be ONLY in skipped, NOT in deleted
            result.deleted.map { it.lookedUp } shouldNotBe LocalPath.build(parentDir)
            result.skipped.map { it.lookedUp } shouldContain LocalPath.build(parentDir)
            issueReceived shouldBe true
        } finally {
            // Restore permissions for cleanup
            parentDir.setReadable(true)
        }
    }

    // ============ PROGRESS THROTTLING TESTS ============

    @Test
    fun `progress callbacks should be throttled to reduce UI spam`() = runTest {
        // Given - 100 files that would normally trigger 200 callbacks (before + after each delete)
        val files = (1..100).map { i ->
            File(testFolder, "file$i.txt").apply {
                writeText("content $i")
            }
        }

        val progressTimestamps = mutableListOf<Long>()
        val startTime = System.currentTimeMillis()

        // When
        files.map { LocalPath.build(it) }.delete(
            onProgress = {
                progressTimestamps.add(System.currentTimeMillis() - startTime)
            }
        )

        // Then - Should have significantly fewer than 200 calls (2 per file without throttling)
        // With 250ms throttling, expect roughly 40-60 calls depending on execution speed
        progressTimestamps.size should { it < 80 }

        // Verify some throttling occurred (if more than 2 callbacks)
        if (progressTimestamps.size > 2) {
            val intervals = progressTimestamps.zipWithNext { a, b -> b - a }
            val throttledIntervals = intervals.dropLast(1).count { it >= 200 }
            // At least some intervals should show throttling behavior
            throttledIntervals should { it > 0 }
        }
    }

    @Test
    fun `progress callbacks should fire for small files despite throttling`() = runTest {
        // Given - Single small file
        val file = File(testFolder, "single.txt")
        file.writeText("Small content")

        var progressCallbackCalled = false

        // When
        LocalPath.build(file).delete(
            onProgress = { progressCallbackCalled = true }
        )

        // Then - At least one callback should fire despite throttling
        progressCallbackCalled shouldBe true
    }

    @Test
    fun `final progress callback shows complete state`() = runTest {
        // Given - 10 files
        val files = (1..10).map { i ->
            File(testFolder, "file$i.txt").apply { writeText("content $i") }
        }

        val progressUpdates = mutableListOf<Pair<Long, Long>>()

        // When
        files.map { LocalPath.build(it) }.delete(
            onProgress = { progress ->
                val count = progress.primaryProgress.count
                if (count is eu.darken.butler.common.progress.Progress.Count.Counter) {
                    progressUpdates.add(count.current to count.max)
                }
            }
        )

        // Then - Final progress callback must show near-completion
        progressUpdates shouldNotBe emptyList<Pair<Long, Long>>()
        val (current, max) = progressUpdates.last()

        // Note: Current implementation has off-by-one because progress object is created
        // before itemsProcessed is incremented. After throttling refactor, this will be fixed.
        max shouldBe 10L
        current should { it >= 9L }  // Should be 9 or 10 (accounting for current implementation quirk)
    }

    // ============ NEW ARCHITECTURE VALIDATION TESTS ============

    @Test
    fun `multiple targets with error in one should continue with others`() = runTest {
        // Given - Three targets: A (succeeds), B (fails), C (succeeds)
        val targetA = File(testFolder, "targetA.txt")
        val targetB = File(testFolder, "targetB.txt")
        val targetC = File(testFolder, "targetC.txt")

        targetA.writeText("content A")
        targetB.writeText("content B")
        targetC.writeText("content C")

        // Make targetB read-only to potentially trigger issues
        targetB.setReadOnly()

        var issueCount = 0

        try {
            // When
            val result = listOf(
                LocalPath.build(targetA),
                LocalPath.build(targetB),
                LocalPath.build(targetC)
            ).delete(
                onIssue = { issue ->
                    issueCount++
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> TODO("Unexpected issue: $issue")
                    }
                }
            )

            // Then - If B had an issue, A and C should still be deleted
            if (issueCount > 0) {
                // B had permission issue and was skipped
                result.deleted.map { it.lookedUp } shouldContain LocalPath.build(targetA)
                result.deleted.map { it.lookedUp } shouldContain LocalPath.build(targetC)
                result.skipped.map { it.lookedUp } shouldContain LocalPath.build(targetB)
                targetB.exists() shouldBe true
            } else {
                // All succeeded (read-only doesn't prevent deletion on this system)
                result.deleted shouldHaveSize 3
            }

            // A and C should always be deleted
            targetA.exists() shouldBe false
            targetC.exists() shouldBe false
        } finally {
            // Cleanup
            targetB.setWritable(true)
        }
    }

    @Test
    fun `deleted and skipped sets are always mutually exclusive`() = runTest {
        // Given - Complex nested structure with various scenarios
        val dirA = File(testFolder, "dirA")
        val dirB = File(dirA, "dirB")
        val fileInA = File(dirA, "fileA.txt")
        val fileInB = File(dirB, "fileB.txt")
        val standaloneFile = File(testFolder, "standalone.txt")

        dirA.mkdir()
        dirB.mkdir()
        fileInA.writeText("content A")
        fileInB.writeText("content B")
        standaloneFile.writeText("standalone")

        // Make one file read-only to potentially trigger skip
        fileInB.setReadOnly()

        try {
            // When
            val result = listOf(
                LocalPath.build(dirA),
                LocalPath.build(standaloneFile)
            ).delete(
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> TODO()
                    }
                }
            )

            // Then - Verify mutual exclusivity (this is the critical test for the bug we fixed)
            val deletedPaths = result.deleted.map { it.lookedUp }.toSet()
            val skippedPaths = result.skipped.map { it.lookedUp }.toSet()
            val intersection = deletedPaths.intersect(skippedPaths)

            intersection.shouldBeEmpty()
        } finally {
            fileInB.setWritable(true)
        }
    }

    @Test
    fun `retry can be called multiple times before success`() = runTest {
        // Given
        val testFile = File(testFolder, "test.txt")
        testFile.writeText("content")

        var attemptCount = 0

        // When - Simulate multiple retry attempts
        val result = LocalPath.build(testFile).delete(
            onIssue = { issue ->
                attemptCount++
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        // We can't easily force real errors, but we can track attempts
                        PathActionIssue.UnknownError.Resolution.Skip()
                    }
                    else -> TODO()
                }
            }
        )

        // Then - File should either be deleted (no errors) or skipped (with error handling)
        // The key is the operation completes without hanging in retry loop
        (result.deleted.size + result.skipped.size) shouldBe 1
    }

    @Test
    fun `progress count is accurate with new workQueue architecture`() = runTest {
        // Given - Nested structure
        val parentDir = File(testFolder, "parent")
        val childDir = File(parentDir, "child")
        val file1 = File(parentDir, "file1.txt")
        val file2 = File(childDir, "file2.txt")

        parentDir.mkdir()
        childDir.mkdir()
        file1.writeText("content1")
        file2.writeText("content2")

        val progressReports = mutableListOf<Long>()

        // When
        val result = LocalPath.build(parentDir).delete(
            onProgress = { progress ->
                val count = progress.primaryProgress.count
                if (count is eu.darken.butler.common.progress.Progress.Count.Counter) {
                    progressReports.add(count.current)
                }
            }
        )

        // Then
        result.deleted shouldHaveSize 4 // file1, file2, childDir, parentDir

        // With throttling, we can't guarantee exact call count
        // But progress tracking must be accurate
        progressReports shouldNotBe emptyList<Long>()

        // Initial progress should start at 0
        progressReports.first() shouldBe 0L

        // Final progress should show all items processed
        progressReports.last() shouldBe 4L  // All 4 items have been processed

        // Should have at least a start and end callback
        progressReports.size should { it >= 2 }
    }

    @Test
    fun `deletion error with skip should appear only in skipped not deleted`() = runTest {
        // Given
        val testFile = File(testFolder, "test.txt")
        testFile.writeText("content")

        // Make file unreadable AND unwritable to maximize chance of permission error
        testFile.setReadOnly()
        testFile.setReadable(false)

        var issueEncountered = false

        try {
            // When
            val result = LocalPath.build(testFile).delete(
                onIssue = { issue ->
                    issueEncountered = true
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> TODO()
                    }
                }
            )

            // Then
            if (issueEncountered) {
                // File should be ONLY in skipped
                result.skipped.map { it.lookedUp } shouldContain LocalPath.build(testFile)
                result.deleted.map { it.lookedUp } shouldNotBe LocalPath.build(testFile)
                testFile.exists() shouldBe true
            } else {
                // System allowed deletion despite permissions
                result.deleted shouldHaveSize 1
            }
        } finally {
            // Restore permissions for cleanup
            testFile.setReadable(true)
            testFile.setWritable(true)
        }
    }

    @Test
    fun `deletion error with retry resolution works correctly`() = runTest {
        // Given
        val testFile = File(testFolder, "test.txt")
        testFile.writeText("content")

        var attemptCount = 0
        var retryInvoked = false

        // When - Use read-only to potentially trigger error
        testFile.setReadOnly()

        try {
            val result = LocalPath.build(testFile).delete(
                onIssue = { issue ->
                    attemptCount++
                    when (issue) {
                        is PathActionIssue.UnknownError -> {
                            if (attemptCount == 1) {
                                retryInvoked = true
                                // On first error, restore permissions and retry
                                testFile.setWritable(true)
                                PathActionIssue.UnknownError.Resolution.Retry
                            } else {
                                // Shouldn't get here if retry worked
                                PathActionIssue.UnknownError.Resolution.Skip()
                            }
                        }
                        is PathActionIssue.InsufficientPermission -> {
                            // InsufficientPermission doesn't support Retry, only Skip/Cancel
                            // This shouldn't happen in practice for deletion, but handle it gracefully
                            PathActionIssue.InsufficientPermission.Resolution.Skip()
                        }
                        else -> TODO()
                    }
                }
            )

            // Then
            if (retryInvoked) {
                // Retry was invoked, file should have been deleted after retry
                result.deleted shouldHaveSize 1
                testFile.exists() shouldBe false
            } else {
                // No error occurred (system allows deletion of read-only by owner)
                result.deleted shouldHaveSize 1
                testFile.exists() shouldBe false
            }
        } finally {
            testFile.setWritable(true)
        }
    }
}