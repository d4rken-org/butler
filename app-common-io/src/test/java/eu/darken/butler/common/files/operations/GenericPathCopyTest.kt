package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for GenericPathCopy - the high-level copy orchestrator.
 *
 * Tests the complete copy operation including:
 * - Scanning source trees
 * - Calculating destination paths (relative path handling)
 * - Orchestrating file/directory creation
 * - Progress reporting
 *
 * Uses MockFileSystemOps to test without real file system access.
 */
class GenericPathCopyTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>
    private lateinit var strategy: GenericCrossTypeCopyStrategy<
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
        strategy = GenericCrossTypeCopyStrategy()
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ DESTINATION PATH CALCULATION ============

    @Test
    fun `copy nested directory preserves structure without duplication`() = runTest {
        // Given - nested directory structure
        mockOps.addMockDir("/source/topfolder")
        mockOps.addMockDir("/source/topfolder/subfolder")
        mockOps.addMockFile("/source/topfolder/subfolder/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/topfolder")
        val destPath = LocalPath.build("/dest")

        // When - copy the top folder
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - structure should be preserved WITHOUT duplication
        // Expected: /dest/topfolder/subfolder/file.txt
        // Bug would create: /dest/topfolder/topfolder/subfolder/file.txt
        println("Mock file system state after copy:")
        println(mockOps.dump())

        mockOps.hasFile("/dest/topfolder") shouldBe true
        mockOps.hasFile("/dest/topfolder/subfolder") shouldBe true
        mockOps.hasFile("/dest/topfolder/subfolder/file.txt") shouldBe true
        mockOps.getFileType("/dest/topfolder") shouldBe FileType.DIRECTORY
        mockOps.getFileType("/dest/topfolder/subfolder") shouldBe FileType.DIRECTORY
        mockOps.getFileType("/dest/topfolder/subfolder/file.txt") shouldBe FileType.FILE

        // Bug check: These should NOT exist (duplicated path)
        mockOps.hasFile("/dest/topfolder/topfolder") shouldBe false

        result.copied.size shouldBe 3 // topfolder + subfolder + file.txt
    }

    @Test
    fun `copy deeply nested structure preserves all levels`() = runTest {
        // Given - deeply nested structure
        mockOps.addMockDir("/source/level1")
        mockOps.addMockDir("/source/level1/level2")
        mockOps.addMockDir("/source/level1/level2/level3")
        mockOps.addMockFile("/source/level1/level2/level3/deep.txt", "deep content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/level1")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - all levels preserved correctly
        mockOps.hasFile("/dest/level1") shouldBe true
        mockOps.hasFile("/dest/level1/level2") shouldBe true
        mockOps.hasFile("/dest/level1/level2/level3") shouldBe true
        mockOps.hasFile("/dest/level1/level2/level3/deep.txt") shouldBe true

        // Bug check: No duplication
        mockOps.hasFile("/dest/level1/level1") shouldBe false

        result.copied.size shouldBe 4 // level1 + level2 + level3 + deep.txt
    }

    @Test
    fun `copy multiple files in same directory`() = runTest {
        // Given - directory with multiple files
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/source/folder/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/source/folder/file3.txt", "content3".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - all files in correct location
        mockOps.hasFile("/dest/folder") shouldBe true
        mockOps.hasFile("/dest/folder/file1.txt") shouldBe true
        mockOps.hasFile("/dest/folder/file2.txt") shouldBe true
        mockOps.hasFile("/dest/folder/file3.txt") shouldBe true

        mockOps.getFileContent("/dest/folder/file1.txt") shouldBe "content1".toByteArray()
        mockOps.getFileContent("/dest/folder/file2.txt") shouldBe "content2".toByteArray()
        mockOps.getFileContent("/dest/folder/file3.txt") shouldBe "content3".toByteArray()

        result.copied.size shouldBe 4 // folder + 3 files
    }

    @Test
    fun `copy root-level file (no subdirectories)`() = runTest {
        // Given - single file at source root
        mockOps.addMockFile("/source/rootfile.txt", "root content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/rootfile.txt")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - file copied to dest root
        mockOps.hasFile("/dest/rootfile.txt") shouldBe true
        mockOps.getFileContent("/dest/rootfile.txt") shouldBe "root content".toByteArray()

        result.copied.size shouldBe 1
    }

    @Test
    fun `copy mixed structure with files and directories at multiple levels`() = runTest {
        // Given - complex structure
        mockOps.addMockDir("/source/root")
        mockOps.addMockFile("/source/root/root.txt", "root level".toByteArray())
        mockOps.addMockDir("/source/root/subdir")
        mockOps.addMockFile("/source/root/subdir/sub.txt", "sub level".toByteArray())
        mockOps.addMockDir("/source/root/subdir/deepdir")
        mockOps.addMockFile("/source/root/subdir/deepdir/deep.txt", "deep level".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/root")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - entire structure preserved
        mockOps.hasFile("/dest/root") shouldBe true
        mockOps.hasFile("/dest/root/root.txt") shouldBe true
        mockOps.hasFile("/dest/root/subdir") shouldBe true
        mockOps.hasFile("/dest/root/subdir/sub.txt") shouldBe true
        mockOps.hasFile("/dest/root/subdir/deepdir") shouldBe true
        mockOps.hasFile("/dest/root/subdir/deepdir/deep.txt") shouldBe true

        mockOps.getFileContent("/dest/root/root.txt") shouldBe "root level".toByteArray()
        mockOps.getFileContent("/dest/root/subdir/sub.txt") shouldBe "sub level".toByteArray()
        mockOps.getFileContent("/dest/root/subdir/deepdir/deep.txt") shouldBe "deep level".toByteArray()

        result.copied.size shouldBe 6 // 3 dirs + 3 files
    }

    // ============ UNIX CP/MV SEMANTICS ============

    @Test
    fun `single source file to non-existent path uses destination as final path (rename)`() = runTest {
        // Given - single source file, non-existent destination
        mockOps.addMockFile("/source/original.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/source/original.txt")
        val destPath = LocalPath.build("/dest/renamed.txt")  // Non-existent final path

        // When - copy single source to non-existent path
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - destination IS the final path (rename semantics)
        mockOps.hasFile("/dest/renamed.txt") shouldBe true
        mockOps.getFileType("/dest/renamed.txt") shouldBe FileType.FILE
        mockOps.getFileContent("/dest/renamed.txt") shouldBe "content".toByteArray()

        // Bug check: Should NOT create /dest/renamed.txt/original.txt
        mockOps.hasFile("/dest/renamed.txt/original.txt") shouldBe false

        result.copied.size shouldBe 1
        result.copied.first() shouldBe (
            LocalPath.build("/source/original.txt") to LocalPath.build("/dest/renamed.txt")
            )
    }

    @Test
    fun `single source file to existing directory copies INTO directory`() = runTest {
        // Given - single source file, existing destination directory
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")  // Existing directory

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        // When - copy single source to existing directory
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - file copied INTO the directory (appends source name)
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileType("/dest/file.txt") shouldBe FileType.FILE
        mockOps.getFileContent("/dest/file.txt") shouldBe "content".toByteArray()

        result.copied.size shouldBe 1
        result.copied.first() shouldBe (
            LocalPath.build("/source/file.txt") to LocalPath.build("/dest/file.txt")
            )
    }

    @Test
    fun `single source directory to non-existent path uses destination as final path (rename)`() = runTest {
        // Given - single source directory with content, non-existent destination
        mockOps.addMockDir("/source/origdir")
        mockOps.addMockFile("/source/origdir/file.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/source/origdir")
        val destPath = LocalPath.build("/dest/renameddir")  // Non-existent final path

        // When - copy single source directory to non-existent path
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - destination IS the final path (directory rename)
        mockOps.hasFile("/dest/renameddir") shouldBe true
        mockOps.getFileType("/dest/renameddir") shouldBe FileType.DIRECTORY
        mockOps.hasFile("/dest/renameddir/file.txt") shouldBe true
        mockOps.getFileContent("/dest/renameddir/file.txt") shouldBe "content".toByteArray()

        // Bug check: Should NOT create /dest/renameddir/origdir
        mockOps.hasFile("/dest/renameddir/origdir") shouldBe false

        result.copied.size shouldBe 2 // renameddir + file.txt
    }

    @Test
    fun `multiple sources to directory appends names to destination`() = runTest {
        // Given - multiple source files
        mockOps.addMockFile("/source/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/source/file2.txt", "content2".toByteArray())
        mockOps.addMockDir("/dest")

        val sources = setOf(
            LocalPath.build("/source/file1.txt"),
            LocalPath.build("/source/file2.txt")
        )
        val destPath = LocalPath.build("/dest")

        // When - copy multiple sources
        val result = sources.copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - both files copied INTO destination directory (names appended)
        mockOps.hasFile("/dest/file1.txt") shouldBe true
        mockOps.hasFile("/dest/file2.txt") shouldBe true
        mockOps.getFileContent("/dest/file1.txt") shouldBe "content1".toByteArray()
        mockOps.getFileContent("/dest/file2.txt") shouldBe "content2".toByteArray()

        result.copied.size shouldBe 2
    }

    // ============ PROGRESS REPORTING ============

    @Test
    fun `progress callback receives updates during copy`() = runTest {
        // Given
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/dest")

        val progressUpdates = mutableListOf<CopyAction.State.Progress<LocalPath, LocalPathLookup>>()

        // When
        setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = { progress -> progressUpdates.add(progress) },
            onIssue = null
        )

        // Then - should receive progress updates
        (progressUpdates.size > 0) shouldBe true
    }

    // ============ RESULT VERIFICATION ============

    @Test
    fun `result contains correct copied pairs`() = runTest {
        // Given
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/dest")

        // When
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = null
        )

        // Then - result should contain all copied items
        result.copied.size shouldBe 2 // folder + file

        // Verify source paths in result
        val copiedSources = result.copied.map { it.first }
        copiedSources shouldBe setOf(
            LocalPath.build("/source/folder"),
            LocalPath.build("/source/folder/file.txt")
        )

        // Verify destination paths in result
        val copiedDests = result.copied.map { it.second }
        copiedDests shouldBe setOf(
            LocalPath.build("/dest/folder"),
            LocalPath.build("/dest/folder/file.txt")
        )
    }

    // ============ CONFLICT RESOLUTION ============

    @Test
    fun `copy file with RenameSource creates new file with renamed name`() = runTest {
        // Given - source file and conflicting destination
        mockOps.addMockFile("/source/file.txt", "new content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file.txt", "old content".toByteArray())

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        // When - copy with RenameSource resolution
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("file (1).txt")
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        )

        // Then - old file unchanged, new file created with renamed name
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileContent("/dest/file.txt") shouldBe "old content".toByteArray()
        mockOps.hasFile("/dest/file (1).txt") shouldBe true
        mockOps.getFileContent("/dest/file (1).txt") shouldBe "new content".toByteArray()

        result.copied.size shouldBe 1
        result.copied.first() shouldBe (LocalPath.build("/source/file.txt") to LocalPath.build("/dest/file (1).txt"))
    }

    @Test
    fun `copy directory with RenameSource creates new directory with renamed name`() = runTest {
        // Given - source directory and conflicting destination directory
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/new.txt", "new".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/folder")
        mockOps.addMockFile("/dest/folder/old.txt", "old".toByteArray())

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/dest")

        // When - copy with RenameSource resolution
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("folder (1)")
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        )

        // Then - old directory unchanged, new directory created with renamed name
        mockOps.hasFile("/dest/folder/old.txt") shouldBe true
        mockOps.hasFile("/dest/folder/new.txt") shouldBe false
        mockOps.hasFile("/dest/folder (1)") shouldBe true
        mockOps.hasFile("/dest/folder (1)/new.txt") shouldBe true
        mockOps.getFileContent("/dest/folder (1)/new.txt") shouldBe "new".toByteArray()

        result.copied.size shouldBe 2 // folder + file
    }

    @Test
    fun `copy with Skip leaves existing file unchanged`() = runTest {
        // Given - source file and conflicting destination
        mockOps.addMockFile("/source/file.txt", "new content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file.txt", "old content".toByteArray())

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        // When - copy with Skip resolution
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        )

        // Then - destination file unchanged
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileContent("/dest/file.txt") shouldBe "old content".toByteArray()

        result.copied.size shouldBe 0
        result.skipped.size shouldBe 1
        result.skipped shouldBe setOf(LocalPath.build("/source/file.txt"))
    }

    @Test
    fun `copy with Overwrite replaces existing file`() = runTest {
        // Given - source file and conflicting destination
        mockOps.addMockFile("/source/file.txt", "new content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file.txt", "old content".toByteArray())

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        // When - copy with Overwrite resolution
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        )

        // Then - destination file replaced with new content
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileContent("/dest/file.txt") shouldBe "new content".toByteArray()

        result.copied.size shouldBe 1
        result.copied.first() shouldBe (LocalPath.build("/source/file.txt") to LocalPath.build("/dest/file.txt"))
    }

    @Test
    fun `copy directory with Merge combines both directories`() = runTest {
        // Given - source and destination directories with different files
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/new.txt", "new".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/folder")
        mockOps.addMockFile("/dest/folder/old.txt", "old".toByteArray())

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/dest")

        // When - copy with Merge resolution
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Merge()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        )

        // Then - both files exist in merged directory
        mockOps.hasFile("/dest/folder") shouldBe true
        mockOps.hasFile("/dest/folder/old.txt") shouldBe true
        mockOps.hasFile("/dest/folder/new.txt") shouldBe true
        mockOps.getFileContent("/dest/folder/old.txt") shouldBe "old".toByteArray()
        mockOps.getFileContent("/dest/folder/new.txt") shouldBe "new".toByteArray()

        result.copied.size shouldBe 2 // folder + new.txt file
    }

    @Test
    fun `nested directory RenameSource updates all child paths`() = runTest {
        // Given - nested source structure and conflicting destination
        mockOps.addMockDir("/source/Parent")
        mockOps.addMockDir("/source/Parent/SubDir")
        mockOps.addMockFile("/source/Parent/SubDir/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/Parent")
        mockOps.addMockFile("/dest/Parent/existing.txt", "existing".toByteArray())

        val sourcePath = LocalPath.build("/source/Parent")
        val destPath = LocalPath.build("/dest")

        // When - copy with RenameSource
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onProgress = null,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Parent-new")
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        )

        // Then - all children copied to renamed parent
        mockOps.hasFile("/dest/Parent/existing.txt") shouldBe true
        mockOps.hasFile("/dest/Parent-new") shouldBe true
        mockOps.hasFile("/dest/Parent-new/SubDir") shouldBe true
        mockOps.hasFile("/dest/Parent-new/SubDir/file.txt") shouldBe true
        mockOps.getFileContent("/dest/Parent-new/SubDir/file.txt") shouldBe "content".toByteArray()

        result.copied.size shouldBe 3 // Parent-new + SubDir + file.txt
    }
}
