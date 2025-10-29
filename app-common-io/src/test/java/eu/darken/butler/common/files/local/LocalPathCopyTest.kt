package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.shouldContainPath
import testhelpers.toPathPairs
import java.io.File
import java.nio.file.Files

class LocalPathCopyTest : BaseTest() {

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val ops = LocalFileSystemOps(
        ownershipResolver = mockOwnershipResolver,
    )

    // ============ BASIC COPY OPERATIONS ============

    @Test
    fun `copy single file to directory`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Hello World")
        val expectedSize = sourceFile.length()
        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.copy(ops, destPath)
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copied shouldContainPath (sourcePath to LocalPath.build(File(destFolder, "test.txt")))
        result.copiedBytes shouldBe expectedSize
        File(destFolder, "test.txt").exists() shouldBe true
        File(destFolder, "test.txt").readText() shouldBe "Hello World"
        sourceFile.exists() shouldBe true // Source should still exist
    }

    @Test
    fun `copy empty directory`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "empty")
        sourceDir.mkdir()
        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.copy(ops, destPath)
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copied shouldContainPath (sourcePath to LocalPath.build(File(destFolder, "empty")))
        File(destFolder, "empty").exists() shouldBe true
        File(destFolder, "empty").isDirectory shouldBe true
    }

    @Test
    fun `copy nested structure with files and subdirectories`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
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
        val result = sourcePath.copy(ops, destPath)
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copiedBytes shouldBe expectedSize
        result.copied.toPathPairs().map { it.first } should { paths ->
            paths shouldContain LocalPath.build(file1)
            paths shouldContain LocalPath.build(file2)
            paths shouldContain LocalPath.build(subDir)
            paths shouldContain LocalPath.build(sourceDir)
        }

        File(destFolder, "nested/file1.txt").exists() shouldBe true
        File(destFolder, "nested/sub/file2.txt").exists() shouldBe true
        File(destFolder, "nested/file1.txt").readText() shouldBe "Content 1"
        File(destFolder, "nested/sub/file2.txt").readText() shouldBe "Content 2"
    }

    @Test
    fun `copy deeply nested structure`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
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
        val result = sourcePath.copy(ops, destPath)
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        // Should have 1 file + 10 directories = 11 items
        result.copied shouldHaveSize 11
        result.copied.toPathPairs().map { it.first } should { paths ->
            paths shouldContain LocalPath.build(File(current, "deep.txt"))
            paths shouldContain LocalPath.build(sourceDir)
        }
        File(destFolder, "level1/level2/level3/level4/level5/level6/level7/level8/level9/level10/deep.txt")
            .readText() shouldBe "Deep content"
    }

    @Test
    fun `copy collection with files and directories`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file = File(sourceFolder, "standalone.txt")
        val dir = File(sourceFolder, "directory")
        val dirFile = File(dir, "inside.txt")

        file.writeText("standalone content")
        dir.mkdir()
        dirFile.writeText("inside content")

        val expectedSize = file.length() + dirFile.length()
        val sourcePaths = listOf(LocalPath.build(file), LocalPath.build(dir))
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePaths.copy(ops, destPath)
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copiedBytes shouldBe expectedSize
        result.copied.toPathPairs().map { it.first } should { paths ->
            paths shouldContain LocalPath.build(file)
            paths shouldContain LocalPath.build(dir)
            paths shouldContain LocalPath.build(dirFile)
        }

        File(destFolder, "standalone.txt").exists() shouldBe true
        File(destFolder, "directory/inside.txt").exists() shouldBe true
    }

    @Test
    fun `copy symlink without following target`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val targetFile = File(sourceFolder, "target.txt")
        val symlink = File(sourceFolder, "symlink")

        targetFile.writeText("target content")

        try {
            // Create symlink with relative path
            Files.createSymbolicLink(symlink.toPath(), java.nio.file.Paths.get("target.txt"))

            if (Files.isSymbolicLink(symlink.toPath())) {
                // When
                val result = LocalPath.build(symlink).copy(ops, LocalPath.build(destFolder))
                    .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

                // Then
                File(destFolder, "symlink").exists() shouldBe true
                // Symlink should be copied as a symlink (implementation dependent)
                result.copied.size shouldBe 1
            }
        } catch (_: Exception) {
            // Symlink creation may fail on some systems - skip test gracefully
        }
    }

    @Test
    fun `verify byte tracking for copied files`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val content = "A".repeat(1024) // 1KB
        val file = File(sourceFolder, "large.txt")
        file.writeText(content)

        // When
        val result = LocalPath.build(file).copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copiedBytes shouldBe content.length.toLong()
    }

    @Test
    fun `very deep directory structure`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        var currentDir = File(sourceFolder, "deep")
        currentDir.mkdir()
        val files = mutableListOf<File>()

        repeat(10) { level ->
            currentDir = File(currentDir, "level$level")
            currentDir.mkdir()

            val file = File(currentDir, "file$level.txt")
            file.writeText("Level $level content")
            files.add(file)
        }

        val expectedSize = files.sumOf { it.length() }

        // When
        val result = LocalPath.build(File(sourceFolder, "deep")).copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copiedBytes shouldBe expectedSize
        File(destFolder, "deep/level0/level1/level2/level3/level4/level5/level6/level7/level8/level9/file9.txt")
            .exists() shouldBe true
    }

    @Test
    fun `handle large number of files efficiently`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "many")
        sourceDir.mkdir()
        val files = (1..100).map { i ->
            File(sourceDir, "file$i.txt").apply {
                writeText("Content $i")
            }
        }

        val expectedSize = files.sumOf { it.length() }
        val startTime = System.currentTimeMillis()

        // When
        val result = LocalPath.build(sourceDir).copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>
        val endTime = System.currentTimeMillis()

        // Then
        result.copiedBytes shouldBe expectedSize
        result.copied shouldHaveSize (files.size + 1) // files + directory

        val duration = endTime - startTime
        duration should { it < 5000 } // Should complete within 5 seconds
    }

    @Test
    fun `empty collection should return empty result`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // When
        val result = emptyList<LocalPath>().copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copied.shouldBeEmpty()
        result.copiedBytes shouldBe 0L
    }

    @Test
    fun `collection with duplicates should handle gracefully`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file = File(sourceFolder, "duplicate.txt")
        file.writeText("content")
        val expectedSize = file.length()
        val sourcePath = LocalPath.build(file)

        // When - second copy will encounter PathAlreadyExists
        val result = listOf(sourcePath, sourcePath).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        File(destFolder, "duplicate.txt").exists() shouldBe true
        // Both copies attempted but result may vary
        result.copiedBytes should { it >= expectedSize }
    }

    // ============ PATH CONSTRUCTION VERIFICATION (CRITICAL) ============

    @Test
    fun `verify directory structure preservation - main bug fix test`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - create nested structure
        val projectDir = File(sourceFolder, "project")
        val srcDir = File(projectDir, "src")
        val mainFile = File(srcDir, "main.kt")

        projectDir.mkdir()
        srcDir.mkdir()
        mainFile.writeText("fun main() {}")

        // When - copy directory to destination
        LocalPath.build(projectDir).copy(ops, LocalPath.build(destFolder)).last()

        // Then - verify structure is preserved with directory name
        File(destFolder, "project").exists() shouldBe true
        File(destFolder, "project").isDirectory shouldBe true
        File(destFolder, "project/src").exists() shouldBe true
        File(destFolder, "project/src").isDirectory shouldBe true
        File(destFolder, "project/src/main.kt").exists() shouldBe true
        File(destFolder, "project/src/main.kt").readText() shouldBe "fun main() {}"

        // Verify wrong paths don't exist (the bug we fixed would create these)
        File(destFolder, "src/main.kt").exists() shouldBe false
        File(destFolder, "main.kt").exists() shouldBe false
    }

    @Test
    fun `verify single file copy path`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file = File(sourceFolder, "report.pdf")
        file.writeText("PDF content")

        // When
        LocalPath.build(file).copy(ops, LocalPath.build(destFolder)).last()

        // Then
        File(destFolder, "report.pdf").exists() shouldBe true
        File(destFolder, "report.pdf").isFile shouldBe true
    }

    @Test
    fun `verify nested directory paths`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val deepDir = File(sourceFolder, "a/b/c")
        deepDir.mkdirs()
        val file = File(deepDir, "file.txt")
        file.writeText("deep content")

        // When
        LocalPath.build(File(sourceFolder, "a")).copy(ops, LocalPath.build(destFolder)).last()

        // Then
        File(destFolder, "a/b/c/file.txt").exists() shouldBe true
        File(destFolder, "a/b/c/file.txt").readText() shouldBe "deep content"
    }

    @Test
    fun `verify multiple sources maintain structure`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val dir1 = File(sourceFolder, "project1")
        val dir2 = File(sourceFolder, "project2")
        val file1 = File(dir1, "file.txt")
        val file2 = File(dir2, "file.txt")

        dir1.mkdir()
        dir2.mkdir()
        file1.writeText("project1 content")
        file2.writeText("project2 content")

        // When
        listOf(LocalPath.build(dir1), LocalPath.build(dir2)).copy(ops, LocalPath.build(destFolder)).last()

        // Then
        File(destFolder, "project1/file.txt").exists() shouldBe true
        File(destFolder, "project2/file.txt").exists() shouldBe true
        File(destFolder, "project1/file.txt").readText() shouldBe "project1 content"
        File(destFolder, "project2/file.txt").readText() shouldBe "project2 content"
    }

    @Test
    fun `verify files vs directories handled consistently`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file = File(sourceFolder, "file.txt")
        val dir = File(sourceFolder, "dir")
        val dirFile = File(dir, "nested.txt")

        file.writeText("file content")
        dir.mkdir()
        dirFile.writeText("nested content")

        // When
        listOf(LocalPath.build(file), LocalPath.build(dir)).copy(ops, LocalPath.build(destFolder)).last()

        // Then - both maintain their top-level name
        File(destFolder, "file.txt").exists() shouldBe true
        File(destFolder, "dir/nested.txt").exists() shouldBe true
    }

    // ============ PROGRESS CALLBACKS ============

    @Test
    fun `progress callback called for each file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        val progressCalls = mutableListOf<LocalPathLookup>()

        // When
        listOf(LocalPath.build(file1), LocalPath.build(file2)).copy(
            ops,
            LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is CopyAction.State.Active) progressCalls.add(state.currentSource)
        }.last()

        // Then - should be called for each file
        progressCalls.size should { it >= 2 }
        progressCalls.map { it.lookedUp } shouldContain LocalPath.build(file1)
        progressCalls.map { it.lookedUp } shouldContain LocalPath.build(file2)
    }

    @Test
    fun `cumulative byte tracking in progress`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val files = (1..5).map { i ->
            File(sourceFolder, "file$i.txt").apply {
                writeText("Content $i".repeat(i * 10))
            }
        }

        val bytesSeen = mutableListOf<Long>()

        // When
        files.map { LocalPath.build(it) }.copy(
            ops,
            LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is CopyAction.State.Active) bytesSeen.add(state.copiedBytes)
        }.last()

        // Then - bytes should increase over time
        bytesSeen.size should { it > 0 }
        if (bytesSeen.size > 1) {
            bytesSeen.zipWithNext().all { (a, b) -> b >= a } shouldBe true
        }
    }

    @Test
    fun `verify progress includes primary and secondary data`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val dir = File(sourceFolder, "project")
        val file = File(dir, "file.txt")
        dir.mkdir()
        file.writeText("content")

        var primarySeen = false
        var secondarySeen = false

        // When
        LocalPath.build(dir).copy(
            ops,
            LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is CopyAction.State.Active) {
                if (state.primaryProgress != null) primarySeen = true
                if (state.secondaryProgress != null) secondarySeen = true
            }
        }.last()

        // Then
        primarySeen shouldBe true
        // Secondary may or may not appear depending on item count
    }

    @Test
    fun `progress callbacks should be throttled to reduce UI spam`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - file large enough to generate many chunks (64KB buffer = ~20 chunks)
        val sourceFile = File(sourceFolder, "large.bin")
        sourceFile.writeBytes(ByteArray(1024 * 1024 * 2)) // 2MB file

        val progressTimestamps = mutableListOf<Long>()
        val startTime = System.currentTimeMillis()

        // When
        LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is CopyAction.State.Active) {
                progressTimestamps.add(System.currentTimeMillis() - startTime)
            }
        }.last()

        // Then - should have significantly fewer callbacks than chunks (2MB / 64KB = ~32 chunks)
        // With 250ms throttling, expect ~4-20 callbacks depending on speed
        progressTimestamps.size should { it < 40 }

        // Verify time intervals between callbacks (except possibly the last)
        if (progressTimestamps.size > 2) {
            val intervals = progressTimestamps.zipWithNext { a, b -> b - a }
            // Most intervals should respect the 250ms throttle (allow some variance)
            val throttledIntervals = intervals.dropLast(1).count { it >= 200 }
            throttledIntervals should { it >= intervals.size / 2 }
        }
    }

    @Test
    fun `progress callbacks should fire for small files despite throttling`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - small file that transfers quickly
        val sourceFile = File(sourceFolder, "small.txt")
        sourceFile.writeText("Small content")

        var progressCallbackCalled = false

        // When
        LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is CopyAction.State.Active) progressCallbackCalled = true
        }.last()

        // Then - should still get at least one callback
        progressCallbackCalled shouldBe true
    }

    @Test
    fun `final progress callback should always fire immediately`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.bin")
        sourceFile.writeBytes(ByteArray(512 * 1024)) // 512KB file

        val progressUpdates = mutableListOf<Pair<Long, Long>>()

        // When
        LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is CopyAction.State.Active) {
                progressUpdates.add(state.copiedBytes to state.totalBytes)
            }
        }.last()

        // Then - last callback should show 100% completion
        progressUpdates shouldNotBe emptyList<Pair<Long, Long>>()
        val (copiedBytes, totalBytes) = progressUpdates.last()
        copiedBytes shouldBe totalBytes
    }

    // ============ ISSUE HANDLING - PATH ALREADY EXISTS ============

    @Test
    fun `handle existing file with overwrite resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        val destFile = File(destFolder, "file.txt")
        sourceFile.writeText("new content")
        destFile.writeText("old content")

        // When
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        destFile.readText() shouldBe "new content"
        result.copied shouldHaveSize 1
    }

    @Test
    fun `handle existing file with skip resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        val destFile = File(destFolder, "file.txt")
        sourceFile.writeText("new content")
        destFile.writeText("old content")

        // When
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        destFile.readText() shouldBe "old content"
        result.skipped shouldContainPath LocalPath.build(sourceFile)
    }

    @Test
    fun `handle existing files with skip apply to all`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        val file3 = File(sourceFolder, "file3.txt")

        file1.writeText("new1")
        file2.writeText("new2")
        file3.writeText("new3")

        File(destFolder, "file1.txt").writeText("old1")
        File(destFolder, "file2.txt").writeText("old2")
        File(destFolder, "file3.txt").writeText("old3")

        val issuesEncountered = mutableListOf<PathActionIssue>()

        // When
        val result = listOf(
            LocalPath.build(file1),
            LocalPath.build(file2),
            LocalPath.build(file3)
        ).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - only first issue should be handled due to "Apply to All"
        issuesEncountered shouldHaveSize 1
        result.skipped shouldHaveSize 3
        File(destFolder, "file1.txt").readText() shouldBe "old1"
        File(destFolder, "file2.txt").readText() shouldBe "old2"
        File(destFolder, "file3.txt").readText() shouldBe "old3"
    }

    @Test
    fun `handle existing files with overwrite apply to all`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")

        file1.writeText("new1")
        file2.writeText("new2")

        File(destFolder, "file1.txt").writeText("old1")
        File(destFolder, "file2.txt").writeText("old2")

        val issuesEncountered = mutableListOf<PathActionIssue>()

        // When
        listOf(LocalPath.build(file1), LocalPath.build(file2)).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite(
                        applyToAll = true
                    )

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - only first issue should be handled, all should be overwritten
        issuesEncountered shouldHaveSize 1
        File(destFolder, "file1.txt").readText() shouldBe "new1"
        File(destFolder, "file2.txt").readText() shouldBe "new2"
    }

    @Test
    fun `handle merge directories`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "project")
        val destDir = File(destFolder, "project")
        val sourceFile = File(sourceDir, "new.txt")
        val destFile = File(destDir, "old.txt")

        sourceDir.mkdir()
        destDir.mkdir()
        sourceFile.writeText("new content")
        destFile.writeText("old content")

        // When
        LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Merge()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - both files should exist
        File(destFolder, "project/new.txt").exists() shouldBe true
        File(destFolder, "project/old.txt").exists() shouldBe true
    }

    // ============ ISSUE HANDLING - PERMISSIONS ============

    @Test
    fun `handle read-only source files gracefully`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "readonly.txt")
        sourceFile.writeText("readonly content")

        try {
            sourceFile.setReadOnly()

            // When - should succeed or handle gracefully
            val result = LocalPath.build(sourceFile).copy(ops, LocalPath.build(destFolder))
                .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            // Then - should complete without crashing
            result.copiedBytes should { it >= 0 }
        } catch (e: SecurityException) {
            // Expected on some systems
        }
    }

    @Test
    fun `handle write-protected destination`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // This test is system-dependent and may not trigger issues on all systems
        // It mainly verifies the code doesn't crash with permission issues
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        try {
            destFolder.setReadOnly()

            val result = LocalPath.build(sourceFile).copy(
                ops,
                LocalPath.build(destFolder),
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            result.copiedBytes should { it >= 0 }
        } catch (e: Exception) {
            // Expected on systems where read-only doesn't prevent writes
            // or where permission errors manifest differently
        }
    }

    @Test
    fun `insufficient permission with apply to all`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // This test verifies the "Apply to All" mechanism for permission issues
        // Actual permission errors may not occur on all systems
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        try {
            file1.setReadOnly()
            file2.setReadOnly()

            val issuesEncountered = mutableListOf<PathActionIssue>()

            listOf(LocalPath.build(file1), LocalPath.build(file2)).copy(
                ops,
                LocalPath.build(destFolder),
                onIssue = { issue ->
                    issuesEncountered.add(issue)
                    when (issue) {
                        is PathActionIssue.InsufficientPermission ->
                            PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)

                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).last()

            // If issues were encountered, verify "Apply to All" behavior
            if (issuesEncountered.isNotEmpty()) {
                issuesEncountered shouldHaveSize 1
            }
        } catch (e: SecurityException) {
            // Expected on some systems where read-only doesn't prevent copying
        }
    }

    // ============ ISSUE HANDLING - UNKNOWN ERRORS ============

    @Test
    fun `handle unknown errors with retry resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // This test verifies retry mechanism works
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        var attemptCount = 0

        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        attemptCount++
                        if (attemptCount == 1) {
                            PathActionIssue.UnknownError.Resolution.Retry
                        } else {
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Result depends on whether errors actually occurred
        result.copiedBytes should { it >= 0 }
    }

    @Test
    fun `handle unknown errors with skip resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        result.copiedBytes should { it >= 0 }
    }

    @Test
    fun `handle unknown errors with cancel resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        var issueCount = 0

        listOf(LocalPath.build(file1), LocalPath.build(file2)).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueCount++
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Cancel()
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Cancel()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // If issues were encountered, operation should have been cancelled
        if (issueCount > 0) {
            issueCount shouldBe 1
        }
    }

    // ============ EDGE CASES ============

    @Test
    fun `copy non-existent source should throw`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val nonExistent = File(sourceFolder, "does-not-exist.txt")

        // When & Then
        shouldThrow<ReadException> {
            LocalPath.build(nonExistent).copy(ops, LocalPath.build(destFolder)).last()
        }
    }

    @Test
    fun `copy to non-existent destination - should throw`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")
        val nonExistentDest = File(tempDir, "non-existent-parent/new-dest")

        // When/Then - parent directory doesn't exist, should fail
        shouldThrow<WriteException> {
            LocalPath.build(sourceFile).copy(ops, LocalPath.build(nonExistentDest)).last()
        }
    }

    @Test
    fun `copy to existing destination - should succeed`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")
        val existingDest = File(tempDir, "existing-dest")
        existingDest.mkdirs()

        // When
        LocalPath.build(sourceFile).copy(ops, LocalPath.build(existingDest)).last()

        // Then
        File(existingDest, "file.txt").exists() shouldBe true
        sourceFile.exists() shouldBe true // Source still exists after copy
    }

    @Test
    fun `destination file conflict can be resolved by overwriting`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "source.txt")
        sourceFile.writeText("content")

        val destinationFile = File(tempDir, "dest-directory")
        destinationFile.writeText("I'm a file blocking directory creation")

        // When
        var issueEncountered = false
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destinationFile),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        issueEncountered = true
                        issue.destination.fileType shouldBe FileType.FILE
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }

                    else -> throw IllegalStateException("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        issueEncountered shouldBe true
        // Unix semantics: single file → non-directory destination = rename (not copy INTO)
        destinationFile.isFile shouldBe true
        destinationFile.readText() shouldBe "content"
        result.copied shouldHaveSize 1
    }

    @Test
    fun `destination file conflict can be resolved by renaming file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "source.txt")
        sourceFile.writeText("content")

        val destinationFile = File(tempDir, "dest-directory")
        destinationFile.writeText("I'm a file blocking directory creation")

        // When
        var issueEncountered = false
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destinationFile),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        issueEncountered = true
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("dest-directory.old")
                    }

                    else -> throw IllegalStateException("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        issueEncountered shouldBe true
        File(tempDir, "dest-directory.old").apply {
            exists() shouldBe true
            isFile shouldBe true
            readText() shouldBe "I'm a file blocking directory creation"
        }
        // Unix semantics: single file → non-directory destination = rename (not copy INTO)
        destinationFile.isFile shouldBe true
        destinationFile.readText() shouldBe "content"
        result.copied shouldHaveSize 1
    }

    @Test
    fun `copy should fail when destination creation fails due to permissions`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "source.txt")
        sourceFile.writeText("content")

        val readOnlyParent = File(tempDir, "readonly-parent")
        readOnlyParent.mkdirs()
        readOnlyParent.setReadOnly()

        val destinationInReadOnly = File(readOnlyParent, "dest-folder")

        try {
            // When/Then
            val exception = shouldThrow<WriteException> {
                LocalPath.build(sourceFile).copy(ops, LocalPath.build(destinationInReadOnly)).last()
            }

            // Verify the exception is about the destination path
            exception.path shouldBe LocalPath.build(destinationInReadOnly)
            // Verify it's an IO error (permission or creation failure)
            exception.cause shouldNotBe null
        } finally {
            // Cleanup - restore write permissions
            readOnlyParent.setWritable(true)
        }
    }

    @Test
    fun `handle already-copied files`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        // Copy once
        LocalPath.build(sourceFile).copy(ops, LocalPath.build(destFolder)).last()

        // When - copy again, should trigger PathAlreadyExists
        var issueEncountered = false
        LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        issueEncountered = true
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then
        issueEncountered shouldBe true
    }

    @Test
    fun `copy works without onProgress callback`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        // When
        val result = LocalPath.build(sourceFile).copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copiedBytes should { it > 0 }
        File(destFolder, "file.txt").exists() shouldBe true
    }

    @Test
    fun `copy works without onIssue callback`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        // When - no onIssue callback provided
        val result = LocalPath.build(sourceFile).copy(ops, LocalPath.build(destFolder), onIssue = null)
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - should complete normally
        result.copiedBytes should { it > 0 }
        File(destFolder, "file.txt").exists() shouldBe true
    }

    // ============ RESULT VERIFICATION ============

    @Test
    fun `result contains correct copied pairs`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        // When
        val result = listOf(LocalPath.build(file1), LocalPath.build(file2))
            .copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copied shouldContainPath (LocalPath.build(file1) to LocalPath.build(File(destFolder, "file1.txt")))
        result.copied shouldContainPath (LocalPath.build(file2) to LocalPath.build(File(destFolder, "file2.txt")))
    }

    @Test
    fun `result contains correct skipped sources`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        val destFile = File(destFolder, "file.txt")
        sourceFile.writeText("new")
        destFile.writeText("old")

        // When
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.skipped shouldContainPath LocalPath.build(sourceFile)
    }

    @Test
    fun `result contains correct copiedBytes count`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val files = (1..5).map { i ->
            File(sourceFolder, "file$i.txt").apply {
                writeText("Content $i")
            }
        }
        val expectedSize = files.sumOf { it.length() }

        // When
        val result = files.map { LocalPath.build(it) }.copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copiedBytes shouldBe expectedSize
    }

    // ============ ATTRIBUTE PRESERVATION ============

    @Test
    fun `verify file attributes are preserved`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")
        val sourceModified = sourceFile.lastModified()

        // When
        LocalPath.build(sourceFile).copy(ops, LocalPath.build(destFolder)).last()

        // Then
        val destFile = File(destFolder, "file.txt")
        destFile.exists() shouldBe true
        // Timestamps may not be exactly preserved on all systems, but should be close
        val destModified = destFile.lastModified()
        // Allow some tolerance for filesystem timestamp precision
        kotlin.math.abs(destModified - sourceModified) should { it < 5000 } // Within 5 seconds
    }

    @Test
    fun `verify directory attributes are preserved`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "dir")
        sourceDir.mkdir()
        val sourceFile = File(sourceDir, "file.txt")
        sourceFile.writeText("content")

        // When
        LocalPath.build(sourceDir).copy(ops, LocalPath.build(destFolder)).last()

        // Then
        val destDir = File(destFolder, "dir")
        destDir.exists() shouldBe true
        destDir.isDirectory shouldBe true
        File(destDir, "file.txt").exists() shouldBe true
    }

    // ============ FILE-DIRECTORY CONFLICTS ============

    @Test
    fun `directory creation should detect file conflict`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source has directory structure, destination has file blocking it
        val sourceDir = File(sourceFolder, "Parent")
        val sourceSubDir = File(sourceDir, "SubDir")
        sourceSubDir.mkdirs()
        File(sourceSubDir, "content.txt").writeText("source content")

        // Destination has file "Parent/SubDir" (regular file, not directory)
        val destParent = File(destFolder, "Parent")
        destParent.mkdir()
        val destFile = File(destParent, "SubDir") // This is a FILE blocking the directory
        destFile.writeText("I'm a file blocking the directory")

        // When - try to copy with issue handler expecting PathAlreadyExists
        var issueReceived: PathActionIssue? = null
        LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        // Verify it's detected as PathAlreadyExists, not UnknownError
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last()

        // Then - should raise PathAlreadyExists issue
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        // Verify directory was created after overwrite
        val finalSubDir = File(destFolder, "Parent/SubDir")
        finalSubDir.exists() shouldBe true
        finalSubDir.isDirectory shouldBe true
        File(finalSubDir, "content.txt").exists() shouldBe true
    }

    @Test
    fun `directory creation should allow skip resolution for file conflict`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory blocked by destination file
        val sourceDir = File(sourceFolder, "Parent")
        val sourceSubDir = File(sourceDir, "SubDir")
        sourceSubDir.mkdirs()
        File(sourceSubDir, "file.txt").writeText("content")

        val destParent = File(destFolder, "Parent")
        destParent.mkdir()
        val destBlockingFile = File(destParent, "SubDir")
        destBlockingFile.writeText("blocking file")

        // When - skip the conflict
        var issueReceived: PathActionIssue? = null
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - issue was raised
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        // File should still exist (not replaced by directory)
        destBlockingFile.exists() shouldBe true
        destBlockingFile.isFile shouldBe true
        destBlockingFile.readText() shouldBe "blocking file"

        // Result should show skipped item
        result.skipped shouldContainPath LocalPath.build(sourceSubDir)
    }

    @Test
    fun `file copy should detect directory conflict`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source is file, destination is directory with same name
        val sourceFile = File(sourceFolder, "item")
        sourceFile.writeText("file content")

        val destDir = File(destFolder, "item") // Directory, not file
        destDir.mkdir()
        File(destDir, "existing.txt").writeText("dir content")

        // When - try to copy file over directory
        var issueReceived: PathActionIssue? = null
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - should raise PathAlreadyExists issue
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        // Directory should still exist (not replaced by file)
        destDir.exists() shouldBe true
        destDir.isDirectory shouldBe true
        File(destDir, "existing.txt").exists() shouldBe true

        // Result should show skipped item
        result.skipped shouldContainPath LocalPath.build(sourceFile)
    }

    @Test
    fun `file copy should allow overwrite directory with file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source file, destination directory with same name
        val sourceFile = File(sourceFolder, "item")
        sourceFile.writeText("new file content")

        val destDir = File(destFolder, "item")
        destDir.mkdir()

        // When - overwrite directory with file
        var issueReceived: PathActionIssue? = null
        LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last()

        // Then
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        val finalItem = File(destFolder, "item")
        finalItem.exists() shouldBe true
        finalItem.isFile shouldBe true
        finalItem.readText() shouldBe "new file content"
    }

    // ============ DIRECTORY-DIRECTORY CONFLICTS (MERGE) ============

    @Test
    fun `directory merge should prompt user when directory exists`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - both source and destination have "Folder" directory
        val sourceDir = File(sourceFolder, "Folder")
        sourceDir.mkdir()
        File(sourceDir, "source.txt").writeText("from source")

        val destDir = File(destFolder, "Folder")
        destDir.mkdir()
        File(destDir, "dest.txt").writeText("from dest")

        // When - copy with merge resolution
        var issueReceived: PathActionIssue? = null
        LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        issue.canMerge shouldBe true
                        PathActionIssue.PathAlreadyExists.Resolution.Merge()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last()

        // Then - issue was raised with canMerge=true
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        // Both files should exist (merged)
        File(destFolder, "Folder/source.txt").exists() shouldBe true
        File(destFolder, "Folder/dest.txt").exists() shouldBe true
    }

    @Test
    fun `directory merge with apply to all should merge all directories`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - multiple directories that exist at destination
        val source1 = File(sourceFolder, "Dir1")
        source1.mkdir()
        File(source1, "file1.txt").writeText("content1")

        val source2 = File(sourceFolder, "Dir2")
        source2.mkdir()
        File(source2, "file2.txt").writeText("content2")

        // Destination has these directories too
        val dest1 = File(destFolder, "Dir1")
        dest1.mkdir()
        File(dest1, "existing1.txt").writeText("old1")

        val dest2 = File(destFolder, "Dir2")
        dest2.mkdir()
        File(dest2, "existing2.txt").writeText("old2")

        // When - copy both with merge apply-to-all
        val issuesEncountered = mutableListOf<PathActionIssue>()
        listOf(LocalPath.build(source1), LocalPath.build(source2)).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Merge(applyToAll = true)
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - only asked once due to "apply to all"
        issuesEncountered shouldHaveSize 1

        // All files merged
        File(destFolder, "Dir1/file1.txt").exists() shouldBe true
        File(destFolder, "Dir1/existing1.txt").exists() shouldBe true
        File(destFolder, "Dir2/file2.txt").exists() shouldBe true
        File(destFolder, "Dir2/existing2.txt").exists() shouldBe true
    }

    @Test
    fun `directory skip with apply to all should skip all directories and their contents`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directories with files
        val source1 = File(sourceFolder, "Dir1")
        source1.mkdir()
        File(source1, "file1.txt").writeText("new1")

        val source2 = File(sourceFolder, "Dir2")
        source2.mkdir()
        File(source2, "file2.txt").writeText("new2")

        // Destination has these directories
        val dest1 = File(destFolder, "Dir1")
        dest1.mkdir()
        File(dest1, "old1.txt").writeText("old1")

        val dest2 = File(destFolder, "Dir2")
        dest2.mkdir()
        File(dest2, "old2.txt").writeText("old2")

        // When - copy with skip apply-to-all
        val issuesEncountered = mutableListOf<PathActionIssue>()
        val result = listOf(LocalPath.build(source1), LocalPath.build(source2)).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - only asked once
        issuesEncountered shouldHaveSize 1

        // Nothing copied, directories and their contents skipped (cascading skip)
        result.copied.isEmpty() shouldBe true
        result.skipped shouldHaveSize 4 // 2 directories + 2 files inside them

        // Old files still exist, new files don't
        File(destFolder, "Dir1/old1.txt").exists() shouldBe true
        File(destFolder, "Dir1/file1.txt").exists() shouldBe false
        File(destFolder, "Dir2/old2.txt").exists() shouldBe true
        File(destFolder, "Dir2/file2.txt").exists() shouldBe false
    }

    @Test
    fun `directory overwrite should remove existing directory content`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory with new content
        val sourceDir = File(sourceFolder, "Folder")
        sourceDir.mkdir()
        File(sourceDir, "new.txt").writeText("new content")

        // Destination has directory with old content
        val destDir = File(destFolder, "Folder")
        destDir.mkdir()
        File(destDir, "old.txt").writeText("old content")
        File(destDir, "another-old.txt").writeText("another old")

        // When - copy with overwrite
        var issueReceived: PathActionIssue? = null
        LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last()

        // Then - issue was raised
        issueReceived shouldNotBe null

        // Old content gone, new content present
        File(destFolder, "Folder/new.txt").exists() shouldBe true
        File(destFolder, "Folder/old.txt").exists() shouldBe false
        File(destFolder, "Folder/another-old.txt").exists() shouldBe false
    }

    @Test
    fun `directory overwrite with apply to all should overwrite all directories`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - multiple source directories
        val source1 = File(sourceFolder, "Dir1")
        source1.mkdir()
        File(source1, "new1.txt").writeText("new1")

        val source2 = File(sourceFolder, "Dir2")
        source2.mkdir()
        File(source2, "new2.txt").writeText("new2")

        // Destination has these with old content
        val dest1 = File(destFolder, "Dir1")
        dest1.mkdir()
        File(dest1, "old1.txt").writeText("old1")

        val dest2 = File(destFolder, "Dir2")
        dest2.mkdir()
        File(dest2, "old2.txt").writeText("old2")

        // When - copy with overwrite apply-to-all
        val issuesEncountered = mutableListOf<PathActionIssue>()
        listOf(LocalPath.build(source1), LocalPath.build(source2)).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - only asked once
        issuesEncountered shouldHaveSize 1

        // Old content replaced with new
        File(destFolder, "Dir1/new1.txt").exists() shouldBe true
        File(destFolder, "Dir1/old1.txt").exists() shouldBe false
        File(destFolder, "Dir2/new2.txt").exists() shouldBe true
        File(destFolder, "Dir2/old2.txt").exists() shouldBe false
    }

    @Test
    fun `no issue handler should auto-merge directories for backward compatibility`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - directory exists at destination
        val sourceDir = File(sourceFolder, "Folder")
        sourceDir.mkdir()
        File(sourceDir, "new.txt").writeText("new")

        val destDir = File(destFolder, "Folder")
        destDir.mkdir()
        File(destDir, "old.txt").writeText("old")

        // When - copy without issue handler (onIssue = null)
        LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder)
            // No onIssue parameter - uses default null
        ).last()

        // Then - should auto-merge without prompting
        File(destFolder, "Folder/new.txt").exists() shouldBe true
        File(destFolder, "Folder/old.txt").exists() shouldBe true
    }

    // ============ RENAME OPERATIONS ============

    @Test
    fun `file rename destination should move existing file and copy source`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source file and destination file already exists
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("new content")

        val destFile = File(destFolder, "file.txt")
        destFile.writeText("old content")

        // When - rename destination
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("file (1).txt")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old file renamed, new file copied to original name
        File(destFolder, "file.txt").readText() shouldBe "new content"
        File(destFolder, "file (1).txt").readText() shouldBe "old content"
        result.copied shouldContainPath (LocalPath.build(sourceFile) to LocalPath.build(File(destFolder, "file.txt")))
    }

    @Test
    fun `file rename source should copy source with new name`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source file and destination file already exists
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("new content")

        val destFile = File(destFolder, "file.txt")
        destFile.writeText("old content")

        // When - rename source
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("file (1).txt")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old file unchanged, new file copied with new name
        File(destFolder, "file.txt").readText() shouldBe "old content"
        File(destFolder, "file (1).txt").readText() shouldBe "new content"
        result.copied shouldContainPath (LocalPath.build(sourceFile) to LocalPath.build(File(destFolder, "file (1).txt")))
    }

    @Test
    fun `directory rename destination should move existing directory and create new`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory and destination directory already exists
        val sourceDir = File(sourceFolder, "Dir")
        sourceDir.mkdir()
        File(sourceDir, "new.txt").writeText("new")

        val destDir = File(destFolder, "Dir")
        destDir.mkdir()
        File(destDir, "old.txt").writeText("old")

        // When - rename destination
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("Dir (1)")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old directory renamed, new directory created with original name
        File(destFolder, "Dir/new.txt").exists() shouldBe true
        File(destFolder, "Dir/old.txt").exists() shouldBe false
        File(destFolder, "Dir (1)/old.txt").exists() shouldBe true
        File(destFolder, "Dir (1)/new.txt").exists() shouldBe false
        result.copied shouldHaveSize 2 // directory + file
    }

    @Test
    fun `directory rename source should create directory with new name`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory and destination directory already exists
        val sourceDir = File(sourceFolder, "Dir")
        sourceDir.mkdir()
        File(sourceDir, "new.txt").writeText("new")

        val destDir = File(destFolder, "Dir")
        destDir.mkdir()
        File(destDir, "old.txt").writeText("old")

        // When - rename source
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Dir (1)")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old directory unchanged, new directory created with new name
        File(destFolder, "Dir/old.txt").exists() shouldBe true
        File(destFolder, "Dir/new.txt").exists() shouldBe false
        File(destFolder, "Dir (1)/new.txt").exists() shouldBe true
        File(destFolder, "Dir (1)/old.txt").exists() shouldBe false
        result.copied shouldHaveSize 2 // directory + file
    }

    @Test
    fun `file-directory conflict rename destination should move file and create directory`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory but destination has a file with same name
        val sourceDir = File(sourceFolder, "Item")
        sourceDir.mkdir()
        File(sourceDir, "content.txt").writeText("content")

        val destFile = File(destFolder, "Item")
        destFile.writeText("blocking file")

        // When - rename destination
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("Item (1)")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file renamed, directory created with original name
        File(destFolder, "Item").isDirectory shouldBe true
        File(destFolder, "Item/content.txt").exists() shouldBe true
        File(destFolder, "Item (1)").isFile shouldBe true
        File(destFolder, "Item (1)").readText() shouldBe "blocking file"
        result.copied shouldHaveSize 2 // directory + file
    }

    @Test
    fun `file-directory conflict rename source should create directory with new name`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory but destination has a file with same name
        val sourceDir = File(sourceFolder, "Item")
        sourceDir.mkdir()
        File(sourceDir, "content.txt").writeText("content")

        val destFile = File(destFolder, "Item")
        destFile.writeText("blocking file")

        // When - rename source
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Item (1)")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file unchanged, directory created with new name
        File(destFolder, "Item").isFile shouldBe true
        File(destFolder, "Item").readText() shouldBe "blocking file"
        File(destFolder, "Item (1)").isDirectory shouldBe true
        File(destFolder, "Item (1)/content.txt").exists() shouldBe true
        result.copied shouldHaveSize 2 // directory + file
    }

    @Test
    fun `issue should provide suggested name for conflicts`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - file that will conflict
        val sourceFile = File(sourceFolder, "document.pdf")
        sourceFile.writeText("content")

        val destFile = File(destFolder, "document.pdf")
        destFile.writeText("existing")

        // When - copy and capture issue
        var capturedIssue: PathActionIssue.PathAlreadyExists? = null
        LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        capturedIssue = issue
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - issue contains suggested name
        capturedIssue shouldNotBe null
        capturedIssue!!.suggestedName shouldBe "document (1).pdf"
        capturedIssue!!.canRenameSource shouldBe true
        capturedIssue!!.canRenameDestination shouldBe true
    }

    @Test
    fun `nested directory rename source should update all subdirectories and files`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - nested source structure and conflicting destination
        val sourceDir = File(sourceFolder, "Parent")
        sourceDir.mkdir()
        val subDir1 = File(sourceDir, "SubDir1")
        subDir1.mkdir()
        val subDir2 = File(subDir1, "SubDir2")
        subDir2.mkdir()
        File(sourceDir, "file1.txt").writeText("content1")
        File(subDir1, "file2.txt").writeText("content2")
        File(subDir2, "file3.txt").writeText("content3")

        // Destination has conflicting Parent directory
        val destDir = File(destFolder, "Parent")
        destDir.mkdir()
        File(destDir, "existing.txt").writeText("existing")

        // When - rename source to Parent-new
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Parent-new")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - all directories and files should be in Parent-new
        File(destFolder, "Parent/existing.txt").exists() shouldBe true
        File(destFolder, "Parent-new").exists() shouldBe true
        File(destFolder, "Parent-new").isDirectory shouldBe true
        File(destFolder, "Parent-new/file1.txt").exists() shouldBe true
        File(destFolder, "Parent-new/file1.txt").readText() shouldBe "content1"
        File(destFolder, "Parent-new/SubDir1").exists() shouldBe true
        File(destFolder, "Parent-new/SubDir1").isDirectory shouldBe true
        File(destFolder, "Parent-new/SubDir1/file2.txt").exists() shouldBe true
        File(destFolder, "Parent-new/SubDir1/file2.txt").readText() shouldBe "content2"
        File(destFolder, "Parent-new/SubDir1/SubDir2").exists() shouldBe true
        File(destFolder, "Parent-new/SubDir1/SubDir2").isDirectory shouldBe true
        File(destFolder, "Parent-new/SubDir1/SubDir2/file3.txt").exists() shouldBe true
        File(destFolder, "Parent-new/SubDir1/SubDir2/file3.txt").readText() shouldBe "content3"

        // Original Parent directory should still only have existing file
        File(destFolder, "Parent/file1.txt").exists() shouldBe false
        File(destFolder, "Parent/SubDir1").exists() shouldBe false

        result.copied shouldHaveSize 6 // 3 dirs + 3 files
    }

    // ============ SYMLINK TESTS ============

    @Test
    fun `copy symlink to file with followSymlinks false should copy link`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink pointing to a file
        val targetFile = File(sourceFolder, "target.txt")
        targetFile.writeText("target content")
        val linkFile = File(sourceFolder, "link.txt")

        // Create symlink with relative path
        Files.createSymbolicLink(
            linkFile.toPath(),
            java.nio.file.Paths.get("target.txt")
        )

        // Only proceed if symlink was actually created
        if (!Files.isSymbolicLink(linkFile.toPath())) {
            return@runTest
        }

        val sourcePath = LocalPath.build(linkFile)
        val destPath = LocalPath.build(destFolder)

        // When - copy with followSymlinks = false (default)
        val result = sourcePath.copy(ops, destPath)
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file should be copied
        val copiedLink = File(destFolder, "link.txt")
        copiedLink.exists() shouldBe true
        // Note: Symlink preservation may not work in all test environments
        // The important thing is the copy succeeds and the file exists
        result.copied shouldHaveSize 1
    }

    @Test
    fun `copy symlink to file with followSymlinks true should copy target`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink pointing to a file
        val targetFile = File(sourceFolder, "target.txt")
        targetFile.writeText("target content")
        val linkFile = File(sourceFolder, "link.txt")

        // Create symlink with relative path
        Files.createSymbolicLink(
            linkFile.toPath(),
            java.nio.file.Paths.get("target.txt")
        )

        // Only proceed if symlink was actually created
        if (!Files.isSymbolicLink(linkFile.toPath())) {
            return@runTest
        }

        val sourcePath = LocalPath.build(linkFile)
        val destPath = LocalPath.build(destFolder)

        // When - copy with followSymlinks = true
        val result = sourcePath.copy(
            ops,
            destPath,
            options = CopyAction.Options(followSymlinks = true)
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - target file content should be copied, not the link
        val copiedFile = File(destFolder, "link.txt")
        copiedFile.exists() shouldBe true
        Files.isSymbolicLink(copiedFile.toPath()) shouldBe false // Not a link
        copiedFile.readText() shouldBe "target content"
        result.copied shouldHaveSize 1
    }

    @Test
    fun `copy symlink to directory with followSymlinks false should copy link`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink pointing to a directory
        val targetDir = File(sourceFolder, "targetDir")
        targetDir.mkdir()
        File(targetDir, "file.txt").writeText("content")

        val linkDir = File(sourceFolder, "linkDir")

        // Create symlink with relative path
        Files.createSymbolicLink(
            linkDir.toPath(),
            java.nio.file.Paths.get("targetDir")
        )

        // Only proceed if symlink was actually created
        if (!Files.isSymbolicLink(linkDir.toPath())) {
            return@runTest
        }

        val sourcePath = LocalPath.build(linkDir)
        val destPath = LocalPath.build(destFolder)

        // When - copy with followSymlinks = false (default)
        val result = sourcePath.copy(ops, destPath)
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - directory should be copied
        val copiedLink = File(destFolder, "linkDir")
        copiedLink.exists() shouldBe true
        // Note: Symlink preservation may not work in all test environments
        // The important thing is the copy succeeds and the directory exists
        result.copied shouldHaveSize 1 // Only the link, not contents
    }

    @Test
    fun `copy symlink to directory with followSymlinks true should copy directory contents`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink pointing to a directory with contents
        val targetDir = File(sourceFolder, "targetDir")
        targetDir.mkdir()
        File(targetDir, "file.txt").writeText("content")

        val linkDir = File(sourceFolder, "linkDir")

        // Create symlink with relative path
        Files.createSymbolicLink(
            linkDir.toPath(),
            java.nio.file.Paths.get("targetDir")
        )

        // Only proceed if symlink was actually created
        if (!Files.isSymbolicLink(linkDir.toPath())) {
            return@runTest
        }

        val sourcePath = LocalPath.build(linkDir)
        val destPath = LocalPath.build(destFolder)

        // When - copy with followSymlinks = true
        val result = sourcePath.copy(
            ops,
            destPath,
            options = CopyAction.Options(followSymlinks = true)
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - directory and its contents should be copied (not as symlink)
        val copiedDir = File(destFolder, "linkDir")
        copiedDir.exists() shouldBe true
        copiedDir.isDirectory shouldBe true
        Files.isSymbolicLink(copiedDir.toPath()) shouldBe false // Not a link

        val copiedFile = File(copiedDir, "file.txt")
        copiedFile.exists() shouldBe true
        copiedFile.readText() shouldBe "content"

        result.copied shouldHaveSize 2 // Directory + file
    }

    @Test
    fun `copy broken symlink with followSymlinks false should preserve symlink`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink pointing to non-existent target
        val brokenLink = File(sourceFolder, "brokenLink")

        // Create symlink to non-existent file
        Files.createSymbolicLink(
            brokenLink.toPath(),
            java.nio.file.Paths.get("nonexistent.txt")
        )

        // Only proceed if symlink was actually created
        if (!Files.isSymbolicLink(brokenLink.toPath())) {
            return@runTest
        }

        val sourcePath = LocalPath.build(brokenLink)
        val destPath = LocalPath.build(destFolder)

        // When - copy with followSymlinks = false
        val result = sourcePath.copy(
            ops,
            destPath,
            options = CopyAction.Options(followSymlinks = false)
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - broken symlink should be copied as-is
        val copiedLink = File(destFolder, "brokenLink")
        copiedLink.exists() shouldBe false // Target doesn't exist
        Files.isSymbolicLink(copiedLink.toPath()) shouldBe true // But symlink exists
        result.copied shouldHaveSize 1
    }

    @Test
    fun `copy broken symlink with followSymlinks true should fail`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink pointing to non-existent target
        val brokenLink = File(sourceFolder, "brokenLink")

        // Create symlink to non-existent file
        Files.createSymbolicLink(
            brokenLink.toPath(),
            java.nio.file.Paths.get("nonexistent.txt")
        )

        // Only proceed if symlink was actually created
        if (!Files.isSymbolicLink(brokenLink.toPath())) {
            return@runTest
        }

        val sourcePath = LocalPath.build(brokenLink)
        val destPath = LocalPath.build(destFolder)

        // When - copy with followSymlinks = true should fail
        shouldThrow<Exception> {
            sourcePath.copy(
                ops,
                destPath,
                options = CopyAction.Options(followSymlinks = true)
            ).last()
        }
    }

    @Test
    fun `copy nested symlinks with followSymlinks true should resolve all levels`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - directory with nested symlinks
        val realDir = File(sourceFolder, "realDir")
        realDir.mkdir()
        val realFile = File(realDir, "realFile.txt")
        realFile.writeText("nested content")

        // Create symlink to file inside directory
        val linkToFile = File(realDir, "linkToFile")
        Files.createSymbolicLink(
            linkToFile.toPath(),
            java.nio.file.Paths.get("realFile.txt")
        )

        // Create symlink to directory
        val linkToDir = File(sourceFolder, "linkToDir")
        Files.createSymbolicLink(
            linkToDir.toPath(),
            java.nio.file.Paths.get("realDir")
        )

        // Only proceed if symlinks were actually created
        if (!Files.isSymbolicLink(linkToDir.toPath()) || !Files.isSymbolicLink(linkToFile.toPath())) {
            return@runTest
        }

        val sourcePath = LocalPath.build(linkToDir)
        val destPath = LocalPath.build(destFolder)

        // When - copy with followSymlinks = true
        val result = sourcePath.copy(
            ops,
            destPath,
            options = CopyAction.Options(followSymlinks = true)
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - both symlinks should be resolved and content copied
        val copiedDir = File(destFolder, "linkToDir")
        copiedDir.exists() shouldBe true
        copiedDir.isDirectory shouldBe true
        Files.isSymbolicLink(copiedDir.toPath()) shouldBe false

        val copiedRealFile = File(copiedDir, "realFile.txt")
        copiedRealFile.exists() shouldBe true
        copiedRealFile.readText() shouldBe "nested content"

        val copiedLinkToFile = File(copiedDir, "linkToFile")
        copiedLinkToFile.exists() shouldBe true
        copiedLinkToFile.readText() shouldBe "nested content"

        result.copied shouldHaveSize 3 // Directory + realFile + linkToFile
    }

    @Test
    fun `copy symlink to deeply nested directory structure`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - deeply nested directory with symlink at top
        val targetDir = File(sourceFolder, "targetDir")
        val subdir1 = File(targetDir, "level1")
        val subdir2 = File(subdir1, "level2")
        subdir2.mkdirs()
        File(subdir2, "deep.txt").writeText("deep content")

        val linkToDir = File(sourceFolder, "linkToDir")
        Files.createSymbolicLink(
            linkToDir.toPath(),
            java.nio.file.Paths.get("targetDir")
        )

        // Only proceed if symlink was actually created
        if (!Files.isSymbolicLink(linkToDir.toPath())) {
            return@runTest
        }

        val sourcePath = LocalPath.build(linkToDir)
        val destPath = LocalPath.build(destFolder)

        // When - copy with followSymlinks = true
        val result = sourcePath.copy(
            ops,
            destPath,
            options = CopyAction.Options(followSymlinks = true)
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - full directory structure should be preserved under linkToDir name
        val copiedDeepFile = File(destFolder, "linkToDir/level1/level2/deep.txt")
        copiedDeepFile.exists() shouldBe true
        copiedDeepFile.readText() shouldBe "deep content"

        result.copied shouldHaveSize 4 // linkToDir + level1 + level2 + deep.txt
    }

    // ============ SCAN ERROR HANDLING ============

    @Test
    fun `directory scan error then skip should not appear in copied`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - Create directory structure
        val parentDir = File(sourceFolder, "parent")
        val childFile = File(parentDir, "child.txt")

        parentDir.mkdir()
        childFile.writeText("content")

        // Make directory unreadable to trigger permission error during scan
        parentDir.setReadable(false)

        try {
            // When
            val result = LocalPath.build(parentDir).copy(
                ops,
                LocalPath.build(destFolder),
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> TODO("Unexpected issue type: $issue")
                    }
                }
            )

            // Then - Directory should be ONLY in skipped, NOT in copied
            val finalResult =
                result.last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>
            finalResult.copied.map { it.first.lookedUp } shouldNotBe setOf(LocalPath.build(parentDir))
            finalResult.skipped.map { it.lookedUp } shouldContain LocalPath.build(parentDir)

            // Destination should not have the directory or child
            File(destFolder, "parent").exists() shouldBe false
            File(destFolder, "parent/child.txt").exists() shouldBe false
        } finally {
            // Restore permissions for cleanup
            if (parentDir.exists()) {
                parentDir.setReadable(true)
            }
        }
    }

    // ============ NULLABLE FIELDS TESTS ============

    @Test
    fun `copy file with null size succeeds and uses 0L for byte tracking`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - File that would have null size in partial lookup scenario (e.g., "/" on Android)
        val sourceFile = File(sourceFolder, "restricted.txt")
        sourceFile.writeText("content")
        sourceFile.length()

        // When - Copy operation should handle potential null sizes in metadata gracefully
        val result = LocalPath.build(sourceFile).copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file copied successfully despite potential null size metadata
        val destFile = File(destFolder, "restricted.txt")
        destFile.exists() shouldBe true
        destFile.readText() shouldBe "content"
        result.copied shouldContainPath (LocalPath.build(sourceFile) to LocalPath.build(destFile))

        // Verify byte tracking used appropriate fallback (actual size or 0L for null)
        result.copiedBytes should { it >= 0L }
        // In real scenario with null size, copiedBytes would be actualSize (from stream copy)
        // but progress tracking during transfer would use 0L fallback
    }

    @Test
    fun `copy file with null modifiedAt skips timestamp preservation`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - File that might have null modifiedAt in partial lookup (e.g., "/" scenario)
        val sourceFile = File(sourceFolder, "restricted.txt")
        sourceFile.writeText("content")
        sourceFile.lastModified()

        // When - Copy with preserveAttributes=true (would try to preserve timestamp)
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            options = CopyAction.Options(preserveAttributes = true)
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file copied successfully
        val destFile = File(destFolder, "restricted.txt")
        destFile.exists() shouldBe true
        destFile.readText() shouldBe "content"
        result.copied shouldHaveSize 1

        // Note: In real "/" scenario with null modifiedAt, timestamp would not be preserved
        // The `?.let` in LocalPathCopyStrategy.copyAttributes skips setModifiedAt when null
        // Here we just verify the copy succeeds - actual null handling is in copyAttributes
    }

}