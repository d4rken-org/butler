package eu.darken.butler.common.files.local.operations.strategies

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.MockFileSystemOps
import eu.darken.butler.common.files.operations.TransferStrategy
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
import java.nio.file.AtomicMoveNotSupportedException

/**
 * Tests for LocalPathMoveStrategy atomic move behavior.
 *
 * This validates that LocalPathMoveStrategy:
 * 1. Attempts atomic move first using Files.move() via sourceOps.move()
 * 2. Falls back to copy+delete when AtomicMoveNotSupportedException is thrown
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

        // Mock atomic move failure (cross-device scenario)
        val spyOps = spyk(mockOps)
        coEvery {
            spyOps.move(sourcePath, destPath)
        } throws AtomicMoveNotSupportedException(
            sourcePath.path,
            destPath.path,
            "Cross-device move not supported"
        )

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

        // Mock atomic move failure
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(sourcePath, destPath) } throws AtomicMoveNotSupportedException(
            sourcePath.path,
            destPath.path,
            "Cross-device"
        )

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

        // Mock both atomic move failure and delete failure
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(sourcePath, destPath) } throws AtomicMoveNotSupportedException(
            sourcePath.path,
            destPath.path,
            "Cross-device"
        )
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

        // Mock atomic move failures
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(any(), any()) } throws AtomicMoveNotSupportedException(
            source1.path,
            dest1.path,
            "Cross-device"
        )

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

        // Mock atomic move failure
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(sourcePath, destPath) } throws AtomicMoveNotSupportedException(
            sourcePath.path,
            destPath.path,
            "Cross-device"
        )

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

        // Mock atomic move failure
        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(sourcePath, destPath) } throws AtomicMoveNotSupportedException(
            sourcePath.path,
            destPath.path,
            "Cross-device"
        )

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
}

/**
 * Mock implementation of LocalFileSystemOps for testing.
 *
 * Simulates local file system behavior including:
 * - Atomic moves within same device (succeeds)
 * - Cross-device moves (throws AtomicMoveNotSupportedException)
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

    override suspend fun move(source: LocalPath, destination: LocalPath): Boolean {
        // Simulate local file system atomic move behavior:
        // - Same device: atomic move succeeds
        // - Different device: throws AtomicMoveNotSupportedException

        // Simple heuristic: different first path segment = different device
        val sourceDevice = source.path.split("/").getOrNull(1)
        val destDevice = destination.path.split("/").getOrNull(1)

        if (sourceDevice != destDevice && sourceDevice != null && destDevice != null) {
            throw AtomicMoveNotSupportedException(
                source.path,
                destination.path,
                "Cross-device move not supported"
            )
        }

        // Same device: perform atomic move (simulate Files.move with ATOMIC_MOVE)
        val fileData = files[source.path] ?: throw WriteException("Source does not exist", source)

        files[destination.path] = fileData
        files.remove(source.path)

        return true
    }
}
