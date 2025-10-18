package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.last
import testhelpers.firstPath
import testhelpers.shouldBePaths
import testhelpers.shouldContainPath
import testhelpers.toPaths
import testhelpers.toPathPairs
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for GenericPathMove orchestrator using MockFileSystemOps.
 *
 * This validates that the move operation correctly:
 * 1. Calculates destination paths without duplication
 * 2. Preserves directory structure including top-level source name
 * 3. Cleans up source directories after moving children
 * 4. Handles edge cases like root-level files, deep nesting, etc.
 *
 * These tests mirror GenericPathCopyTest but verify move-specific behavior
 * like source cleanup and post-order directory deletion.
 */
class GenericPathMoveTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    private lateinit var strategy: GenericCrossTypeMoveStrategy<
        LocalPath, LocalPathLookup, LocalPathLookupExtended,
        LocalPath, LocalPathLookup, LocalPathLookupExtended
    >

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
        strategy = GenericCrossTypeMoveStrategy()
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ UNIX CP/MV SEMANTICS ============

    @Test
    fun `single source file to non-existent path uses destination as final path (rename)`() = runTest {
        // Tests Unix mv semantics: mv source.txt dest/renamed.txt (dest doesn't exist)
        // Expected: /dest/renamed.txt (as file)
        // NOT: /dest/renamed.txt/source.txt

        // Given - single source file, destination doesn't exist
        mockOps.addMockFile("/source/original.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/original.txt")
        val destPath = LocalPath.build("/dest/renamed.txt")

        // When
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - destination is used as final path (rename semantics)
        mockOps.hasFile("/dest/renamed.txt") shouldBe true
        mockOps.getFileType("/dest/renamed.txt") shouldBe FileType.FILE
        mockOps.getFileContent("/dest/renamed.txt") shouldBe "content".toByteArray()

        // Should NOT create /dest/renamed.txt/original.txt (bug we're preventing)
        mockOps.hasFile("/dest/renamed.txt/original.txt") shouldBe false

        // Source should be deleted
        mockOps.hasFile("/source/original.txt") shouldBe false

        result.movedFiles.size shouldBe 1
        result.movedFiles.firstPath() shouldBe (LocalPath.build("/source/original.txt") to LocalPath.build("/dest/renamed.txt"))
    }

    @Test
    fun `single source file to existing directory moves INTO directory`() = runTest {
        // Tests Unix mv semantics: mv source.txt dest/ (dest is existing directory)
        // Expected: /dest/file.txt
        // NOT: /dest as file

        // Given - single source file, destination is existing directory
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - moved INTO destination directory (appends source name)
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileType("/dest/file.txt") shouldBe FileType.FILE
        mockOps.getFileContent("/dest/file.txt") shouldBe "content".toByteArray()

        // Destination directory should still exist
        mockOps.getFileType("/dest") shouldBe FileType.DIRECTORY

        // Source should be deleted
        mockOps.hasFile("/source/file.txt") shouldBe false

        result.movedFiles.size shouldBe 1
        result.movedFiles.firstPath() shouldBe (LocalPath.build("/source/file.txt") to LocalPath.build("/dest/file.txt"))
    }

    @Test
    fun `single source directory to non-existent path uses destination as final path (rename)`() = runTest {
        // Tests Unix mv semantics: mv source/origdir dest/renameddir (dest doesn't exist)
        // Expected: /dest/renameddir/ with contents
        // NOT: /dest/renameddir/origdir/

        // Given - single source directory with file inside, destination doesn't exist
        mockOps.addMockDir("/source/origdir")
        mockOps.addMockFile("/source/origdir/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/origdir")
        val destPath = LocalPath.build("/dest/renameddir")

        // When
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - destination is used as final directory name (rename semantics)
        mockOps.hasFile("/dest/renameddir") shouldBe true
        mockOps.getFileType("/dest/renameddir") shouldBe FileType.DIRECTORY
        mockOps.hasFile("/dest/renameddir/file.txt") shouldBe true
        mockOps.getFileContent("/dest/renameddir/file.txt") shouldBe "content".toByteArray()

        // Should NOT create /dest/renameddir/origdir/ (bug we're preventing)
        mockOps.hasFile("/dest/renameddir/origdir") shouldBe false

        // Source should be deleted
        mockOps.hasFile("/source/origdir") shouldBe false

        result.movedFiles.size shouldBe 2 // directory + file
        result.movedFiles shouldContainPath (LocalPath.build("/source/origdir") to LocalPath.build("/dest/renameddir"))
        result.movedFiles shouldContainPath (LocalPath.build("/source/origdir/file.txt") to LocalPath.build("/dest/renameddir/file.txt"))
    }

    @Test
    fun `multiple sources to directory appends names to destination`() = runTest {
        // Tests Unix mv semantics: mv file1.txt file2.txt dest/
        // Expected: /dest/file1.txt and /dest/file2.txt
        // NOT: treating dest as final path

        // Given - multiple source files, destination is existing directory
        mockOps.addMockFile("/source/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/source/file2.txt", "content2".toByteArray())
        mockOps.addMockDir("/dest")

        val source1 = LocalPath.build("/source/file1.txt")
        val source2 = LocalPath.build("/source/file2.txt")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(source1, source2).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - multiple sources always append names to destination
        mockOps.hasFile("/dest/file1.txt") shouldBe true
        mockOps.getFileContent("/dest/file1.txt") shouldBe "content1".toByteArray()
        mockOps.hasFile("/dest/file2.txt") shouldBe true
        mockOps.getFileContent("/dest/file2.txt") shouldBe "content2".toByteArray()

        // Destination directory should still exist
        mockOps.getFileType("/dest") shouldBe FileType.DIRECTORY

        // Sources should be deleted
        mockOps.hasFile("/source/file1.txt") shouldBe false
        mockOps.hasFile("/source/file2.txt") shouldBe false

        result.movedFiles.size shouldBe 2
        result.movedFiles shouldContainPath (LocalPath.build("/source/file1.txt") to LocalPath.build("/dest/file1.txt"))
        result.movedFiles shouldContainPath (LocalPath.build("/source/file2.txt") to LocalPath.build("/dest/file2.txt"))
    }

    // ============ PATH CALCULATION TESTS ============

    @Test
    fun `move nested directory preserves structure without duplication`() = runTest {
        // Given - nested directory structure
        mockOps.addMockDir("/source/topfolder")
        mockOps.addMockDir("/source/topfolder/subfolder")
        mockOps.addMockFile("/source/topfolder/subfolder/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/topfolder")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - verify structure preserved: /dest/topfolder/subfolder/file.txt
        mockOps.hasFile("/dest/topfolder/subfolder/file.txt") shouldBe true
        mockOps.hasFile("/dest/topfolder/topfolder/subfolder/file.txt") shouldBe false  // No duplication!

        // Verify source cleaned up
        mockOps.hasFile("/source/topfolder/subfolder/file.txt") shouldBe false
        mockOps.hasFile("/source/topfolder/subfolder") shouldBe false
        mockOps.hasFile("/source/topfolder") shouldBe false

        result.movedFiles.size shouldBe 3  // directory + subdirectory + file
    }

    @Test
    fun `move deeply nested structure preserves all levels`() = runTest {
        // Given - deep nesting (5 levels)
        mockOps.addMockDir("/source/level1")
        mockOps.addMockDir("/source/level1/level2")
        mockOps.addMockDir("/source/level1/level2/level3")
        mockOps.addMockDir("/source/level1/level2/level3/level4")
        mockOps.addMockFile("/source/level1/level2/level3/level4/deep.txt", "deep content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/level1")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - verify full path preserved
        mockOps.hasFile("/dest/level1/level2/level3/level4/deep.txt") shouldBe true
        mockOps.getFileContent("/dest/level1/level2/level3/level4/deep.txt") shouldBe "deep content".toByteArray()

        // Verify source completely cleaned up
        mockOps.hasFile("/source/level1") shouldBe false

        result.movedFiles.size shouldBe 5  // 4 directories + 1 file
    }

    @Test
    fun `move multiple files in same directory`() = runTest {
        // Given - multiple files in same folder
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/source/folder/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/source/folder/file3.txt", "content3".toByteArray())
        mockOps.addMockDir("/dest")

        val file1 = LocalPath.build("/source/folder/file1.txt")
        val file2 = LocalPath.build("/source/folder/file2.txt")
        val file3 = LocalPath.build("/source/folder/file3.txt")

        // When
        val result = setOf(file1, file2, file3).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - all files moved to /dest/ (not /dest/folder/)
        mockOps.hasFile("/dest/file1.txt") shouldBe true
        mockOps.hasFile("/dest/file2.txt") shouldBe true
        mockOps.hasFile("/dest/file3.txt") shouldBe true

        // Source files deleted
        mockOps.hasFile("/source/folder/file1.txt") shouldBe false
        mockOps.hasFile("/source/folder/file2.txt") shouldBe false
        mockOps.hasFile("/source/folder/file3.txt") shouldBe false

        result.movedFiles.size shouldBe 3
    }

    @Test
    fun `move root-level file (no subdirectories)`() = runTest {
        // Given - single file at root level
        mockOps.addMockFile("/source/document.pdf", "pdf content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/document.pdf")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then
        mockOps.hasFile("/dest/document.pdf") shouldBe true
        mockOps.getFileContent("/dest/document.pdf") shouldBe "pdf content".toByteArray()
        mockOps.hasFile("/source/document.pdf") shouldBe false
        result.movedFiles.size shouldBe 1
    }

    @Test
    fun `move mixed structure with files and directories at multiple levels`() = runTest {
        // Given - complex mixed structure
        mockOps.addMockDir("/source/project")
        mockOps.addMockFile("/source/project/README.md", "readme".toByteArray())
        mockOps.addMockDir("/source/project/src")
        mockOps.addMockFile("/source/project/src/main.kt", "main".toByteArray())
        mockOps.addMockDir("/source/project/src/utils")
        mockOps.addMockFile("/source/project/src/utils/helper.kt", "helper".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/project")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - verify entire structure moved
        mockOps.hasFile("/dest/project/README.md") shouldBe true
        mockOps.hasFile("/dest/project/src/main.kt") shouldBe true
        mockOps.hasFile("/dest/project/src/utils/helper.kt") shouldBe true

        // Verify source completely cleaned up
        mockOps.hasFile("/source/project") shouldBe false

        result.movedFiles.size shouldBe 6  // 3 dirs + 3 files
    }

    // ============ MOVE-SPECIFIC TESTS (SOURCE CLEANUP) ============

    @Test
    fun `source directories deleted in post-order after children moved`() = runTest {
        // Given - nested structure to track deletion order
        mockOps.addMockDir("/source/parent")
        mockOps.addMockDir("/source/parent/child")
        mockOps.addMockFile("/source/parent/child/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        val deletionOrder = mutableListOf<String>()
        val spyOps = object : MockFileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>(
            lookupFactory = { path, type, size, modifiedAt, permissions, ownership ->
                LocalPathLookup(
                    lookedUp = path,
                    fileType = type,
                    size = size,
                    modifiedAt = modifiedAt ?: kotlin.time.Instant.fromEpochMilliseconds(0),
                    target = null
                )
            }
        ) {
            init {
                // Copy initial state from mockOps
                addMockDir("/source/parent")
                addMockDir("/source/parent/child")
                addMockFile("/source/parent/child/file.txt", "content".toByteArray())
                addMockDir("/dest")
            }

            override suspend fun delete(path: LocalPath, recursive: Boolean): Boolean {
                deletionOrder.add(path.path)
                return super.delete(path, recursive)
            }
        }

        val sourcePath = LocalPath.build("/source/parent")
        val destPath = LocalPath.build("/dest")

        // When
        setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            strategy = strategy,
            onIssue = null
        ).last()

        // Then - verify deletion order: file, then child dir, then parent dir
        deletionOrder shouldBe listOf(
            "/source/parent/child/file.txt",
            "/source/parent/child",
            "/source/parent"
        )
    }

    @Test
    fun `empty directories are cleaned up after move`() = runTest {
        // Given - directory with only subdirectories (no files)
        mockOps.addMockDir("/source/empty-parent")
        mockOps.addMockDir("/source/empty-parent/empty-child1")
        mockOps.addMockDir("/source/empty-parent/empty-child2")
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/empty-parent")
        val destPath = LocalPath.build("/dest")

        // When
        setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last()

        // Then - all empty directories should be cleaned up
        mockOps.hasFile("/source/empty-parent/empty-child1") shouldBe false
        mockOps.hasFile("/source/empty-parent/empty-child2") shouldBe false
        mockOps.hasFile("/source/empty-parent") shouldBe false

        // And recreated at destination
        mockOps.hasFile("/dest/empty-parent/empty-child1") shouldBe true
        mockOps.hasFile("/dest/empty-parent/empty-child2") shouldBe true
    }

    // ============ RESULT VERIFICATION ============

    @Test
    fun `result contains correct moved pairs`() = runTest {
        // Given
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourceFolder = LocalPath.build("/source/folder")
        val sourceFile = LocalPath.build("/source/folder/file.txt")

        // When
        val result = setOf(sourceFolder).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - verify moved pairs in result
        result.movedFiles shouldContainPath (sourceFolder to LocalPath.build("/dest/folder"))
        result.movedFiles shouldContainPath (sourceFile to LocalPath.build("/dest/folder/file.txt"))
    }

    @Test
    fun `progress callback receives updates during move`() = runTest {
        // Given
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/source/folder/file2.txt", "content2".toByteArray())
        mockOps.addMockDir("/dest")

        val progressUpdates = mutableListOf<MoveAction.State.Progress<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>>()

        // When
        setOf(LocalPath.build("/source/folder")).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).onEach { state ->
            if (state is MoveAction.State.Progress) progressUpdates.add(state)
        }.last()

        // Then - should receive progress updates for files
        progressUpdates.size shouldBe 3  // folder + 2 files
    }

    @Test
    fun `result tracks bytes moved`() = runTest {
        // Given
        val content1 = "content1".toByteArray()
        val content2 = "content2".toByteArray()
        mockOps.addMockFile("/source/file1.txt", content1)
        mockOps.addMockFile("/source/file2.txt", content2)
        mockOps.addMockDir("/dest")

        val expectedBytes = (content1.size + content2.size).toLong()

        // When
        val result = setOf(
            LocalPath.build("/source/file1.txt"),
            LocalPath.build("/source/file2.txt")
        ).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then
        result.bytesMoved shouldBe expectedBytes
    }

    // ============ CONFLICT RESOLUTION - APPLY TO ALL ============

    @Test
    fun `move file with apply to all - skip`() = runTest {
        // Given - multiple files with conflicts
        mockOps.addMockFile("/source/file1.txt", "new1".toByteArray())
        mockOps.addMockFile("/source/file2.txt", "new2".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file1.txt", "old1".toByteArray())
        mockOps.addMockFile("/dest/file2.txt", "old2".toByteArray())

        var issueCount = 0

        // When
        val result = setOf(
            LocalPath.build("/source/file1.txt"),
            LocalPath.build("/source/file2.txt")
        ).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1
        result.skippedFiles.size shouldBe 2

        // Destination files unchanged
        mockOps.getFileContent("/dest/file1.txt") shouldBe "old1".toByteArray()
        mockOps.getFileContent("/dest/file2.txt") shouldBe "old2".toByteArray()

        // Source files still exist
        mockOps.hasFile("/source/file1.txt") shouldBe true
        mockOps.hasFile("/source/file2.txt") shouldBe true
    }

    @Test
    fun `move file with apply to all - overwrite`() = runTest {
        // Given - multiple files with conflicts
        mockOps.addMockFile("/source/file1.txt", "new1".toByteArray())
        mockOps.addMockFile("/source/file2.txt", "new2".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file1.txt", "old1".toByteArray())
        mockOps.addMockFile("/dest/file2.txt", "old2".toByteArray())

        var issueCount = 0

        // When
        val result = setOf(
            LocalPath.build("/source/file1.txt"),
            LocalPath.build("/source/file2.txt")
        ).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1
        result.movedFiles.size shouldBe 2

        // Destination files overwritten
        mockOps.getFileContent("/dest/file1.txt") shouldBe "new1".toByteArray()
        mockOps.getFileContent("/dest/file2.txt") shouldBe "new2".toByteArray()

        // Source files deleted
        mockOps.hasFile("/source/file1.txt") shouldBe false
        mockOps.hasFile("/source/file2.txt") shouldBe false
    }

    @Test
    fun `move file with apply to all - rename source`() = runTest {
        // Given - multiple files with conflicts
        mockOps.addMockFile("/source/file.txt", "new1".toByteArray())
        mockOps.addMockFile("/source/document.txt", "new2".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file.txt", "old1".toByteArray())
        mockOps.addMockFile("/dest/document.txt", "old2".toByteArray())

        var issueCount = 0

        // When
        val result = setOf(
            LocalPath.build("/source/file.txt"),
            LocalPath.build("/source/document.txt")
        ).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.RenameSource(
                    newName = when (issue) {
                        is PathActionIssue.PathAlreadyExists -> issue.suggestedName ?: error("No suggested name")
                        else -> error("Unexpected issue")
                    },
                    applyToAll = true
                )
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1
        result.movedFiles.size shouldBe 2

        // Original files unchanged
        mockOps.getFileContent("/dest/file.txt") shouldBe "old1".toByteArray()
        mockOps.getFileContent("/dest/document.txt") shouldBe "old2".toByteArray()

        // New files with renamed names
        mockOps.hasFile("/dest/file (1).txt") shouldBe true
        mockOps.getFileContent("/dest/file (1).txt") shouldBe "new1".toByteArray()
        mockOps.hasFile("/dest/document (1).txt") shouldBe true
        mockOps.getFileContent("/dest/document (1).txt") shouldBe "new2".toByteArray()

        // Source files deleted
        mockOps.hasFile("/source/file.txt") shouldBe false
        mockOps.hasFile("/source/document.txt") shouldBe false
    }

    @Test
    fun `move directory with apply to all - merge`() = runTest {
        // Given - multiple directories with conflicts
        mockOps.addMockDir("/source/dir1")
        mockOps.addMockFile("/source/dir1/new1.txt", "content1".toByteArray())
        mockOps.addMockDir("/source/dir2")
        mockOps.addMockFile("/source/dir2/new2.txt", "content2".toByteArray())

        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/dir1")
        mockOps.addMockFile("/dest/dir1/old1.txt", "old content1".toByteArray())
        mockOps.addMockDir("/dest/dir2")
        mockOps.addMockFile("/dest/dir2/old2.txt", "old content2".toByteArray())

        var issueCount = 0

        // When
        setOf(
            LocalPath.build("/source/dir1"),
            LocalPath.build("/source/dir2")
        ).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Merge(applyToAll = true)
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1

        // All files exist in merged directories
        mockOps.hasFile("/dest/dir1/new1.txt") shouldBe true
        mockOps.hasFile("/dest/dir1/old1.txt") shouldBe true
        mockOps.hasFile("/dest/dir2/new2.txt") shouldBe true
        mockOps.hasFile("/dest/dir2/old2.txt") shouldBe true

        // Source directories deleted
        mockOps.hasFile("/source/dir1") shouldBe false
        mockOps.hasFile("/source/dir2") shouldBe false
    }

    @Test
    fun `move directory with apply to all - overwrite`() = runTest {
        // Given - multiple directories with conflicts
        mockOps.addMockDir("/source/dir1")
        mockOps.addMockFile("/source/dir1/new1.txt", "content1".toByteArray())
        mockOps.addMockDir("/source/dir2")
        mockOps.addMockFile("/source/dir2/new2.txt", "content2".toByteArray())

        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/dir1")
        mockOps.addMockFile("/dest/dir1/old1.txt", "old content1".toByteArray())
        mockOps.addMockDir("/dest/dir2")
        mockOps.addMockFile("/dest/dir2/old2.txt", "old content2".toByteArray())

        var issueCount = 0

        // When
        setOf(
            LocalPath.build("/source/dir1"),
            LocalPath.build("/source/dir2")
        ).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1

        // Old files deleted, new files exist
        mockOps.hasFile("/dest/dir1/new1.txt") shouldBe true
        mockOps.hasFile("/dest/dir1/old1.txt") shouldBe false
        mockOps.hasFile("/dest/dir2/new2.txt") shouldBe true
        mockOps.hasFile("/dest/dir2/old2.txt") shouldBe false

        // Source directories deleted
        mockOps.hasFile("/source/dir1") shouldBe false
        mockOps.hasFile("/source/dir2") shouldBe false
    }

    @Test
    fun `move directory with apply to all - skip`() = runTest {
        // Given - multiple directories with conflicts
        mockOps.addMockDir("/source/dir1")
        mockOps.addMockFile("/source/dir1/new1.txt", "content1".toByteArray())
        mockOps.addMockDir("/source/dir2")
        mockOps.addMockFile("/source/dir2/new2.txt", "content2".toByteArray())

        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/dir1")
        mockOps.addMockFile("/dest/dir1/old1.txt", "old content1".toByteArray())
        mockOps.addMockDir("/dest/dir2")
        mockOps.addMockFile("/dest/dir2/old2.txt", "old content2".toByteArray())

        var issueCount = 0

        // When
        val result = setOf(
            LocalPath.build("/source/dir1"),
            LocalPath.build("/source/dir2")
        ).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1

        // Skipped items include directories and their children
        result.skippedFiles.size shouldBe 4 // 2 directories + 2 files

        // Original files unchanged
        mockOps.hasFile("/dest/dir1/old1.txt") shouldBe true
        mockOps.hasFile("/dest/dir1/new1.txt") shouldBe false
        mockOps.hasFile("/dest/dir2/old2.txt") shouldBe true
        mockOps.hasFile("/dest/dir2/new2.txt") shouldBe false

        // Source directories still exist
        mockOps.hasFile("/source/dir1") shouldBe true
        mockOps.hasFile("/source/dir2") shouldBe true
    }

    @Test
    fun `move directory over existing FILE with apply to all overwrite uses recursive false`() = runTest {
        // Tests bug fix: overwrite should use recursive=false when destination is a file
        // Given - directory at source, FILE at destination (not directory)
        mockOps.addMockDir("/source/item")
        mockOps.addMockFile("/source/item/content.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/item", "file content".toByteArray())  // FILE, not directory

        val sourcePath = LocalPath.build("/source/item")
        val destPath = LocalPath.build("/dest")

        // When - move with Overwrite (apply to all)
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - file deleted and replaced with directory
        mockOps.hasFile("/dest/item") shouldBe true
        mockOps.getFileType("/dest/item") shouldBe FileType.DIRECTORY
        mockOps.hasFile("/dest/item/content.txt") shouldBe true
        mockOps.getFileContent("/dest/item/content.txt") shouldBe "content".toByteArray()

        // Source cleaned up
        mockOps.hasFile("/source/item") shouldBe false

        result.movedFiles.size shouldBe 2 // directory + file
    }

    @Test
    fun `move directory to existing directory with no issue handler auto-merges`() = runTest {
        // Tests bug fix: auto-merge should be last fallback, after overwrite checks
        // Given - both source and dest have same directory with different files
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/new.txt", "new".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/folder")
        mockOps.addMockFile("/dest/folder/old.txt", "old".toByteArray())

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/dest")

        // When - move with NO issue handler (backward compatibility)
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null  // No handler - should auto-merge
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - directories merged (both files exist)
        mockOps.hasFile("/dest/folder") shouldBe true
        mockOps.hasFile("/dest/folder/old.txt") shouldBe true
        mockOps.hasFile("/dest/folder/new.txt") shouldBe true
        mockOps.getFileContent("/dest/folder/old.txt") shouldBe "old".toByteArray()
        mockOps.getFileContent("/dest/folder/new.txt") shouldBe "new".toByteArray()

        // Source cleaned up
        mockOps.hasFile("/source/folder") shouldBe false

        result.movedFiles.size shouldBe 2 // folder + new.txt
        // Bug fix verification: merged directory should be in result
        result.movedFiles shouldContainPath (LocalPath.build("/source/folder") to LocalPath.build("/dest/folder"))
    }

    @Test
    fun `merge resolution adds directory to moved set`() = runTest {
        // Tests bug fix: merged directories should appear in result set
        // Given - source and destination directories with different files
        mockOps.addMockDir("/source/project")
        mockOps.addMockFile("/source/project/new.txt", "new".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/project")
        mockOps.addMockFile("/dest/project/old.txt", "old".toByteArray())

        val sourcePath = LocalPath.build("/source/project")
        val destPath = LocalPath.build("/dest")

        // When - move with Merge resolution
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Merge()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - both files exist in merged directory
        mockOps.hasFile("/dest/project") shouldBe true
        mockOps.hasFile("/dest/project/old.txt") shouldBe true
        mockOps.hasFile("/dest/project/new.txt") shouldBe true

        // Source cleaned up
        mockOps.hasFile("/source/project") shouldBe false

        result.movedFiles.size shouldBe 2 // directory + new.txt
        // Bug fix verification: merged directory explicitly in result
        result.movedFiles shouldContainPath (LocalPath.build("/source/project") to LocalPath.build("/dest/project"))
    }

    @Test
    fun `nested directory rename source updates all child paths`() = runTest {
        // Given - nested structure with conflict
        mockOps.addMockDir("/source/Parent")
        mockOps.addMockDir("/source/Parent/SubDir")
        mockOps.addMockFile("/source/Parent/SubDir/file.txt", "content".toByteArray())

        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/Parent")
        mockOps.addMockFile("/dest/Parent/existing.txt", "existing".toByteArray())

        // When - rename source to Parent-new
        val result = setOf(LocalPath.build("/source/Parent")).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Parent-new")
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - all paths updated to use Parent-new
        mockOps.hasFile("/dest/Parent-new") shouldBe true
        mockOps.hasFile("/dest/Parent-new/SubDir") shouldBe true
        mockOps.hasFile("/dest/Parent-new/SubDir/file.txt") shouldBe true
        mockOps.getFileContent("/dest/Parent-new/SubDir/file.txt") shouldBe "content".toByteArray()

        // Original unchanged
        mockOps.hasFile("/dest/Parent/existing.txt") shouldBe true

        // Source deleted
        mockOps.hasFile("/source/Parent") shouldBe false

        result.movedFiles.size shouldBe 3 // Parent-new + SubDir + file.txt
    }

    // ============ RETRY TESTS ============

    @Test
    fun `move file with transient error retries and succeeds`() = runTest {
        // Given - file with injected failure on first write attempt
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Fail once on openOutputStream, then succeed
        mockOps.setFailOpenOutputStream(1)

        // When - move with retry on UnknownError
        val result = setOf(LocalPath.build("/source/file.txt")).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed move left partial file - overwrite
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - file successfully moved after retry
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileContent("/dest/file.txt") shouldBe "content".toByteArray()
        mockOps.hasFile("/source/file.txt") shouldBe false

        result.movedFiles.size shouldBe 1
    }

    @Test
    fun `move file with persistent error retries multiple times then skips`() = runTest {
        // Given - file with persistent failure (fails 4 times)
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Fail 4 times on openOutputStream
        mockOps.setFailOpenOutputStream(4)

        var retryCount = 0

        // When - retry 3 times, then skip
        val result = setOf(LocalPath.build("/source/file.txt")).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        retryCount++
                        if (retryCount < 4) {
                            PathActionIssue.UnknownError.Resolution.Retry
                        } else {
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    }
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed move may leave partial file - overwrite it on retry
                        retryCount++
                        if (retryCount < 4) {
                            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                        } else {
                            PathActionIssue.PathAlreadyExists.Resolution.Skip()
                        }
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - file skipped after retries
        // Note: partial file may or may not exist depending on when failures occurred
        mockOps.hasFile("/source/file.txt") shouldBe true  // Source still exists

        retryCount shouldBe 4
        result.skippedFiles.size shouldBe 1
        result.movedFiles.size shouldBe 0
    }

    @Test
    fun `move file retry does not regress progress tracking`() = runTest {
        // Given - file with injected failure
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        mockOps.setFailOpenOutputStream(1)

        val progressUpdates = mutableListOf<MoveAction.State.Progress<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>>()

        // When - move with progress tracking
        setOf(LocalPath.build("/source/file.txt")).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed move may leave partial file - overwrite it on retry
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).onEach { state ->
            if (state is MoveAction.State.Progress) progressUpdates.add(state)
        }.last()

        // Then - progress should never decrease (no regression)
        var previousProgress = 0L
        for (progress in progressUpdates) {
            progress.movedBytes should { it >= previousProgress }
            previousProgress = progress.movedBytes
        }

        // File successfully moved
        mockOps.hasFile("/dest/file.txt") shouldBe true
    }

    // ============ SCAN ERROR HANDLING ============

    @Test
    fun `directory scan error during move then skip should appear only in skipped`() = runTest {
        // Given - directory with children that will fail during listFiles
        mockOps.addMockDir("/source/parent")
        mockOps.addMockFile("/source/parent/child.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Inject listFiles failure (simulates permission denied during scan)
        mockOps.setFailListFiles(1, { SecurityException("Permission denied") })

        val sourcePath = LocalPath.build("/source/parent")
        val destPath = LocalPath.build("/dest")

        var issueReceived = false

        // When
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueReceived = true
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> TODO("Unexpected issue type: $issue")
                }
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - directory should be ONLY in skipped, NOT in moved
        result.movedFiles.toPathPairs().map { it.first } shouldNotBe setOf(LocalPath.build("/source/parent"))
        result.skippedFiles shouldBePaths setOf(LocalPath.build("/source/parent"))
        issueReceived shouldBe true

        // Destination should not have the directory or its children
        mockOps.hasFile("/dest/parent") shouldBe false
        mockOps.hasFile("/dest/parent/child.txt") shouldBe false

        // Source should still exist (move was skipped)
        mockOps.hasFile("/source/parent") shouldBe true
        mockOps.hasFile("/source/parent/child.txt") shouldBe true
    }

    @Test
    fun `directory scan error during move with retry should succeed on second attempt`() = runTest {
        // Given - directory with children
        mockOps.addMockDir("/source/parent")
        mockOps.addMockFile("/source/parent/child.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Inject listFiles failure for first attempt only
        mockOps.setFailListFiles(1, { SecurityException("Permission denied") })

        val sourcePath = LocalPath.build("/source/parent")
        val destPath = LocalPath.build("/dest")

        var retryInvoked = false

        // When
        val result = setOf(sourcePath).moveGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        if (!retryInvoked) {
                            retryInvoked = true
                            PathActionIssue.UnknownError.Resolution.Retry
                        } else {
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    }
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    else -> TODO("Unexpected issue type: $issue")
                }
            }
        ).last() as MoveAction.State.Result<LocalPath, LocalPathLookup,LocalPath, LocalPathLookup>

        // Then - directory and children successfully moved after retry
        retryInvoked shouldBe true
        mockOps.hasFile("/dest/parent") shouldBe true
        mockOps.hasFile("/dest/parent/child.txt") shouldBe true
        mockOps.getFileContent("/dest/parent/child.txt") shouldBe "content".toByteArray()

        // Source should be deleted (successful move)
        mockOps.hasFile("/source/parent") shouldBe false
        mockOps.hasFile("/source/parent/child.txt") shouldBe false

        result.movedFiles.size shouldBe 2 // parent + child.txt
        result.skippedFiles.size shouldBe 0
    }
}
