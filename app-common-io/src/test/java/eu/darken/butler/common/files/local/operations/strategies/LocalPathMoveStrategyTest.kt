package eu.darken.butler.common.files.local.operations.strategies

import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.MockFileSystemOps
import eu.darken.butler.common.files.operations.TransferStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for LocalPathMoveStrategy atomic move behavior.
 *
 * This validates that LocalPathMoveStrategy:
 * 1. Attempts atomic move first using Files.move() via sourceOps.move()
 * 2. Falls back to copy+delete when move() returns [MoveOutcome.NotSupported]
 * 3. Correctly reports progress for both atomic and fallback paths
 * 4. Handles symlinks specially with target adjustment
 *
 * This mirrors SAFPathMoveStrategy's test structure but for local file system paths.
 */
class LocalPathMoveStrategyTest : BaseTest() {

    private lateinit var mockOps: MockLocalFileSystemOps
    private lateinit var strategy: LocalPathMoveStrategy

    @BeforeEach
    fun setup() {
        mockOps = MockLocalFileSystemOps()
        // Every case below is about a destination that is meant to be free; the ones that are not
        // say so per path.
        mockOps.defaultExistsStrict = Existence.ABSENT
        strategy = LocalPathMoveStrategy(mockOps)
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ ATOMIC MOVE TESTS ============

    @Test
    fun `atomic move succeeds without copy strategy`() = runTest {
        // Given - file that can be moved atomically
        val content = "Hello World".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Spy on mockOps to verify atomic move is called
        val spyOps = spyk(mockOps)
        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When
        val result = spyStrategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - atomic move succeeded
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        (result as TransferStrategy.TransferResult.Success).bytesTransferred shouldBe content.size.toLong()

        // Verify atomic move was called
        coVerify(exactly = 1) { spyOps.move(sourcePath, destPath) }

        // Verify file system state (atomic move succeeded)
        spyOps.hasFile("/dest/file.txt") shouldBe true
        spyOps.hasFile("/source/file.txt") shouldBe false
    }

    @Test
    fun `atomic move reports correct bytes transferred`() = runTest {
        // Given
        val content = ByteArray(1024) { it.toByte() } // 1KB file
        mockOps.addMockFile("/source/data.bin", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/data.bin")
        val destPath = LocalPath.build("/dest/data.bin")
        val sourceLookup = mockOps.lookup(sourcePath)

        var progressCalled = false
        var bytesReported = 0L

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = { bytes ->
                progressCalled = true
                bytesReported = bytes
            }
        ) as TransferStrategy.TransferResult.Success

        // Then
        result.bytesTransferred shouldBe content.size.toLong()
        progressCalled shouldBe true
        bytesReported shouldBe content.size.toLong()
    }

    @Test
    fun `atomic move with zero byte file`() = runTest {
        // Given - empty file
        val emptyContent = ByteArray(0)
        mockOps.addMockFile("/source/empty.txt", emptyContent)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/empty.txt")
        val destPath = LocalPath.build("/dest/empty.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        ) as TransferStrategy.TransferResult.Success

        // Then
        result.bytesTransferred shouldBe 0L
        mockOps.hasFile("/dest/empty.txt") shouldBe true
        mockOps.hasFile("/source/empty.txt") shouldBe false
    }

    // ============ FALLBACK TO COPY+DELETE ============

    @Test
    fun `atomic move fails falls back to copy+delete`() = runTest {
        // Given - simulate cross-device move that can't be atomic
        val content = "Cross-device content".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Mock atomic move refusal (cross-device scenario)
        val spyOps = spyk(mockOps)
        coEvery {
            spyOps.move(sourcePath, destPath)
        } returns MoveOutcome.NotSupported("Cross-device move not supported")

        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When
        val result = spyStrategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - fallback succeeded
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()

        // Verify atomic move was attempted
        coVerify(exactly = 1) { spyOps.move(sourcePath, destPath) }

        // Verify fallback copy+delete occurred
        spyOps.hasFile("/dest/file.txt") shouldBe true
        spyOps.getFileContent("/dest/file.txt") shouldBe content
        spyOps.hasFile("/source/file.txt") shouldBe false
    }

    @Test
    fun `fallback copy+delete reports progress correctly`() = runTest {
        // Given
        val content = ByteArray(5000) { it.toByte() }
        mockOps.addMockFile("/source/large.bin", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/large.bin")
        val destPath = LocalPath.build("/dest/large.bin")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Mock atomic move refusal
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(sourcePath, destPath) } returns MoveOutcome.NotSupported("Cross-device")

        val spyStrategy = LocalPathMoveStrategy(spyOps)
        val progressUpdates = mutableListOf<Long>()

        // When
        val result = spyStrategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = { bytes -> progressUpdates.add(bytes) }
        ) as TransferStrategy.TransferResult.Success

        // Then
        result.bytesTransferred shouldBe content.size.toLong()
        progressUpdates.isNotEmpty() shouldBe true
        progressUpdates.sum() shouldBe content.size.toLong()
    }

    @Test
    fun `fallback succeeds even if source delete fails`() = runTest {
        // Given
        val content = "Test content".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Mock both atomic move refusal and delete failure
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(sourcePath, destPath) } returns MoveOutcome.NotSupported("Cross-device")
        coEvery { spyOps.delete(sourcePath, recursive = false) } returns false // Delete fails

        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When
        val result = spyStrategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - operation still succeeds (destination was created)
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        spyOps.hasFile("/dest/file.txt") shouldBe true

        // Source still exists due to delete failure
        spyOps.hasFile("/source/file.txt") shouldBe true

        // Verify delete was attempted
        coVerify { spyOps.delete(sourcePath, recursive = false) }
    }

