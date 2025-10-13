package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
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
            onProgress = null,
            onIssue = null
        )

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
            onProgress = null,
            onIssue = null
        )

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
            onProgress = null,
            onIssue = null
        )

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
            onProgress = null,
            onIssue = null
        )

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
            onProgress = null,
            onIssue = null
        )

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

            override suspend fun delete(path: LocalPath): Boolean {
                deletionOrder.add(path.path)
                return super.delete(path)
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
            onProgress = null,
            onIssue = null
        )

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
            onProgress = null,
            onIssue = null
        )

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
            onProgress = null,
            onIssue = null
        )

        // Then - verify moved pairs in result
        result.movedFiles shouldContain (sourceFolder to LocalPath.build("/dest/folder"))
        result.movedFiles shouldContain (sourceFile to LocalPath.build("/dest/folder/file.txt"))
    }

    @Test
    fun `progress callback receives updates during move`() = runTest {
        // Given
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/source/folder/file2.txt", "content2".toByteArray())
        mockOps.addMockDir("/dest")

        val progressUpdates = mutableListOf<LocalPath>()

        // When
        setOf(LocalPath.build("/source/folder")).moveGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = { state ->
                progressUpdates.add(state.currentSource)
            },
            onIssue = null
        )

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
            onProgress = null,
            onIssue = null
        )

        // Then
        result.bytesMoved shouldBe expectedBytes
    }
}
