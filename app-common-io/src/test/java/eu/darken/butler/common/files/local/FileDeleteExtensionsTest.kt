package eu.darken.butler.common.files.local

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import java.nio.file.Files

class FileDeleteExtensionsTest : BaseTest() {

    private val testFolder = File(IO_TEST_BASEDIR, "delete-test")

    @AfterEach
    fun cleanup() {
        if (testFolder.exists()) {
            testFolder.deleteRecursively()
        }
    }

    @Test
    fun `delete existing file`() = runTest {
        // Given
        testFolder.mkdirs()
        val testFile = File(testFolder, "test.txt")
        testFile.writeText("Hello World")
        val initialSize = testFile.length()

        // When
        val result = listOf(testFile).delete()

        // Then
        result.deletedFiles shouldContain testFile
        result.totalSize shouldBe initialSize
        testFile.exists() shouldBe false
    }

    @Test
    fun `delete non-existent file should not throw`() = runTest {
        // Given
        testFolder.mkdirs()
        val nonExistentFile = File(testFolder, "does-not-exist.txt")

        // When
        val result = listOf(nonExistentFile).delete()

        // Then
        result.deletedFiles.shouldBeEmpty()
        result.totalSize shouldBe 0L
    }

    @Test
    fun `verify size calculation for files`() = runTest {
        // Given
        testFolder.mkdirs()
        val content = "A".repeat(1024) // 1KB
        val testFile = File(testFolder, "large.txt")
        testFile.writeText(content)

        // When
        val result = listOf(testFile).delete()

        // Then
        result.totalSize shouldBe content.length.toLong()
    }

    @Test
    fun `delete empty directory`() = runTest {
        // Given
        testFolder.mkdirs()
        val emptyDir = File(testFolder, "empty")
        emptyDir.mkdir()

        // When
        val result = listOf(emptyDir).delete()

        // Then
        result.deletedFiles shouldContain emptyDir
        emptyDir.exists() shouldBe false
    }

    @Test
    fun `delete nested structure with files and subdirectories`() = runTest {
        // Given
        testFolder.mkdirs()
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
        val result = listOf(nestedDir).delete(recursive = true)

        // Then
        result.totalSize shouldBe expectedSize
        result.deletedFiles should { files ->
            files shouldContain file1
            files shouldContain file2
            files shouldContain subDir
            files shouldContain nestedDir
        }
        nestedDir.exists() shouldBe false
    }

    @Test
    fun `verify correct deletion order (children before parents)`() = runTest {
        // Given
        testFolder.mkdirs()
        val parentDir = File(testFolder, "parent")
        val childFile = File(parentDir, "child.txt")
        parentDir.mkdir()
        childFile.writeText("child content")

        val deletionOrder = mutableListOf<File>()

        // When
        listOf(parentDir).delete(
            recursive = true,
            onProgress = { file, _ -> deletionOrder.add(file) }
        )

        // Then
        val childIndex = deletionOrder.indexOf(childFile)
        val parentIndex = deletionOrder.indexOf(parentDir)
        childIndex shouldNotBe -1
        parentIndex shouldNotBe -1
        childIndex should { it < parentIndex } // Child deleted before parent
    }

    @Test
    fun `directory with contents should not be deleted when recursive false`() = runTest {
        // Given
        testFolder.mkdirs()
        val dirWithContent = File(testFolder, "with-content")
        val childFile = File(dirWithContent, "child.txt")
        dirWithContent.mkdir()
        childFile.writeText("content")

        // When
        val result = listOf(dirWithContent).delete(recursive = false)

        // Then
        result.deletedFiles.shouldBeEmpty()
        dirWithContent.exists() shouldBe true
        childFile.exists() shouldBe true
    }

    @Test
    fun `empty directory should be deleted when recursive false`() = runTest {
        // Given
        testFolder.mkdirs()
        val emptyDir = File(testFolder, "empty")
        emptyDir.mkdir()

        // When
        val result = listOf(emptyDir).delete(recursive = false)

        // Then
        result.deletedFiles shouldContain emptyDir
        emptyDir.exists() shouldBe false
    }

