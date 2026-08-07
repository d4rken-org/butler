package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.firstPath
import testhelpers.shouldContainPath
import kotlin.time.Instant

/**
 * Tests for GenericPathMove orchestrator using MockFileSystemOps.
 *
 * This validates that the move operation's non-interactive path correctly:
 * 1. Calculates destination paths without duplication
 * 2. Preserves directory structure including top-level source name
 * 3. Cleans up source directories after moving children
 * 4. Handles edge cases like root-level files, deep nesting, etc.
 *
 * Issue resolution, retry and cancellation are covered by GenericPathMoveIssueTest.
 *
 * These tests mirror GenericPathCopyTest but verify move-specific behavior
 * like source cleanup and post-order directory deletion.
 */
class GenericPathMoveTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup>
    private lateinit var strategy: GenericCrossTypeMoveStrategy<
        LocalPath, LocalPathLookup,
        LocalPath, LocalPathLookup
        >

    @BeforeEach
    fun setup() {
        mockOps = MockFileSystemOps { path, type, size, modifiedAt, permissions, ownership, createdAt ->
            LocalPathLookup(
                lookedUp = path,
                fileType = type,
                size = size,
                modifiedAt = modifiedAt ?: kotlin.time.Instant.fromEpochMilliseconds(0),
                target = null,
                ownership = ownership,
                permissions = permissions,
                createdAt = createdAt,
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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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
        val spyOps = object : MockFileSystemOps<LocalPath, LocalPathLookup>(
            lookupFactory = { path, type, size, modifiedAt, permissions, ownership, createdAt ->
                LocalPathLookup(
                    lookedUp = path,
                    fileType = type,
                    size = size,
                    modifiedAt = modifiedAt ?: kotlin.time.Instant.fromEpochMilliseconds(0),
                    target = null,
                    ownership = ownership,
                    permissions = permissions,
                    createdAt = createdAt,
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
            options = TransferStrategy.Options(attemptAtomicMove = false),
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
            options = TransferStrategy.Options(attemptAtomicMove = false),
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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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

        val progressUpdates =
            mutableListOf<MoveAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // When
        setOf(LocalPath.build("/source/folder")).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).onEach { state ->
            if (state is MoveAction.State.Active) progressUpdates.add(state)
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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.bytesMoved shouldBe expectedBytes
    }

    // ============ CONFLICT RESOLUTION - APPLY TO ALL ============

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
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null  // No handler - should auto-merge
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

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

    // ============ PER-FILE PROGRESS TRACKING ============

    @Test
    fun `move multiple files reports correct per-file progress without accumulation`() = runTest {
        // Given - multiple files with different sizes (1MB, 5MB, 2MB)
        mockOps.addMockDir("/source")
        mockOps.addMockFile("/source/file1.bin", ByteArray(1_000_000))  // 1 MB
        mockOps.addMockFile("/source/file2.bin", ByteArray(5_000_000))  // 5 MB
        mockOps.addMockFile("/source/file3.bin", ByteArray(2_000_000))  // 2 MB
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source")
        val destPath = LocalPath.build("/dest")

        val progressUpdates =
            mutableListOf<MoveAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // When - move files and collect progress updates
        val result = setOf(sourcePath).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).onEach { state ->
            if (state is MoveAction.State.Active) {
                progressUpdates.add(state)
            }
        }.last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - operation succeeded
        result.movedFiles.size shouldBe 4  // source dir + 3 files

        // Filter progress updates that have secondary progress (file-level tracking)
        val fileProgressUpdates = progressUpdates.filter { it.secondaryProgress != null }

        fileProgressUpdates.size shouldNotBe 0 // Should have file progress updates

        // Critical assertion: currentFileBytes should NEVER exceed currentFileSize
        fileProgressUpdates.forEach { progress ->
            val currentBytes = progress.currentFileBytes
            val fileSize = progress.currentFileSize

            // This will fail with the bug: shows accumulated bytes like 6MB/5MB or 8MB/2MB
            if (currentBytes > fileSize) {
                throw AssertionError(
                    "Bug detected: currentFileBytes ($currentBytes) > currentFileSize ($fileSize) " +
                        "for file ${progress.currentSource.lookedUp.name}"
                )
            }
        }

        // Each file should start with currentFileBytes = 0 (or at least reset between files)
        // Group by file being moved
        val progressByFile = fileProgressUpdates.groupBy { it.currentSource.lookedUp.path }

        progressByFile.values.forEach { progressList ->
            if (progressList.isNotEmpty()) {
                val firstUpdate = progressList.first()
                // First progress for each file should have currentFileBytes near 0
                // (allowing small initial value due to chunked reads)
                val firstBytes = firstUpdate.currentFileBytes
                val fileSize = firstUpdate.currentFileSize

                // Should be starting fresh, not continuing from previous file
                if (firstBytes > fileSize * 0.5) {
                    throw AssertionError(
                        "Bug detected: First progress update for file ${firstUpdate.currentSource.lookedUp.name} " +
                            "shows currentFileBytes=$firstBytes which is > 50% of fileSize=$fileSize. " +
                            "This suggests bytes are accumulating from previous file."
                    )
                }
            }
        }
    }

    // ============ PROGRESS COUNTER TESTS ============

    @Test
    fun `move multiple files increments items processed counter correctly`() = runTest {
        // Given - 5 files to move
        mockOps.addMockFile("/source/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/source/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/source/file3.txt", "content3".toByteArray())
        mockOps.addMockFile("/source/file4.txt", "content4".toByteArray())
        mockOps.addMockFile("/source/file5.txt", "content5".toByteArray())
        mockOps.addMockDir("/dest")

        val sources = setOf(
            LocalPath.build("/source/file1.txt"),
            LocalPath.build("/source/file2.txt"),
            LocalPath.build("/source/file3.txt"),
            LocalPath.build("/source/file4.txt"),
            LocalPath.build("/source/file5.txt")
        )

        val progressUpdates =
            mutableListOf<MoveAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // When - move files and collect progress
        sources.moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).onEach { state ->
            if (state is MoveAction.State.Active) {
                progressUpdates.add(state)
            }
        }.last()

        // Then - verify counter increments: 1/5 → 2/5 → 3/5 → 4/5 → 5/5
        // (Progress is only reported after items complete, so starts at 1, not 0)
        val counters = progressUpdates
            .mapNotNull { it.primaryProgress.count as? eu.darken.butler.common.progress.Progress.Count.Counter }
            .filter { it.max == 5L }

        // Should see progression from 1 to 5 (all items processed)
        counters.size shouldNotBe 0
        val progressionSeen = counters.map { it.current }.distinct().sorted()
        progressionSeen shouldBe listOf(1L, 2L, 3L, 4L, 5L)

        // Final counter should be 5/5
        counters.last().current shouldBe 5L
        counters.last().max shouldBe 5L
    }

    @Test
    fun `move directory with files increments counter for both dirs and files`() = runTest {
        // Given - 1 directory + 2 files = 3 items total
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/source/folder/file2.txt", "content2".toByteArray())
        mockOps.addMockDir("/dest")

        val progressUpdates =
            mutableListOf<MoveAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // When
        setOf(LocalPath.build("/source/folder")).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = null
        ).onEach { state ->
            if (state is MoveAction.State.Active) progressUpdates.add(state)
        }.last()

        // Then - verify counter increments for all 3 items
        val counters = progressUpdates
            .mapNotNull { it.primaryProgress.count as? eu.darken.butler.common.progress.Progress.Count.Counter }
            .filter { it.max == 3L }

        counters.size shouldNotBe 0
        counters.last().current shouldBe 3L
        counters.last().max shouldBe 3L
    }

    // ============ CROSS-DEVICE ATOMIC MOVE FALLBACK (BUG FIX TEST) ============

    @Test
    fun `nested directory with cross-device atomic move fallback succeeds`() = runTest {
        // This test verifies the bug fix: when atomic move fails with cross-device error
        // for a top-level directory, nested subdirectories should still move correctly
        // via the recursive fallback pattern.
        //
        // The bug: after top-level atomic move fails, children are added to FRONT of queue
        // and parent's CreateDirectory is added to BACK. When a nested directory is processed,
        // it tries atomic move which fails with NoSuchFileException because parent doesn't exist.

        // Given - nested directory structure, cross-device mock
        val crossDeviceOps =
            CrossDeviceMockFileSystemOps<LocalPath, LocalPathLookup> { path, type, size, modifiedAt, permissions, ownership, createdAt ->
                LocalPathLookup(
                    lookedUp = path,
                    fileType = type,
                    size = size,
                    modifiedAt = modifiedAt ?: Instant.fromEpochMilliseconds(0),
                    target = null,
                    ownership = ownership,
                    permissions = permissions,
                    createdAt = createdAt,
                )
            }
        crossDeviceOps.addMockDir("/source/parentdir")
        crossDeviceOps.addMockDir("/source/parentdir/childdir")
        crossDeviceOps.addMockFile("/source/parentdir/childdir/file.txt", "content".toByteArray())
        crossDeviceOps.addMockDir("/dest")

        // When - attempt move with atomicMove enabled (will fail, should use fallback)
        val result = setOf(LocalPath.build("/source/parentdir")).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = true),
            destination = LocalPath.build("/dest/parentdir"),
            sourceOps = crossDeviceOps,
            destOps = crossDeviceOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - should succeed via recursive fallback (not throw exception)
        crossDeviceOps.hasFile("/dest/parentdir") shouldBe true
        crossDeviceOps.hasFile("/dest/parentdir/childdir") shouldBe true
        crossDeviceOps.hasFile("/dest/parentdir/childdir/file.txt") shouldBe true
        crossDeviceOps.getFileContent("/dest/parentdir/childdir/file.txt") shouldBe "content".toByteArray()

        // Source should be deleted
        crossDeviceOps.hasFile("/source/parentdir") shouldBe false
        crossDeviceOps.hasFile("/source/parentdir/childdir") shouldBe false
        crossDeviceOps.hasFile("/source/parentdir/childdir/file.txt") shouldBe false

        // Verify moved items
        result.movedFiles.size shouldBe 3 // parentdir + childdir + file.txt
    }

    @Test
    fun `deeply nested directories with cross-device atomic move fallback succeeds`() = runTest {
        // Test with deeper nesting to ensure the fix works at all levels

        val crossDeviceOps =
            CrossDeviceMockFileSystemOps<LocalPath, LocalPathLookup> { path, type, size, modifiedAt, permissions, ownership, createdAt ->
                LocalPathLookup(
                    lookedUp = path,
                    fileType = type,
                    size = size,
                    modifiedAt = modifiedAt ?: Instant.fromEpochMilliseconds(0),
                    target = null,
                    ownership = ownership,
                    permissions = permissions,
                    createdAt = createdAt,
                )
            }
        // 4 levels deep: /source/a/b/c/d/file.txt
        crossDeviceOps.addMockDir("/source/a")
        crossDeviceOps.addMockDir("/source/a/b")
        crossDeviceOps.addMockDir("/source/a/b/c")
        crossDeviceOps.addMockDir("/source/a/b/c/d")
        crossDeviceOps.addMockFile("/source/a/b/c/d/file.txt", "deep content".toByteArray())
        crossDeviceOps.addMockDir("/dest")

        // When
        val result = setOf(LocalPath.build("/source/a")).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = true),
            destination = LocalPath.build("/dest/a"),
            sourceOps = crossDeviceOps,
            destOps = crossDeviceOps,
            strategy = strategy,
            onIssue = null
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - all levels should be created
        crossDeviceOps.hasFile("/dest/a") shouldBe true
        crossDeviceOps.hasFile("/dest/a/b") shouldBe true
        crossDeviceOps.hasFile("/dest/a/b/c") shouldBe true
        crossDeviceOps.hasFile("/dest/a/b/c/d") shouldBe true
        crossDeviceOps.hasFile("/dest/a/b/c/d/file.txt") shouldBe true
        crossDeviceOps.getFileContent("/dest/a/b/c/d/file.txt") shouldBe "deep content".toByteArray()

        // Source should be deleted
        crossDeviceOps.hasFile("/source/a") shouldBe false

        // 4 directories + 1 file = 5 items
        result.movedFiles.size shouldBe 5
    }
}