    @Test
    fun `multiple atomic move failures all fall back correctly`() = runTest {
        // Given - multiple files, all requiring fallback
        val content1 = "File 1".toByteArray()
        val content2 = "File 2".toByteArray()
        mockOps.addMockFile("/source/file1.txt", content1)
        mockOps.addMockFile("/source/file2.txt", content2)
        mockOps.addMockDir("/dest")

        val source1 = LocalPath.build("/source/file1.txt")
        val source2 = LocalPath.build("/source/file2.txt")
        val dest1 = LocalPath.build("/dest/file1.txt")
        val dest2 = LocalPath.build("/dest/file2.txt")

        // Mock atomic move refusals
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(any(), any()) } returns MoveOutcome.NotSupported("Cross-device")

        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When - move both files
        val result1 = spyStrategy.transferFile(
            sourceLookup = spyOps.lookup(source1),
            destination = dest1,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )
        val result2 = spyStrategy.transferFile(
            sourceLookup = spyOps.lookup(source2),
            destination = dest2,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - both succeeded via fallback
        result1.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        result2.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        spyOps.hasFile("/dest/file1.txt") shouldBe true
        spyOps.hasFile("/dest/file2.txt") shouldBe true
    }

    // ============ SYMLINK HANDLING ============

    @Test
    fun `symlink moved via special handling not atomic move`() = runTest {
        // Given - symlink (LocalPath-specific feature)
        mockOps.addMockSymlink("/source/link", "/target/file.txt")
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/link")
        val destPath = LocalPath.build("/dest/link")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Spy to verify atomic move is NOT called for symlinks
        val spyOps = spyk(mockOps)
        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When
        val result = spyStrategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - symlink handled specially
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()

        // Atomic move should NOT be called for symlinks
        coVerify(exactly = 0) { spyOps.move(any(), any()) }

        // Verify symlink recreated at destination
        spyOps.hasFile("/dest/link") shouldBe true
        spyOps.getFileType("/dest/link") shouldBe FileType.SYMBOLIC_LINK

        // Source symlink deleted
        spyOps.hasFile("/source/link") shouldBe false
    }