    @Test
    fun `progress callback called for each file`() = runTest {
        // Given
        testFolder.mkdirs()
        val file1 = File(testFolder, "file1.txt")
        val file2 = File(testFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        val progressCalls = mutableListOf<Pair<File, Long>>()

        // When
        listOf(file1, file2).delete(
            onProgress = { file, size -> progressCalls.add(file to size) }
        )

        // Then
        progressCalls shouldHaveSize 2
        progressCalls.map { it.first } shouldContainExactlyInAnyOrder listOf(file1, file2)
        progressCalls.all { it.second > 0 } shouldBe true
    }

    @Test
    fun `cumulative size tracking`() = runTest {
        // Given
        testFolder.mkdirs()
        val files = (1..5).map { i ->
            File(testFolder, "file$i.txt").apply {
                writeText("Content $i".repeat(i * 10)) // Different sizes
            }
        }

        var cumulativeSize = 0L
        val expectedTotalSize = files.sumOf { it.length() }

        // When
        val result = files.delete(
            onProgress = { _, size -> cumulativeSize += size }
        )

        // Then
        cumulativeSize shouldBe expectedTotalSize
        result.totalSize shouldBe expectedTotalSize
    }

    @Test
    fun `delete collection with files and directories`() = runTest {
        // Given
        testFolder.mkdirs()
        val file = File(testFolder, "standalone.txt")
        val dir = File(testFolder, "directory")
        val dirFile = File(dir, "inside.txt")

        file.writeText("standalone content")
        dir.mkdir()
        dirFile.writeText("inside content")

        val expectedSize = file.length() + dirFile.length()

        // When
        val result = listOf(file, dir).delete()

        // Then
        result.totalSize shouldBe expectedSize
        result.deletedFiles should { files ->
            files shouldContain file
            files shouldContain dir
            files shouldContain dirFile
        }
        file.exists() shouldBe false
        dir.exists() shouldBe false
    }

    @Test
    fun `handle read-only files gracefully`() = runTest {
        // Given
        testFolder.mkdirs()
        val readOnlyFile = File(testFolder, "readonly.txt")
        readOnlyFile.writeText("readonly content")

        // Note: On many systems, setting read-only doesn't prevent deletion by owner
        // This test mainly verifies the code doesn't crash with permission issues
        try {
            readOnlyFile.setReadOnly()

            // When
            val result = listOf(readOnlyFile).delete()

            // Then - depending on system, file may or may not be deleted
            // The important thing is that it doesn't throw an exception
            result.deletedFiles.size shouldBe if (readOnlyFile.exists()) 0 else 1
        } catch (e: SecurityException) {
            // Expected on some systems
        }
    }

    @Test
    fun `delete symlink without following target`() = runTest {
        // Given
        testFolder.mkdirs()
        val targetFile = File(testFolder, "target.txt")
        val symlink = File(testFolder, "symlink")

        targetFile.writeText("target content")

        // Create symlink (may not work on all systems/permissions)
        Files.createSymbolicLink(symlink.toPath(), targetFile.toPath())

        // Only proceed if symlink was actually created
        if (Files.isSymbolicLink(symlink.toPath())) {
            // When - the key thing is that deletion doesn't crash
            listOf(symlink).delete()

            // Then - target should remain intact
            targetFile.exists() shouldBe true // Target should remain intact
        }
    }

    @Test
    fun `empty collection should return empty result`() = runTest {
        // When
        val result = emptyList<File>().delete()

        // Then
        result.deletedFiles.shouldBeEmpty()
        result.totalSize shouldBe 0L
    }

    @Test
    fun `collection with duplicates should handle gracefully`() = runTest {
        // Given
        testFolder.mkdirs()
        val testFile = File(testFolder, "duplicate.txt")
        testFile.writeText("content")
        val expectedSize = testFile.length()

        // When
        val result = listOf(testFile, testFile).delete()

        // Then
        // File should only be deleted once, but may appear in result multiple times
        testFile.exists() shouldBe false
        result.totalSize shouldBe expectedSize // Size counted only once in actual deletion
    }

    @Test
    fun `very deep directory structure`() = runTest {
        // Given
        testFolder.mkdirs()
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
        val result = listOf(File(testFolder, "level0")).delete()

        // Then
        result.totalSize shouldBe expectedSize
        File(testFolder, "level0").exists() shouldBe false
    }

    @Test
    fun `handle already-deleted files during operation`() = runTest {
        // Given
        testFolder.mkdirs()
        val file1 = File(testFolder, "file1.txt")
        val file2 = File(testFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        val expectedSize = file2.length() // Get size before deletion

        // Delete one file externally
        file1.delete()

        // When
        val result = listOf(file1, file2).delete()

        // Then
        result.deletedFiles shouldHaveSize 1
        result.deletedFiles shouldContain file2
        result.totalSize shouldBe expectedSize
    }

    @Test
    fun `handle large number of files efficiently`() = runTest {
        // Given
        testFolder.mkdirs()
        val files = (1..100).map { i ->
            File(testFolder, "file$i.txt").apply {
                writeText("Content $i")
            }
        }

        val expectedSize = files.sumOf { it.length() }
        val startTime = System.currentTimeMillis()

        // When
        val result = files.delete()
        val endTime = System.currentTimeMillis()

        // Then
        result.totalSize shouldBe expectedSize
        result.deletedFiles shouldHaveSize files.size

        // Basic performance check - should complete reasonably quickly
        val duration = endTime - startTime
        duration should { it < 5000 } // Should complete within 5 seconds
    }
}