/**
 * MockFileSystemOps that simulates cross-device moves.
 *
 * This accurately simulates the real-world bug scenario:
 * 1. If destination's parent directory doesn't exist → throws NoSuchFileException
 *    (This is what happens on real file systems when trying atomic move to non-existent path)
 * 2. If parent exists → returns MoveOutcome.NotSupported (cross-device link)
 *
 * The bug occurred because:
 * - Top-level directory atomic move refuses with NotSupported (falls back)
 * - Nested directory atomic move failed with NoSuchFileException (NOT caught, propagates up)
 */
private class CrossDeviceMockFileSystemOps<P : APath<P>, PL : APathLookup<P>>(
    lookupFactory: (path: P, type: FileType, size: Long?, modifiedAt: Instant?, permissions: Permissions?, ownership: Ownership?, createdAt: Instant?) -> PL
) : MockFileSystemOps<P, PL>(lookupFactory) {

    override suspend fun move(source: P, destination: P): MoveOutcome {
        // Check if destination's parent directory exists
        val destParentPath = destination.path.substringBeforeLast('/', "")
        val parentExists = destParentPath.isEmpty() || files.containsKey(destParentPath)

        if (!parentExists) {
            // Parent doesn't exist - this is what causes the real bug
            // Real file systems throw IOException/NoSuchFileException in this case
            throw java.nio.file.NoSuchFileException(
                source.path,
                destination.path,
                "Parent directory does not exist: $destParentPath"
            )
        }

        // Parent exists but cross-device - atomic move is provably not possible
        return MoveOutcome.NotSupported("Cross-device link")
    }
}