    // ============ EDGE CASES ============

    @Test
    fun `large file atomic move succeeds`() = runTest {
        // Given - 1MB file
        val largeContent = ByteArray(1024 * 1024) { it.toByte() }
        mockOps.addMockFile("/source/large.bin", largeContent)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/large.bin")
        val destPath = LocalPath.build("/dest/large.bin")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        ) as TransferStrategy.TransferResult.Success

        // Then - atomic move should handle large files efficiently
        result.bytesTransferred shouldBe largeContent.size.toLong()
        mockOps.hasFile("/dest/large.bin") shouldBe true
        mockOps.hasFile("/source/large.bin") shouldBe false
    }

    @Test
    fun `atomic move result contains correct source and destination`() = runTest {
        // Given
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        ) as TransferStrategy.TransferResult.Success

        // Then
        result.source shouldBe sourcePath
        result.destination shouldBe destPath
    }

    @Test
    fun `fallback result contains correct source and destination`() = runTest {
        // Given
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Mock atomic move refusal
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(sourcePath, destPath) } returns MoveOutcome.NotSupported("Cross-device")

        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When
        val result = spyStrategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        ) as TransferStrategy.TransferResult.Success

        // Then
        result.source shouldBe sourcePath
        result.destination shouldBe destPath
    }

    @Test
    fun `fallback preserves attributes when requested`() = runTest {
        // Given
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Mock atomic move refusal
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(sourcePath, destPath) } returns MoveOutcome.NotSupported("Cross-device")

        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When
        spyStrategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(preserveAttributes = true),
            onProgress = {}
        )

        // Then - verify setModifiedAt was called for attribute preservation
        coVerify { spyOps.setModifiedAt(destPath, any()) }
    }

    // ============ DIRECTORY MOVE BEHAVIOR ============

    @Test
    fun `directory move creates at destination`() = runTest {
        // Given
        mockOps.addMockDir("/source/dir")
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/dir")
        val destPath = LocalPath.build("/dest/dir")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        val result = strategy.createDirectory(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options()
        )

        // Then
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        mockOps.hasFile("/dest/dir") shouldBe true
        mockOps.getFileType("/dest/dir") shouldBe FileType.DIRECTORY
    }

    // ============ DIRECTORY CREATE-ONLY TESTS ============

    @Test
    fun `createDirectory never attempts atomic move`() = runTest {
        // Atomic directory moves are owned solely by GenericPathMove.tryAtomicMove;
        // createDirectory only creates the empty destination directory.

        mockOps.addMockDir("/data/source/folder")
        mockOps.addMockDir("/data/dest")

        val sourcePath = LocalPath.build("/data/source/folder")
        val destPath = LocalPath.build("/data/dest/folder")
        val sourceLookup = mockOps.lookup(sourcePath)

        val spyOps = spyk(mockOps)
        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When
        spyStrategy.createDirectory(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options()
        )

        // Then - move() is NOT called, only createDir()
        coVerify(exactly = 0) { spyOps.move(any(), any()) }
        coVerify(exactly = 1) { spyOps.createDir(destPath) }

        // Destination created, source untouched (cleanup happens in GenericPathMove)
        spyOps.hasFile("/data/dest/folder") shouldBe true
        spyOps.hasFile("/data/source/folder") shouldBe true
    }

    @Test
    fun `createDirectory with rename creates destination only`() = runTest {
        mockOps.addMockDir("/data/source/AAAA")
        mockOps.addMockDir("/data/dest")

        val sourcePath = LocalPath.build("/data/source/AAAA")
        val destPath = LocalPath.build("/data/dest/BBB")
        val sourceLookup = mockOps.lookup(sourcePath)

        val spyOps = spyk(mockOps)
        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When
        val result = spyStrategy.createDirectory(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options()
        )

        // Then - no atomic move attempt
        coVerify(exactly = 0) { spyOps.move(any(), any()) }

        // Result should be success
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()

        // Destination created under the new name, source still present
        spyOps.hasFile("/data/dest/BBB") shouldBe true
        spyOps.hasFile("/data/source/AAAA") shouldBe true
    }

    // ============ OCCUPIED DESTINATION ============

    /**
     * A FIFO, socket or device node is FileType.UNKNOWN to the plain lookup, i.e. indistinguishable
     * from "nothing there", and the fallback's truncating copy would write straight into it.
     */
    @Test
    fun `a destination the plain lookup cannot classify is a conflict`() = runTest {
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.existsStrictAnswers["/dest/file.txt"] = Existence.PRESENT
        val spyOps = spyk(mockOps)
        val spyStrategy = LocalPathMoveStrategy(spyOps)

        shouldThrow<PathAlreadyExistsException> {
            spyStrategy.transferFile(
                sourceLookup = mockOps.lookup(LocalPath.build("/source/file.txt")),
                destination = LocalPath.build("/dest/file.txt"),
                sourceOps = spyOps,
                destOps = spyOps,
                options = TransferStrategy.Options(overwrite = false),
                onProgress = {},
            )
        }

        coVerify(exactly = 0) { spyOps.openOutputStream(any(), any()) }
        spyOps.hasFile("/dest/file.txt") shouldBe false
        spyOps.hasFile("/source/file.txt") shouldBe true
    }

    @Test
    fun `a destination that cannot be inspected stops the fallback copy`() = runTest {
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.existsStrictAnswers["/dest/file.txt"] = Existence.UNKNOWN
        val spyOps = spyk(mockOps)
        val spyStrategy = LocalPathMoveStrategy(spyOps)

        shouldThrow<WriteException> {
            spyStrategy.transferFile(
                sourceLookup = mockOps.lookup(LocalPath.build("/source/file.txt")),
                destination = LocalPath.build("/dest/file.txt"),
                sourceOps = spyOps,
                destOps = spyOps,
                options = TransferStrategy.Options(overwrite = false),
                onProgress = {},
            )
        }

        coVerify(exactly = 0) { spyOps.openOutputStream(any(), any()) }
        spyOps.hasFile("/dest/file.txt") shouldBe false
        spyOps.hasFile("/source/file.txt") shouldBe true
    }

    @Test
    fun `createDirectory across devices creates directory only`() = runTest {
        mockOps.addMockDir("/source/folder")
        mockOps.addMockDir("/otherdevice")

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/otherdevice/folder")
        val sourceLookup = mockOps.lookup(sourcePath)

        val spyOps = spyk(mockOps)
        val spyStrategy = LocalPathMoveStrategy(spyOps)

        // When
        val result = spyStrategy.createDirectory(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options()
        )

        // Then - no atomic move attempt, just createDir
        coVerify(exactly = 0) { spyOps.move(any(), any()) }
        coVerify(exactly = 1) { spyOps.createDir(destPath) }

        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
    }
}

/**
 * Mock implementation of LocalFileSystemOps for testing.
 *
 * Simulates local file system behavior including:
 * - Atomic moves within same device (succeeds)
 * - Cross-device moves (returns MoveOutcome.NotSupported)
 * - Symlink support
 */
private class MockLocalFileSystemOps : MockFileSystemOps<LocalPath, LocalPathLookup>(
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

    override suspend fun move(source: LocalPath, destination: LocalPath): MoveOutcome {
        // Simulate local file system atomic move behavior:
        // - Same device: atomic move succeeds
        // - Different device: returns MoveOutcome.NotSupported

        // Simple heuristic: different first path segment = different device
        val sourceDevice = source.path.split("/").getOrNull(1)
        val destDevice = destination.path.split("/").getOrNull(1)

        if (sourceDevice != destDevice && sourceDevice != null && destDevice != null) {
            return MoveOutcome.NotSupported("Cross-device move not supported")
        }

        // Same device: call parent implementation (handles all bookkeeping + children)
        return super.move(source, destination)
    }
}
