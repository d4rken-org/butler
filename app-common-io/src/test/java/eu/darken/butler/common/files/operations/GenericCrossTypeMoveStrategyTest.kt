package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
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
 * Tests for GenericCrossTypeMoveStrategy using LocalPath→LocalPath with MockFileSystemOps.
 *
 * This validates the generic copy+delete move pattern that works for ANY cross-type combination
 * (SAF↔Local, FTP↔Local, etc.) without requiring Android framework or actual file system access.
 *
 * Key behavior: Cross-type moves use copy+delete pattern because no atomic move is possible.
 * If copy succeeds but delete fails, the operation still returns success (destination exists).
 */
class GenericCrossTypeMoveStrategyTest : BaseTest() {

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

    // ============ BASIC MOVE OPERATIONS ============

    @Test
    fun `move single file uses copy and delete pattern`() = runTest {
        // Given
        val content = "Hello World".toByteArray()
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
        )

        // Then
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        mockOps.getFileContent("/dest/file.txt") shouldBe content  // Destination exists
        mockOps.hasFile("/source/file.txt") shouldBe false  // Source deleted
        (result as TransferStrategy.TransferResult.Success).bytesTransferred shouldBe content.size.toLong()
    }

    @Test
    fun `source deleted after successful copy`() = runTest {
        // Given
        val content = "Test content".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - source should be deleted
        mockOps.hasFile("/source/file.txt") shouldBe false
        mockOps.deleteCalls shouldBe listOf("/source/file.txt")
    }

    @Test
    fun `source not deleted if copy fails`() = runTest {
        // Given
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        // Note: /dest does not exist - copy will fail

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When/Then - copy should fail
        shouldThrow<Exception> {
            strategy.transferFile(
                sourceLookup = sourceLookup,
                destination = destPath,
                sourceOps = mockOps,
                destOps = mockOps,
                options = TransferStrategy.Options(),
                onProgress = {}
            )
        }

        // Then - source should still exist (delete never attempted)
        mockOps.hasFile("/source/file.txt") shouldBe true
        mockOps.deleteCalls shouldBe emptyList()
    }

    @Test
    fun `byte counting accurate for move operation`() = runTest {
        // Given
        val content = "Test content for move".toByteArray()
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
        result.bytesTransferred shouldBe content.size.toLong()
    }

    @Test
    fun `move large file succeeds`() = runTest {
        // Given - 150KB file
        val largeContent = ByteArray(150 * 1024) { it.toByte() }
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
        )

        // Then
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        mockOps.getFileContent("/dest/large.bin") shouldBe largeContent
        mockOps.hasFile("/source/large.bin") shouldBe false
    }

    // ============ SOURCE DELETION HANDLING ============

    @Test
    fun `successful deletion after copy`() = runTest {
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
        )

        // Then
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        mockOps.hasFile("/source/file.txt") shouldBe false
        mockOps.hasFile("/dest/file.txt") shouldBe true
    }

    @Test
    fun `failed deletion returns success with warning`() = runTest {
        // Given
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Create a spy to simulate delete failure
        val spyOps = spyk(mockOps)
        coEvery { spyOps.delete(sourcePath) } returns false  // Simulate delete failure

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - operation still returns success (destination was created)
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        spyOps.hasFile("/dest/file.txt") shouldBe true
        coVerify { spyOps.delete(sourcePath) }
    }

    @Test
    fun `deletion exception returns success with error log`() = runTest {
        // Given
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Create a spy to simulate delete exception
        val spyOps = spyk(mockOps)
        coEvery { spyOps.delete(sourcePath) } throws RuntimeException("Delete failed")

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - operation still returns success (destination was created)
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        spyOps.hasFile("/dest/file.txt") shouldBe true
        coVerify { spyOps.delete(sourcePath) }
    }

    @Test
    fun `source remains if deletion fails but destination exists`() = runTest {
        // Given
        val content = "Test content".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // Create a spy to simulate delete failure
        val spyOps = spyk(mockOps)
        coEvery { spyOps.delete(sourcePath) } returns false

        // When
        strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = spyOps,
            destOps = spyOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - both source and destination exist (acceptable behavior)
        spyOps.hasFile("/source/file.txt") shouldBe true
        spyOps.hasFile("/dest/file.txt") shouldBe true
    }

    // ============ DIRECTORY MOVE BEHAVIOR ============

    @Test
    fun `move directory creates at destination`() = runTest {
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

    @Test
    fun `move directory does not delete source immediately`() = runTest {
        // Given
        mockOps.addMockDir("/source/dir")
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/dir")
        val destPath = LocalPath.build("/dest/dir")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        strategy.createDirectory(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options()
        )

        // Then - source directory still exists (cleanup deferred to GenericPathMove)
        mockOps.hasFile("/source/dir") shouldBe true
        mockOps.hasFile("/dest/dir") shouldBe true
        mockOps.deleteCalls shouldBe emptyList()
    }

    @Test
    fun `directory move preserves attributes if requested`() = runTest {
        // Given
        val modifiedTime = kotlin.time.Instant.fromEpochMilliseconds(1234567890000)
        mockOps.addMockDir("/source/dir")
        mockOps.files["/source/dir"] = mockOps.files["/source/dir"]!!.copy(modifiedAt = modifiedTime)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/dir")
        val destPath = LocalPath.build("/dest/dir")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When
        strategy.createDirectory(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(preserveAttributes = true)
        )

        // Then
        val destDir = mockOps.files["/dest/dir"]!!
        destDir.modifiedAt shouldBe modifiedTime
    }

    // ============ PROGRESS REPORTING ============

    @Test
    fun `progress reported during copy phase`() = runTest {
        // Given
        val content = "Progress test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        val progressUpdates = mutableListOf<Long>()

        // When
        strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = { bytesTransferred -> progressUpdates.add(bytesTransferred) }
        )

        // Then
        progressUpdates.size shouldBe 1
        progressUpdates.sum() shouldBe content.size.toLong()
    }

    @Test
    fun `progress includes copy phase for large files`() = runTest {
        // Given - 150KB file
        val largeContent = ByteArray(150 * 1024) { it.toByte() }
        mockOps.addMockFile("/source/large.bin", largeContent)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/large.bin")
        val destPath = LocalPath.build("/dest/large.bin")
        val sourceLookup = mockOps.lookup(sourcePath)

        val progressUpdates = mutableListOf<Long>()

        // When
        strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = { bytesTransferred -> progressUpdates.add(bytesTransferred) }
        )

        // Then - multiple progress updates during copy
        // Note: Exact count depends on Okio's buffering, but should be more than 1
        (progressUpdates.size > 1) shouldBe true
        progressUpdates.sum() shouldBe largeContent.size.toLong()
    }

    @Test
    fun `cumulative bytes accurate for move`() = runTest {
        // Given
        val content = ByteArray(100) { it.toByte() }
        mockOps.addMockFile("/source/file.bin", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.bin")
        val destPath = LocalPath.build("/dest/file.bin")
        val sourceLookup = mockOps.lookup(sourcePath)

        val progressUpdates = mutableListOf<Long>()

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = { bytesTransferred -> progressUpdates.add(bytesTransferred) }
        ) as TransferStrategy.TransferResult.Success

        // Then
        result.bytesTransferred shouldBe content.size.toLong()
        progressUpdates.sum() shouldBe content.size.toLong()
    }

    // ============ ERROR HANDLING ============

    @Test
    fun `copy fails returns error without delete attempt`() = runTest {
        // Given
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        // Note: /dest does not exist

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When/Then - should throw
        shouldThrow<Exception> {
            strategy.transferFile(
                sourceLookup = sourceLookup,
                destination = destPath,
                sourceOps = mockOps,
                destOps = mockOps,
                options = TransferStrategy.Options(),
                onProgress = {}
            )
        }

        // Then - source still exists, no delete attempted
        mockOps.hasFile("/source/file.txt") shouldBe true
        mockOps.deleteCalls shouldBe emptyList()
    }

    @Test
    fun `source not found propagates error`() = runTest {
        // Given
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/nonexistent.txt")
        val destPath = LocalPath.build("/dest/nonexistent.txt")

        // When/Then
        shouldThrow<Exception> {
            val sourceLookup = mockOps.lookup(sourcePath)  // Should throw
            strategy.transferFile(
                sourceLookup = sourceLookup,
                destination = destPath,
                sourceOps = mockOps,
                destOps = mockOps,
                options = TransferStrategy.Options(),
                onProgress = {}
            )
        }
    }

    @Test
    fun `destination parent not found propagates error`() = runTest {
        // Given
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        // Note: /dest does not exist

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When/Then
        shouldThrow<Exception> {
            strategy.transferFile(
                sourceLookup = sourceLookup,
                destination = destPath,
                sourceOps = mockOps,
                destOps = mockOps,
                options = TransferStrategy.Options(),
                onProgress = {}
            )
        }
    }

    // ============ INTEGRATION WITH COPY STRATEGY ============

    @Test
    fun `uses GenericCrossTypeCopyStrategy internally`() = runTest {
        // Given
        val content = "Integration test".toByteArray()
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
        )

        // Then - behavior matches copy strategy (stream-based transfer)
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        mockOps.getFileContent("/dest/file.txt") shouldBe content
        // Plus deletion of source
        mockOps.hasFile("/source/file.txt") shouldBe false
    }

    @Test
    fun `copy result determines deletion behavior`() = runTest {
        // Given - scenario where copy succeeds
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When - copy succeeds
        strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - delete was attempted
        mockOps.deleteCalls.size shouldBe 1
        mockOps.deleteCalls shouldBe listOf("/source/file.txt")
    }

    @Test
    fun `move result contains correct source and destination`() = runTest {
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
    fun `empty file move succeeds`() = runTest {
        // Given
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
        )

        // Then
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        mockOps.getFileContent("/dest/empty.txt") shouldBe emptyContent
        mockOps.hasFile("/source/empty.txt") shouldBe false
    }
}
