package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import java.nio.file.Files

class LocalPathMoveTest : BaseTest() {

    private val testFolder = File(IO_TEST_BASEDIR, "move-test")
    private val sourceFolder = File(testFolder, "source")
    private val destFolder = File(testFolder, "dest")

    @BeforeEach
    fun setup() {
        testFolder.mkdirs()
        sourceFolder.mkdirs()
        destFolder.mkdirs()
    }

    @AfterEach
    fun cleanup() {
        if (testFolder.exists()) {
            testFolder.deleteRecursively()
        }
    }

    // ============ BASIC MOVE OPERATIONS ============

    @Test
    fun `move single file to directory`() = runTest {
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Hello World")
        val expectedSize = sourceFile.length()
        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath)

        // Then
        result.movedFiles shouldContain (sourcePath to LocalPath.build(File(destFolder, "test.txt")))
        result.bytesMoved shouldBe expectedSize
        File(destFolder, "test.txt").exists() shouldBe true
        File(destFolder, "test.txt").readText() shouldBe "Hello World"
        sourceFile.exists() shouldBe false // Source should be deleted
    }

    @Test
    fun `move empty directory`() = runTest {
        // Given
        val sourceDir = File(sourceFolder, "empty")
        sourceDir.mkdir()
        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath)

        // Then
        result.movedFiles shouldContain (sourcePath to LocalPath.build(File(destFolder, "empty")))
        File(destFolder, "empty").exists() shouldBe true
        File(destFolder, "empty").isDirectory shouldBe true
        sourceDir.exists() shouldBe false // Source should be deleted
    }

    @Test
    fun `move nested structure with files and subdirectories`() = runTest {
        // Given
        val sourceDir = File(sourceFolder, "nested")
        val subDir = File(sourceDir, "sub")
        val file1 = File(sourceDir, "file1.txt")
        val file2 = File(subDir, "file2.txt")

        sourceDir.mkdir()
        subDir.mkdir()
        file1.writeText("Content 1")
        file2.writeText("Content 2")

        val expectedSize = file1.length() + file2.length()
        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath)

        // Then
        result.movedFiles shouldContain (sourcePath to LocalPath.build(File(destFolder, "nested")))
        result.bytesMoved shouldBe expectedSize

        File(destFolder, "nested").exists() shouldBe true
        File(destFolder, "nested/file1.txt").exists() shouldBe true
        File(destFolder, "nested/file1.txt").readText() shouldBe "Content 1"
        File(destFolder, "nested/sub").exists() shouldBe true
        File(destFolder, "nested/sub/file2.txt").exists() shouldBe true
        File(destFolder, "nested/sub/file2.txt").readText() shouldBe "Content 2"

        sourceDir.exists() shouldBe false // Source should be deleted
    }

    @Test
    fun `move multiple files to directory`() = runTest {
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("Content 1")
        file2.writeText("Content 2")

        val sources = setOf(LocalPath.build(file1), LocalPath.build(file2))
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sources.move(destPath)

        // Then
        result.movedFiles shouldHaveSize 2
        File(destFolder, "file1.txt").exists() shouldBe true
        File(destFolder, "file2.txt").exists() shouldBe true
        file1.exists() shouldBe false
        file2.exists() shouldBe false
    }

    // ============ CONFLICT HANDLING ============

    @Test
    fun `move file - destination exists - no issue handler - should throw`() = runTest {
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Source content")

        val existingFile = File(destFolder, "test.txt")
        existingFile.writeText("Existing content")

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When/Then
        shouldThrow<WriteException> {
            sourcePath.move(destPath, onIssue = null)
        }
    }

    @Test
    fun `move file - destination exists - skip`() = runTest {
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Source content")

        val existingFile = File(destFolder, "test.txt")
        existingFile.writeText("Existing content")

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath) { issue ->
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip()
                else -> error("Unexpected issue: $issue")
            }
        }

        // Then
        result.skippedFiles shouldContain sourcePath
        existingFile.readText() shouldBe "Existing content" // Destination unchanged
        sourceFile.exists() shouldBe true // Source still exists
    }

    @Test
    fun `move file - destination exists - overwrite`() = runTest {
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Source content")

        val existingFile = File(destFolder, "test.txt")
        existingFile.writeText("Existing content")

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath) { issue ->
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                else -> error("Unexpected issue: $issue")
            }
        }

        // Then
        result.movedFiles shouldContain (sourcePath to LocalPath.build(existingFile))
        existingFile.readText() shouldBe "Source content" // Overwritten
        sourceFile.exists() shouldBe false // Source deleted
    }

    @Test
    fun `move file - destination exists - rename source`() = runTest {
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Source content")

        val existingFile = File(destFolder, "test.txt")
        existingFile.writeText("Existing content")

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath) { issue ->
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> {
                    PathActionIssue.PathAlreadyExists.Resolution.RenameSource("test-renamed.txt")
                }
                else -> error("Unexpected issue: $issue")
            }
        }

        // Then
        result.movedFiles shouldContain (sourcePath to LocalPath.build(File(destFolder, "test-renamed.txt")))
        File(destFolder, "test-renamed.txt").exists() shouldBe true
        File(destFolder, "test-renamed.txt").readText() shouldBe "Source content"
        existingFile.readText() shouldBe "Existing content" // Original unchanged
        sourceFile.exists() shouldBe false // Source deleted
    }

    @Test
    fun `move directory - destination exists and is directory - merge`() = runTest {
        // Given
        val sourceDir = File(sourceFolder, "docs")
        sourceDir.mkdir()
        File(sourceDir, "file1.txt").writeText("File 1")

        val existingDir = File(destFolder, "docs")
        existingDir.mkdir()
        File(existingDir, "file2.txt").writeText("File 2")

        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath) { issue ->
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Merge()
                else -> error("Unexpected issue: $issue")
            }
        }

        // Then
        File(destFolder, "docs/file1.txt").exists() shouldBe true
        File(destFolder, "docs/file1.txt").readText() shouldBe "File 1"
        File(destFolder, "docs/file2.txt").exists() shouldBe true
        File(destFolder, "docs/file2.txt").readText() shouldBe "File 2"
        sourceDir.exists() shouldBe false // Source deleted
    }

    @Test
    fun `move file - apply to all - skip`() = runTest {
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("Source 1")
        file2.writeText("Source 2")

        File(destFolder, "file1.txt").writeText("Existing 1")
        File(destFolder, "file2.txt").writeText("Existing 2")

        val sources = setOf(LocalPath.build(file1), LocalPath.build(file2))
        val destPath = LocalPath.build(destFolder)

        var issueCount = 0

        // When
        val result = sources.move(destPath) { issue ->
            issueCount++
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
                else -> error("Unexpected issue: $issue")
            }
        }

        // Then
        issueCount shouldBe 1 // Only asked once due to apply-to-all
        result.skippedFiles shouldHaveSize 2
        file1.exists() shouldBe true
        file2.exists() shouldBe true
    }

    @Test
    fun `move file - apply to all - overwrite`() = runTest {
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("Source 1")
        file2.writeText("Source 2")

        File(destFolder, "file1.txt").writeText("Existing 1")
        File(destFolder, "file2.txt").writeText("Existing 2")

        val sources = setOf(LocalPath.build(file1), LocalPath.build(file2))
        val destPath = LocalPath.build(destFolder)

        var issueCount = 0

        // When
        val result = sources.move(destPath) { issue ->
            issueCount++
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
                else -> error("Unexpected issue: $issue")
            }
        }

        // Then
        issueCount shouldBe 1 // Only asked once due to apply-to-all
        result.movedFiles shouldHaveSize 2
        File(destFolder, "file1.txt").readText() shouldBe "Source 1"
        File(destFolder, "file2.txt").readText() shouldBe "Source 2"
        file1.exists() shouldBe false
        file2.exists() shouldBe false
    }

    // ============ ERROR HANDLING ============

    @Test
    fun `move non-existent file - should throw`() = runTest {
        val nonExistentFile = File(sourceFolder, "does-not-exist.txt")

        shouldThrow<ReadException> {
            LocalPath.build(nonExistentFile).move(LocalPath.build(destFolder))
        }
    }

    @Test
    fun `move to non-existent destination - creates destination`() = runTest {
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Content")

        val nonExistentDest = File(testFolder, "non-existent-dest")

        // When
        val result = LocalPath.build(sourceFile).move(LocalPath.build(nonExistentDest))

        // Then
        nonExistentDest.exists() shouldBe true
        File(nonExistentDest, "test.txt").exists() shouldBe true
        sourceFile.exists() shouldBe false
    }

    // ============ SYMLINK HANDLING ============

    @Test
    fun `move symlink to file`() = runTest {
        // Given
        val targetFile = File(sourceFolder, "target.txt")
        targetFile.writeText("Target content")

        val symlink = File(sourceFolder, "link.txt")
        Files.createSymbolicLink(symlink.toPath(), targetFile.toPath())

        val sourcePath = LocalPath.build(symlink)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath)

        // Then
        result.movedFiles shouldHaveSize 1
        val movedLink = File(destFolder, "link.txt")
        movedLink.exists() shouldBe true
        Files.isSymbolicLink(movedLink.toPath()) shouldBe true
        symlink.exists() shouldBe false
    }

    // ============ PROGRESS TRACKING ============

    @Test
    fun `move reports progress`() = runTest {
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("x".repeat(10000))

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        val progressUpdates = mutableListOf<MoveAction.State.Progress<LocalPath>>()

        // When
        val result = sourcePath.move(
            destination = destPath,
            onProgress = { progress -> progressUpdates.add(progress) }
        )

        // Then
        result.movedFiles shouldHaveSize 1
        progressUpdates shouldNotBe emptyList<MoveAction.State.Progress<LocalPath>>()
    }

    // ============ EDGE CASES ============

    @Test
    fun `move empty list of sources`() = runTest {
        // Given
        val emptySources = emptySet<LocalPath>()
        val destPath = LocalPath.build(destFolder)

        // When
        val result = emptySources.move(destPath)

        // Then
        result.movedFiles.shouldBeEmpty()
        result.skippedFiles.shouldBeEmpty()
        result.bytesMoved shouldBe 0
    }

    @Test
    fun `move deeply nested structure`() = runTest {
        // Given
        val sourceDir = File(sourceFolder, "level1")
        var current = sourceDir
        for (i in 2..10) {
            current = File(current, "level$i")
        }
        current.mkdirs()
        File(current, "deep.txt").writeText("Deep content")

        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath)

        // Then
        result.movedFiles shouldHaveSize 1
        File(destFolder, "level1/level2/level3/level4/level5/level6/level7/level8/level9/level10/deep.txt")
            .readText() shouldBe "Deep content"
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `move large file`() = runTest {
        // Given
        val largeFile = File(sourceFolder, "large.bin")
        largeFile.writeBytes(ByteArray(1024 * 1024) { it.toByte() }) // 1MB

        val sourcePath = LocalPath.build(largeFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(destPath)

        // Then
        result.movedFiles shouldHaveSize 1
        result.bytesMoved shouldBe (1024 * 1024).toLong()
        File(destFolder, "large.bin").exists() shouldBe true
        File(destFolder, "large.bin").length() shouldBe (1024 * 1024).toLong()
        largeFile.exists() shouldBe false
    }
}
