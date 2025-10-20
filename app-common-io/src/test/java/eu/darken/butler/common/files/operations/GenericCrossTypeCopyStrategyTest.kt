package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for GenericCrossTypeCopyStrategy using LocalPath→LocalPath with MockFileSystemOps.
 *
 * This validates the generic stream-based copy logic that works for ANY cross-type combination
 * (SAF↔Local, FTP↔Local, etc.) without requiring Android framework or actual file system access.
 */
class GenericCrossTypeCopyStrategyTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup>
    private lateinit var strategy: GenericCrossTypeCopyStrategy<
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
        strategy = GenericCrossTypeCopyStrategy()
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ BASIC TRANSFER OPERATIONS ============

    @Test
    fun `copy single file transfers content correctly`() = runTest {
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
        mockOps.getFileContent("/dest/file.txt") shouldBe content
        (result as TransferStrategy.TransferResult.Success).bytesTransferred shouldBe content.size.toLong()
    }

    @Test
    fun `copy empty file succeeds`() = runTest {
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
        (result as TransferStrategy.TransferResult.Success).bytesTransferred shouldBe 0L
    }

    @Test
    fun `copy large file uses chunked transfer`() = runTest {
        // Given - 200KB file (larger than 64KB buffer)
        val largeContent = ByteArray(200 * 1024) { it.toByte() }
        mockOps.addMockFile("/source/large.bin", largeContent)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/large.bin")
        val destPath = LocalPath.build("/dest/large.bin")
        val sourceLookup = mockOps.lookup(sourcePath)

        var progressCallCount = 0

        // When
        val result = strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = { progressCallCount++ }
        )

        // Then
        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        mockOps.getFileContent("/dest/large.bin") shouldBe largeContent
        (result as TransferStrategy.TransferResult.Success).bytesTransferred shouldBe largeContent.size.toLong()

        // Should have multiple progress callbacks for chunked transfer
        // Note: Exact count depends on Okio's internal buffering, but should be more than 1
        (progressCallCount > 1) shouldBe true
    }

    @Test
    fun `create empty directory succeeds`() = runTest {
        // Given
        mockOps.addMockDir("/source/emptyDir")
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/emptyDir")
        val destPath = LocalPath.build("/dest/emptyDir")
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
        mockOps.hasFile("/dest/emptyDir") shouldBe true
        mockOps.getFileType("/dest/emptyDir") shouldBe FileType.DIRECTORY
        (result as TransferStrategy.TransferResult.Success).bytesTransferred shouldBe 0L
    }

    @Test
    fun `create nested directory succeeds`() = runTest {
        // Given
        mockOps.addMockDir("/source/parent/child")
        mockOps.addMockDir("/dest/parent")

        val sourcePath = LocalPath.build("/source/parent/child")
        val destPath = LocalPath.build("/dest/parent/child")
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
        mockOps.hasFile("/dest/parent/child") shouldBe true
        mockOps.getFileType("/dest/parent/child") shouldBe FileType.DIRECTORY
    }

    // ============ PROGRESS REPORTING ============

    @Test
    fun `progress callback invoked during file transfer`() = runTest {
        // Given
        val content = "Progress test content".toByteArray()
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

        // Then - should have received at least one progress update
        progressUpdates.size shouldBe 1
        progressUpdates.sum() shouldBe content.size.toLong()
    }

    @Test
    fun `cumulative bytes reported correctly for large file`() = runTest {
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

        // Then - sum of all progress updates should equal total size
        progressUpdates.sum() shouldBe largeContent.size.toLong()
        // Should have multiple progress callbacks (exact count depends on Okio's buffering)
        (progressUpdates.size > 1) shouldBe true
    }

    @Test
    fun `progress callback invoked for small files`() = runTest {
        // Given - small file that fits in single buffer
        val smallContent = "Small".toByteArray()
        mockOps.addMockFile("/source/small.txt", smallContent)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/small.txt")
        val destPath = LocalPath.build("/dest/small.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        var progressCalled = false

        // When
        strategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = { progressCalled = true }
        )

        // Then - progress should be called even for small files
        progressCalled shouldBe true
    }

    // ============ ATTRIBUTE PRESERVATION ============

    @Test
    fun `modified time copied when preserveAttributes enabled`() = runTest {
        // Given
        val modifiedTime = kotlin.time.Instant.fromEpochMilliseconds(1234567890000)
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content, modifiedAt = modifiedTime)
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
            options = TransferStrategy.Options(preserveAttributes = true),
            onProgress = {}
        )

        // Then
        val destFile = mockOps.files["/dest/file.txt"]!!
        destFile.modifiedAt shouldBe modifiedTime
    }

    @Test
    fun `permissions copied when supported and preserveAttributes enabled`() = runTest {
        // Given
        val permissions = Permissions(mode = Integer.parseInt("755", 8))  // 755 octal = 493 decimal
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.files["/source/file.txt"] = mockOps.files["/source/file.txt"]!!.copy(permissions = permissions)
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
            options = TransferStrategy.Options(preserveAttributes = true),
            onProgress = {}
        )

        // Then
        val destFile = mockOps.files["/dest/file.txt"]!!
        destFile.permissions shouldBe permissions
    }

    @Test
    fun `ownership copied when supported and preserveAttributes enabled`() = runTest {
        // Given
        val ownership = Ownership(userId = 1000, groupId = 1000)
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content)
        mockOps.files["/source/file.txt"] = mockOps.files["/source/file.txt"]!!.copy(ownership = ownership)
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
            options = TransferStrategy.Options(preserveAttributes = true),
            onProgress = {}
        )

        // Then
        val destFile = mockOps.files["/dest/file.txt"]!!
        destFile.ownership shouldBe ownership
    }

    @Test
    fun `attributes not copied when preserveAttributes disabled`() = runTest {
        // Given
        val modifiedTime = kotlin.time.Instant.fromEpochMilliseconds(1234567890000)
        val permissions = Permissions(mode = Integer.parseInt("755", 8))  // 755 octal = 493 decimal
        val content = "Test".toByteArray()
        mockOps.addMockFile("/source/file.txt", content, modifiedAt = modifiedTime)
        mockOps.files["/source/file.txt"] = mockOps.files["/source/file.txt"]!!.copy(permissions = permissions)
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
            options = TransferStrategy.Options(preserveAttributes = false),
            onProgress = {}
        )

        // Then - attributes should be null (not copied)
        val destFile = mockOps.files["/dest/file.txt"]!!
        destFile.modifiedAt shouldBe null
        destFile.permissions shouldBe null
    }

    @Test
    fun `directory attributes preserved when requested`() = runTest {
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

    // ============ STREAM-BASED TRANSFER SPECIFICS ============

    @Test
    fun `content integrity verified for binary data`() = runTest {
        // Given - binary content with all byte values
        val binaryContent = ByteArray(256) { it.toByte() }
        mockOps.addMockFile("/source/binary.bin", binaryContent)
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/binary.bin")
        val destPath = LocalPath.build("/dest/binary.bin")
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

        // Then - every byte should match
        val destContent = mockOps.getFileContent("/dest/binary.bin")!!
        destContent shouldBe binaryContent
        destContent.size shouldBe binaryContent.size
        for (i in binaryContent.indices) {
            destContent[i] shouldBe binaryContent[i]
        }
    }

    @Test
    fun `multiple files can be copied sequentially`() = runTest {
        // Given
        mockOps.addMockFile("/source/file1.txt", "Content 1".toByteArray())
        mockOps.addMockFile("/source/file2.txt", "Content 2".toByteArray())
        mockOps.addMockDir("/dest")

        // When - copy both files
        val sourcePath1 = LocalPath.build("/source/file1.txt")
        val destPath1 = LocalPath.build("/dest/file1.txt")
        val sourceLookup1 = mockOps.lookup(sourcePath1)

        strategy.transferFile(
            sourceLookup = sourceLookup1,
            destination = destPath1,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        val sourcePath2 = LocalPath.build("/source/file2.txt")
        val destPath2 = LocalPath.build("/dest/file2.txt")
        val sourceLookup2 = mockOps.lookup(sourcePath2)

        strategy.transferFile(
            sourceLookup = sourceLookup2,
            destination = destPath2,
            sourceOps = mockOps,
            destOps = mockOps,
            options = TransferStrategy.Options(),
            onProgress = {}
        )

        // Then - both files should exist with correct content
        mockOps.getFileContent("/dest/file1.txt") shouldBe "Content 1".toByteArray()
        mockOps.getFileContent("/dest/file2.txt") shouldBe "Content 2".toByteArray()
    }

    // ============ ERROR HANDLING ============

    @Test
    fun `source file not found throws exception`() = runTest {
        // Given
        mockOps.addMockDir("/dest")

        val sourcePath = LocalPath.build("/source/nonexistent.txt")
        val destPath = LocalPath.build("/dest/nonexistent.txt")

        // When/Then
        shouldThrow<Exception> {
            val sourceLookup = mockOps.lookup(sourcePath)  // This should throw
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
    fun `destination parent not found throws exception`() = runTest {
        // Given
        mockOps.addMockFile("/source/file.txt", "Test".toByteArray())
        // Note: /dest does not exist

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest/file.txt")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When/Then - should fail because destination parent doesn't exist
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

    @Test
    fun `create directory succeeds even if already exists (idempotent)`() = runTest {
        // Given
        mockOps.addMockDir("/source/dir")
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/dir")  // Already exists

        val sourcePath = LocalPath.build("/source/dir")
        val destPath = LocalPath.build("/dest/dir")
        val sourceLookup = mockOps.lookup(sourcePath)

        // When - should not throw
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
    }

    @Test
    fun `transfer result contains correct source and destination paths`() = runTest {
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
}
