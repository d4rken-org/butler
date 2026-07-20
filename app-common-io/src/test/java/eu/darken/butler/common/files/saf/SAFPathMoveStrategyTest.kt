package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.operations.TransferStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

/**
 * Tests for SAFPathMoveStrategy atomic move behavior.
 *
 * This validates that SAFPathMoveStrategy:
 * 1. Attempts atomic move first using DocumentsContract.moveDocument() via sourceOps.move()
 * 2. Falls back to copy+delete when atomic move fails
 * 3. Correctly reports progress for both atomic and fallback paths
 *
 * This mirrors LocalPathMoveStrategy's behavior but for SAF paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SAFPathMoveStrategyTest : BaseTest() {

    private lateinit var mockOps: MockSAFFileSystemOps
    private lateinit var strategy: SAFPathMoveStrategy

    // Valid SAF tree URIs for testing
    private val primaryTreeUri = "content://com.android.externalstorage.documents/tree/primary%3A"
    private val secondaryTreeUri = "content://com.android.externalstorage.documents/tree/4BBD-D3E7%3A"

    @Before
    fun setup() {
        mockOps = MockSAFFileSystemOps()
        strategy = SAFPathMoveStrategy()
    }

    @After
    fun cleanup() {
        mockOps.clear()
    }

    // ============ ATOMIC MOVE TESTS ============

    @Test
    fun `atomic move succeeds without copy strategy`() = runTest {
        // Given - file that can be moved atomically
        val content = "Hello World".toByteArray()
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "file.txt")
        val destPath = SAFPath.build(primaryTreeUri, "dest", "file.txt")
        val destParent = SAFPath.build(primaryTreeUri, "dest")

        mockOps.addMockFile(sourcePath.path, content)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourcePath)

        // Spy on mockOps to verify atomic move is called
        val spyOps = spyk(mockOps)

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onProgress = {}
        )

        // Then - atomic move succeeded
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        (result as TransferStrategy.TransferResult.Success).bytesTransferred shouldBe content.size.toLong()

        // Verify atomic move was called
        coVerify(exactly = 1) { spyOps.move(sourcePath, destPath) }

        // Verify file system state (atomic move succeeded)
        spyOps.hasFile(destPath.path) shouldBe true
        spyOps.hasFile(sourcePath.path) shouldBe false
    }

    @Test
    fun `atomic move reports correct bytes transferred`() = runTest {
        // Given
        val content = ByteArray(1024) { it.toByte() } // 1KB file
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "data.bin")
        val destPath = SAFPath.build(primaryTreeUri, "dest", "data.bin")
        val destParent = SAFPath.build(primaryTreeUri, "dest")

        mockOps.addMockFile(sourcePath.path, content)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourcePath)

        var progressCalled = false
        var bytesReported = 0L

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
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
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "empty.txt")
        val destPath = SAFPath.build(primaryTreeUri, "dest", "empty.txt")
        val destParent = SAFPath.build(primaryTreeUri, "dest")

        mockOps.addMockFile(sourcePath.path, emptyContent)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onProgress = {}
        ) as TransferStrategy.TransferResult.Success

        // Then
        result.bytesTransferred shouldBe 0L
        mockOps.hasFile(destPath.path) shouldBe true
        mockOps.hasFile(sourcePath.path) shouldBe false
    }

    // ============ FALLBACK TO COPY+DELETE ============

    @Test
    fun `atomic move fails falls back to copy+delete`() = runTest {
        // Given - simulate cross-tree move that can't be atomic
        val content = "Cross-tree content".toByteArray()
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "file.txt")
        val destPath = SAFPath.build(secondaryTreeUri, "dest", "file.txt")
        val destParent = SAFPath.build(secondaryTreeUri, "dest")

        mockOps.addMockFile(sourcePath.path, content)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourcePath)

        // Spy on mockOps - the built-in cross-tree check returns MoveOutcome.NotSupported
        val spyOps = spyk(mockOps)

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onProgress = {}
        )

        // Then - fallback succeeded
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()

        // Verify atomic move was attempted
        coVerify(exactly = 1) { spyOps.move(sourcePath, destPath) }

        // Verify fallback copy+delete occurred
        spyOps.hasFile(destPath.path) shouldBe true
        spyOps.getFileContent(destPath.path) shouldBe content
        spyOps.hasFile(sourcePath.path) shouldBe false
    }

    @Test
    fun `cancellation during atomic move propagates without fallback IO`() = runTest {
        val content = "Cancelled".toByteArray()
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "file.txt")
        val destPath = SAFPath.build(primaryTreeUri, "source", "renamed.txt")

        mockOps.addMockFile(sourcePath.path, content)
        val sourceLookup = mockOps.lookup(sourcePath)

        val spyOps = spyk(mockOps)
        coEvery { spyOps.move(any(), any()) } throws CancellationException("cancelled")

        shouldThrow<CancellationException> {
            strategy.transferFile(
                sourceLookup = sourceLookup,
                destination = destPath,
                sourceOps = spyOps,
                destOps = spyOps,
                options = TransferStrategy.Options(),
                onProgress = {}
            )
        }

        // No destructive fallback ran: source untouched, nothing written to the destination
        spyOps.hasFile(sourcePath.path) shouldBe true
        spyOps.hasFile(destPath.path) shouldBe false
        coVerify(exactly = 0) { spyOps.openOutputStream(any(), any()) }
        coVerify(exactly = 0) { spyOps.delete(any(), any()) }
    }

    @Test
    fun `move exception with a vanished source is rethrown instead of copied`() = runTest {
        val content = "Gone".toByteArray()
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "file.txt")
        val destPath = SAFPath.build(primaryTreeUri, "source", "renamed.txt")

        mockOps.addMockFile(sourcePath.path, content)
        val sourceLookup = mockOps.lookup(sourcePath)

        val spyOps = spyk(mockOps)
        // The failed move had side effects: the source is gone afterwards
        coEvery { spyOps.move(any(), any()) } coAnswers {
            mockOps.files.remove(sourcePath.path)
            throw WriteException("provider failed mid-move", sourcePath)
        }

        shouldThrow<WriteException> {
            strategy.transferFile(
                sourceLookup = sourceLookup,
                destination = destPath,
                sourceOps = spyOps,
                destOps = spyOps,
                options = TransferStrategy.Options(),
                onProgress = {}
            )
        }

        // The copy fallback must not have run against the vanished source
        coVerify(exactly = 0) { spyOps.openOutputStream(any(), any()) }
    }

    @Test
    fun `fallback copy+delete reports progress correctly`() = runTest {
        // Given
        val content = ByteArray(5000) { it.toByte() }
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "large.bin")
        val destPath = SAFPath.build(secondaryTreeUri, "dest", "large.bin")
        val destParent = SAFPath.build(secondaryTreeUri, "dest")

        mockOps.addMockFile(sourcePath.path, content)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourcePath)

        // Spy on mockOps - the built-in cross-tree check returns MoveOutcome.NotSupported
        val spyOps = spyk(mockOps)

        val progressUpdates = mutableListOf<Long>()

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
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
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "file.txt")
        val destPath = SAFPath.build(secondaryTreeUri, "dest", "file.txt")
        val destParent = SAFPath.build(secondaryTreeUri, "dest")

        mockOps.addMockFile(sourcePath.path, content)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourcePath)

        // Mock delete failure
        val spyOps = spyk(mockOps)
        coEvery { spyOps.delete(sourcePath) } returns false // Delete fails

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onProgress = {}
        )

        // Then - operation still succeeds (destination was created)
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        spyOps.hasFile(destPath.path) shouldBe true

        // Source still exists due to delete failure
        spyOps.hasFile(sourcePath.path) shouldBe true

        // Verify delete was attempted
        coVerify { spyOps.delete(sourcePath) }
    }

    @Test
    fun `multiple atomic move failures all fall back correctly`() = runTest {
        // Given - multiple files, all requiring fallback
        val content1 = "File 1".toByteArray()
        val content2 = "File 2".toByteArray()
        val source1 = SAFPath.build(primaryTreeUri, "source", "file1.txt")
        val source2 = SAFPath.build(primaryTreeUri, "source", "file2.txt")
        val dest1 = SAFPath.build(secondaryTreeUri, "dest", "file1.txt")
        val dest2 = SAFPath.build(secondaryTreeUri, "dest", "file2.txt")
        val destParent = SAFPath.build(secondaryTreeUri, "dest")

        mockOps.addMockFile(source1.path, content1)
        mockOps.addMockFile(source2.path, content2)
        mockOps.addMockDir(destParent.path)

        // Spy on mockOps - the built-in cross-tree check returns MoveOutcome.NotSupported
        val spyOps = spyk(mockOps)

        // When - move both files
        val result1 = strategy.transferFile(
            sourceLookup = spyOps.lookup(source1),
            destination = dest1,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onProgress = {}
        )
        val result2 = strategy.transferFile(
            sourceLookup = spyOps.lookup(source2),
            destination = dest2,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onProgress = {}
        )

        // Then - both succeeded via fallback
        result1.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        result2.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        spyOps.hasFile(dest1.path) shouldBe true
        spyOps.hasFile(dest2.path) shouldBe true
    }

    // ============ EDGE CASES ============

    @Test
    fun `large file atomic move succeeds`() = runTest {
        // Given - 1MB file
        val largeContent = ByteArray(1024 * 1024) { it.toByte() }
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "large.bin")
        val destPath = SAFPath.build(primaryTreeUri, "dest", "large.bin")
        val destParent = SAFPath.build(primaryTreeUri, "dest")

        mockOps.addMockFile(sourcePath.path, largeContent)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onProgress = {}
        ) as TransferStrategy.TransferResult.Success

        // Then - atomic move should handle large files efficiently
        result.bytesTransferred shouldBe largeContent.size.toLong()
        mockOps.hasFile(destPath.path) shouldBe true
        mockOps.hasFile(sourcePath.path) shouldBe false
    }

    @Test
    fun `atomic move result contains correct source and destination`() = runTest {
        // Given
        val content = "Test".toByteArray()
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "file.txt")
        val destPath = SAFPath.build(primaryTreeUri, "dest", "file.txt")
        val destParent = SAFPath.build(primaryTreeUri, "dest")

        mockOps.addMockFile(sourcePath.path, content)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
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
        val sourcePath = SAFPath.build(primaryTreeUri, "source", "file.txt")
        val destPath = SAFPath.build(secondaryTreeUri, "dest", "file.txt")
        val destParent = SAFPath.build(secondaryTreeUri, "dest")

        mockOps.addMockFile(sourcePath.path, content)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourcePath)

        // Spy on mockOps - the built-in cross-tree check returns MoveOutcome.NotSupported
        val spyOps = spyk(mockOps)

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onProgress = {}
        ) as TransferStrategy.TransferResult.Success

        // Then
        result.source shouldBe sourcePath
        result.destination shouldBe destPath
    }

    // ============ DIRECTORY MOVE BEHAVIOR ============

    @Test
    fun `directory move creates at destination without deletion`() = runTest {
        // Given
        val sourceDir = SAFPath.build(primaryTreeUri, "source", "dir")
        val destDir = SAFPath.build(primaryTreeUri, "dest", "dir")
        val destParent = SAFPath.build(primaryTreeUri, "dest")

        mockOps.addMockDir(sourceDir.path)
        mockOps.addMockDir(destParent.path)

        val sourceLookup = mockOps.lookup(sourceDir)

        // When
        val result = strategy.createDirectory(
            sourceLookup = sourceLookup,
            destination = destDir,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(attemptAtomicMove = false)
        )

        // Then
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        mockOps.hasFile(destDir.path) shouldBe true

        // Source directory still exists (cleanup happens in GenericPathMove)
        mockOps.hasFile(sourceDir.path) shouldBe true
    }
}
