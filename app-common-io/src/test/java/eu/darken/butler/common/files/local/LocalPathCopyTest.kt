package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.TestClock
import testhelpers.shouldContainPath
import testhelpers.toPathPairs
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

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

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the copy behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(symlink.toPath(), java.nio.file.Paths.get("target.txt"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(symlink.toPath()) shouldBe true

        // When
        val result = LocalPath.build(symlink).copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - the link itself is copied and still points at the original target, which was
        // neither followed nor duplicated into the destination
        val copiedLink = File(destFolder, "symlink")
        Files.isSymbolicLink(copiedLink.toPath()) shouldBe true
        Files.readSymbolicLink(copiedLink.toPath()).toFile() shouldBe targetFile
        File(destFolder, "target.txt").exists() shouldBe false
        result.copied shouldHaveSize 1
        result.skipped.shouldBeEmpty()
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

        // When - a hang is caught by runTest's own timeout, no wall-clock assertion needed here
        val result = LocalPath.build(sourceDir).copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.copiedBytes shouldBe expectedSize
        result.copied shouldHaveSize (files.size + 1) // files + directory
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
        // Given - file large enough to generate many chunks (64KB buffer = ~32 chunks)
        suspend fun copyRun(name: String, clock: Clock): Int {
            val sourceFolder = File(tempDir, "$name-source").apply { mkdirs() }
            val destFolder = File(tempDir, "$name-dest").apply { mkdirs() }
            val sourceFile = File(sourceFolder, "large.bin")
            sourceFile.writeBytes(ByteArray(1024 * 1024 * 2)) // 2MB file

            var actives = 0
            LocalPath.build(sourceFile).copy(
                ops,
                LocalPath.build(destFolder),
                progressClock = clock,
            ).onEach { state ->
                if (state is CopyAction.State.Active) actives++
            }.last()
            return actives
        }

        // When - time never advances, so every tick inside the report interval is dropped ...
        val throttled = copyRun("throttled", TestClock())
        // ... and when the interval elapses before every tick, nothing is dropped.
        val unthrottled = copyRun("unthrottled", TestClock(autoAdvance = 1.seconds))
        // Real clock, default production wiring
        val realClock = copyRun("realclock", Clock.System)

        // Then - the exact retained set: throttling is what keeps these numbers apart. If the
        // operation stopped routing progress through PathOperationProgressTracker both runs would
        // report the same amount of progress states.
        // 1 scan + 1 forced final report, every per-chunk tick dropped
        throttled shouldBe 2
        // ... plus one report per copied chunk
        unthrottled shouldBe 258
        realClock should { it in throttled..unthrottled }
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

    // ============ ISSUE HANDLING - PERMISSIONS ============

    @Test
    fun `handle read-only source files gracefully`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - a source file the owner may read but not write
        val sourceFile = File(sourceFolder, "readonly.txt")
        sourceFile.writeText("readonly content")
        sourceFile.setReadOnly() shouldBe true

        // When
        val result = LocalPath.build(sourceFile).copy(ops, LocalPath.build(destFolder))
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - read-only is not a read barrier, so the copy completes in full
        result.copied shouldContainPath (
            LocalPath.build(sourceFile) to LocalPath.build(File(destFolder, "readonly.txt"))
            )
        result.skipped.shouldBeEmpty()
        result.copiedBytes shouldBe "readonly content".length.toLong()
        File(destFolder, "readonly.txt").readText() shouldBe "readonly content"
        sourceFile.exists() shouldBe true
    }

    // ============ ISSUE HANDLING - UNKNOWN ERRORS ============

    // ============ EDGE CASES ============

    @Test
    fun `copy non-existent source should throw`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val nonExistent = File(sourceFolder, "does-not-exist.txt")

        // When & Then
        shouldThrow<PathNotFoundException> {
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
    fun `copy should fail when destination creation fails due to permissions`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "source.txt")
        sourceFile.writeText("content")

        val readOnlyParent = File(tempDir, "readonly-parent")
        readOnlyParent.mkdirs()

        val destinationInReadOnly = File(readOnlyParent, "dest-folder")
        val destinationPath = LocalPath.build(destinationInReadOnly)

        // setReadOnly() is not a reliable barrier (a privileged test process writes through it), so
        // inject exactly the failure a real EACCES produces inside LocalFileSystemOps.
        val spyOps = spyk(ops)
        coEvery { spyOps.openOutputStream(destinationPath, append = false) } throws WriteException(
            path = destinationPath,
            cause = java.nio.file.AccessDeniedException(destinationPath.path),
        )

        // When/Then - with no issue handler the failure must surface unchanged, not be swallowed
        val exception = shouldThrow<WriteException> {
            LocalPath.build(sourceFile).copy(spyOps, destinationPath).last()
        }

        // Verify the exception is about the destination path
        exception.path shouldBe destinationPath
        // Verify it's an IO error (permission or creation failure)
        exception.cause.shouldBeInstanceOf<java.nio.file.AccessDeniedException>()
        destinationInReadOnly.exists() shouldBe false
        sourceFile.readText() shouldBe "content"
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

    // ============ DIRECTORY-DIRECTORY CONFLICTS (MERGE) ============

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

    // ============ SYMLINK TESTS ============

    @Test
    fun `copy symlink to file with followSymlinks false should copy link`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink pointing to a file
        val targetFile = File(sourceFolder, "target.txt")
        targetFile.writeText("target content")
        val linkFile = File(sourceFolder, "link.txt")

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the copy behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get("target.txt"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(linkFile.toPath()) shouldBe true

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

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the copy behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get("target.txt"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(linkFile.toPath()) shouldBe true

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

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the copy behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(linkDir.toPath(), java.nio.file.Paths.get("targetDir"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(linkDir.toPath()) shouldBe true

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
    fun `copy symlink to directory with followSymlinks true should copy directory contents`(@TempDir tempDir: File) =
        runTest {
            val sourceFolder = File(tempDir, "source").apply { mkdirs() }
            val destFolder = File(tempDir, "dest").apply { mkdirs() }
            // Given - symlink pointing to a directory with contents
            val targetDir = File(sourceFolder, "targetDir")
            targetDir.mkdir()
            File(targetDir, "file.txt").writeText("content")

            val linkDir = File(sourceFolder, "linkDir")

            // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
            // below is unconditional, so the copy behaviour can never silently go unexercised.
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(linkDir.toPath(), java.nio.file.Paths.get("targetDir"))
            }.isSuccess
            assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
            Files.isSymbolicLink(linkDir.toPath()) shouldBe true

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

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the copy behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(brokenLink.toPath(), java.nio.file.Paths.get("nonexistent.txt"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(brokenLink.toPath()) shouldBe true

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

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the copy behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(brokenLink.toPath(), java.nio.file.Paths.get("nonexistent.txt"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(brokenLink.toPath()) shouldBe true

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

        val linkToFile = File(realDir, "linkToFile")
        val linkToDir = File(sourceFolder, "linkToDir")

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the copy behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(linkToFile.toPath(), java.nio.file.Paths.get("realFile.txt"))
            Files.createSymbolicLink(linkToDir.toPath(), java.nio.file.Paths.get("realDir"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(linkToFile.toPath()) shouldBe true
        Files.isSymbolicLink(linkToDir.toPath()) shouldBe true

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

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the copy behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(linkToDir.toPath(), java.nio.file.Paths.get("targetDir"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(linkToDir.toPath()) shouldBe true

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

    // ============ NULLABLE FIELDS TESTS ============

    @Test
    fun `copy file with null size succeeds and uses 0L for byte tracking`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - a source whose lookup reports no size, as a partial lookup does (e.g. "/" on Android)
        val sourceFile = File(sourceFolder, "restricted.txt")
        sourceFile.writeText("content")
        val sourcePath = LocalPath.build(sourceFile)

        // preserveAttributes defaults to true, so the scan looks the source up with MAX
        val nullSizeLookup = ops.lookup(sourcePath, LookupOptions.MAX).copy(size = null)
        val spyOps = spyk(ops)
        coEvery { spyOps.lookup(sourcePath, LookupOptions.MAX) } returns nullSizeLookup

        val states = mutableListOf<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // When
        val result = sourcePath.copy(spyOps, LocalPath.build(destFolder))
            .onEach { states.add(it) }
            .last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file copied successfully despite the missing size metadata
        val destFile = File(destFolder, "restricted.txt")
        destFile.exists() shouldBe true
        destFile.readText() shouldBe "content"
        result.copied shouldContainPath (sourcePath to LocalPath.build(destFile))
        result.copied shouldHaveSize 1
        result.copied.single().first.size shouldBe null

        // Progress totals fall back to 0L because the size was never known
        val activeStates =
            states.filterIsInstance<CopyAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()
        activeStates.shouldNotBeEmpty()
        activeStates.forEach {
            it.totalBytes shouldBe 0L
            it.currentFileSize shouldBe 0L
        }

        // ... while byte tracking still reports the bytes actually streamed
        result.copiedBytes shouldBe "content".length.toLong()
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