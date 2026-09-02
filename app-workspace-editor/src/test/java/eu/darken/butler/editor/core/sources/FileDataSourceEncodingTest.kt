package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okio.buffer
import okio.use
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import kotlin.uuid.Uuid

/**
 * Tests for encoding detection and preservation in FileDataSource.
 *
 * Tests verify:
 * - BOM detection (UTF-8, UTF-16 LE/BE)
 * - UTF-8 validation for non-BOM files
 * - BOM preservation on commit
 * - Encoding preservation on commit
 * - Round-trip integrity
 */
class FileDataSourceEncodingTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { canWrite(any()) } returns true
        coEvery { exists(any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            fileSystemOps.exists(path)
        }
        coEvery { existsStrict(any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            fileSystemOps.existsStrict(path)
        }

        @Suppress("UNCHECKED_CAST")
        coEvery { lookup(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val options = secondArg<LookupOptions>()
            fileSystemOps.lookup(path, options) as APathLookup<APath<*>>
        }

        coEvery { file(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val readWrite = secondArg<Boolean>()
            if (readWrite && !fileSystemOps.exists(path)) {
                path.file.createNewFile()
            }
            fileSystemOps.file(path, readWrite)
        }

        coEvery { delete(any<APath<*>>()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            fileSystemOps.delete(path)
        }

        coEvery { move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            val source = firstArg<APath<*>>() as LocalPath
            val dest = secondArg<APath<*>>() as LocalPath
            if (source.file.renameTo(dest.file)) MoveOutcome.Moved else MoveOutcome.NotSupported("rename failed")
        }
    }

    // ==================== BOM Detection Tests ====================

    @Test
    fun `detect UTF-8 BOM`(@TempDir tempDir: File) = runTest {
        // Given: UTF-8 file with BOM (EF BB BF)
        val testFile = File(tempDir, "test.txt")
        testFile.writeBytes(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hello UTF-8".toByteArray(
                Charsets.UTF_8
            )
        )

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        // When: Open file
        dataSource.open()

        // Then: Should detect UTF-8 with BOM
        val contentSource = dataSource.contentSource.value as ContentSource.File
        contentSource.detectedCharset shouldBe Charsets.UTF_8
        contentSource.hasBOM shouldBe true
        contentSource.bomBytes shouldBe byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }

    @Test
    fun `detect UTF-8 without BOM via validation`(@TempDir tempDir: File) = runTest {
        // Given: UTF-8 file without BOM
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("Hello UTF-8 without BOM", Charsets.UTF_8)

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        // When: Open file
        dataSource.open()

        // Then: Should detect UTF-8 via validation (no BOM)
        val contentSource = dataSource.contentSource.value as ContentSource.File
        contentSource.detectedCharset shouldBe Charsets.UTF_8
        contentSource.hasBOM shouldBe false
        contentSource.bomBytes shouldBe null
    }

    @Test
    fun `detect UTF-16 LE with BOM`(@TempDir tempDir: File) = runTest {
        // Given: UTF-16 LE file with BOM (FF FE)
        val testFile = File(tempDir, "test.txt")
        val bomBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val contentBytes = "Hello UTF-16 LE".toByteArray(Charsets.UTF_16LE)
        testFile.writeBytes(bomBytes + contentBytes)

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        // When: Open file
        dataSource.open()

        // Then: Should detect UTF-16 LE with BOM
        val contentSource = dataSource.contentSource.value as ContentSource.File
        contentSource.detectedCharset shouldBe Charsets.UTF_16LE
        contentSource.hasBOM shouldBe true
        contentSource.bomBytes shouldBe bomBytes
    }

    @Test
    fun `detect UTF-16 BE with BOM`(@TempDir tempDir: File) = runTest {
        // Given: UTF-16 BE file with BOM (FE FF)
        val testFile = File(tempDir, "test.txt")
        val bomBytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val contentBytes = "Hello UTF-16 BE".toByteArray(Charsets.UTF_16BE)
        testFile.writeBytes(bomBytes + contentBytes)

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        // When: Open file
        dataSource.open()

        // Then: Should detect UTF-16 BE with BOM
        val contentSource = dataSource.contentSource.value as ContentSource.File
        contentSource.detectedCharset shouldBe Charsets.UTF_16BE
        contentSource.hasBOM shouldBe true
        contentSource.bomBytes shouldBe bomBytes
    }

    @Test
    fun `default to UTF-8 for empty file`(@TempDir tempDir: File) = runTest {
        // Given: Empty file
        val testFile = File(tempDir, "empty.txt")
        testFile.createNewFile()

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        // When: Open file
        dataSource.open()

        // Then: Should default to UTF-8
        val contentSource = dataSource.contentSource.value as ContentSource.File
        contentSource.detectedCharset shouldBe Charsets.UTF_8
        contentSource.hasBOM shouldBe false
    }

    // ==================== BOM Preservation Tests ====================

    @Test
    fun `preserve UTF-8 BOM on save`(@TempDir tempDir: File) = runTest {
        // Given: UTF-8 file with BOM
        val testFile = File(tempDir, "test.txt")
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        testFile.writeBytes(bomBytes + "Original content".toByteArray(Charsets.UTF_8))

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        dataSource.open()

        // When: Commit modified content, writing the detected BOM first
        dataSource.commit { context ->
            context.sink.write(bomBytes)
            context.sink.write("Modified content".toByteArray(Charsets.UTF_8))
        }

        // Then: BOM should be preserved
        val savedBytes = testFile.readBytes()
        savedBytes.take(3).toByteArray() shouldBe bomBytes

        // And: Content is correct (after BOM)
        String(savedBytes.drop(3).toByteArray(), Charsets.UTF_8) shouldBe "Modified content"
    }

    @Test
    fun `preserve UTF-16 LE BOM on save`(@TempDir tempDir: File) = runTest {
        // Given: UTF-16 LE file with BOM
        val testFile = File(tempDir, "test.txt")
        val bomBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        testFile.writeBytes(bomBytes + "Original".toByteArray(Charsets.UTF_16LE))

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        dataSource.open()

        // When: Commit modified content in the detected encoding
        dataSource.commit { context ->
            context.sink.write(bomBytes)
            context.sink.write("Modified".toByteArray(Charsets.UTF_16LE))
        }

        // Then: BOM should be preserved
        val savedBytes = testFile.readBytes()
        savedBytes.take(2).toByteArray() shouldBe bomBytes
    }

    @Test
    fun `do not add BOM if original had none`(@TempDir tempDir: File) = runTest {
        // Given: UTF-8 file WITHOUT BOM
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("Original content", Charsets.UTF_8)

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        dataSource.open()

        // When: Commit modified content without a BOM
        dataSource.commit { context ->
            context.sink.write("Modified content".toByteArray(Charsets.UTF_8))
        }

        // Then: Should NOT have BOM
        val savedBytes = testFile.readBytes()
        savedBytes.take(3).toByteArray() shouldNotBe byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        // And: Content starts immediately (no BOM)
        String(savedBytes, Charsets.UTF_8) shouldBe "Modified content"
    }

    // ==================== Encoding Preservation Tests ====================

    @Test
    fun `preserve UTF-8 encoding on save`(@TempDir tempDir: File) = runTest {
        // Given: UTF-8 file
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("Hello with emoji 🎉", Charsets.UTF_8)

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        dataSource.open()

        // When: Commit modified content
        dataSource.commit { context ->
            context.sink.write("Modified with emoji 🚀".toByteArray(Charsets.UTF_8))
        }

        // Then: Should still be UTF-8
        val savedContent = testFile.readText(Charsets.UTF_8)
        savedContent shouldBe "Modified with emoji 🚀"
    }

    @Test
    fun `round-trip UTF-8 with BOM preserves encoding and BOM`(@TempDir tempDir: File) = runTest {
        // Given: UTF-8 file with BOM
        val testFile = File(tempDir, "test.txt")
        val originalBOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        testFile.writeBytes(originalBOM + "Line 1\nLine 2\nLine 3".toByteArray(Charsets.UTF_8))

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        dataSource.open()

        // When: Read (past the BOM), modify, and commit
        val originalContent = dataSource.openByteSource(3L).buffer().use { it.readByteArray() }
            .toString(Charsets.UTF_8)

        dataSource.commit { context ->
            context.sink.write(originalBOM)
            context.sink.write((originalContent + "\nLine 4").toByteArray(Charsets.UTF_8))
        }

        // Then: BOM should be preserved
        val savedBytes = testFile.readBytes()
        savedBytes.take(3).toByteArray() shouldBe originalBOM

        // And: Content is correct
        String(savedBytes.drop(3).toByteArray(), Charsets.UTF_8) shouldBe "Line 1\nLine 2\nLine 3\nLine 4"
    }

    // ==================== Charset Override Tests ====================

    @Test
    fun `override skips detection and decodes single-byte content`(@TempDir tempDir: File) = runTest {
        // "café" in ISO-8859-1: é = 0xE9, invalid as UTF-8 (detection would fall back to UTF-8)
        val testFile = File(tempDir, "latin.txt")
        testFile.writeBytes(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()))

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway(),
            charsetOverride = Charsets.ISO_8859_1,
        )
        dataSource.open()

        val contentSource = dataSource.contentSource.value as ContentSource.File
        contentSource.detectedCharset shouldBe Charsets.ISO_8859_1
        contentSource.hasBOM shouldBe false
        contentSource.bomBytes shouldBe null
    }

    @Test
    fun `override matching the on-disk BOM family strips the BOM`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt")
        val bomBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        testFile.writeBytes(bomBytes + "Hello".toByteArray(Charsets.UTF_16LE))

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway(),
            charsetOverride = Charsets.UTF_16LE,
        )
        dataSource.open()

        val contentSource = dataSource.contentSource.value as ContentSource.File
        contentSource.detectedCharset shouldBe Charsets.UTF_16LE
        contentSource.hasBOM shouldBe true
        contentSource.bomBytes shouldBe bomBytes
    }

    @Test
    fun `override from a different family treats a BOM as content`(@TempDir tempDir: File) = runTest {
        // UTF-8 BOM on disk, but the file is reopened as ISO-8859-1: the BOM bytes are content
        val testFile = File(tempDir, "test.txt")
        testFile.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hello".toByteArray())

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway(),
            charsetOverride = Charsets.ISO_8859_1,
        )
        dataSource.open()

        val contentSource = dataSource.contentSource.value as ContentSource.File
        contentSource.detectedCharset shouldBe Charsets.ISO_8859_1
        contentSource.hasBOM shouldBe false
        contentSource.bomBytes shouldBe null
    }

    @Test
    fun `round-trip UTF-16 LE preserves encoding and BOM`(@TempDir tempDir: File) = runTest {
        // Given: UTF-16 LE file with BOM
        val testFile = File(tempDir, "test.txt")
        val bomBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        testFile.writeBytes(bomBytes + "Original".toByteArray(Charsets.UTF_16LE))

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        dataSource.open()

        // Verify detection
        (dataSource.contentSource.value as ContentSource.File).detectedCharset shouldBe Charsets.UTF_16LE

        // When: Commit modified content in the detected encoding
        dataSource.commit { context ->
            context.sink.write(bomBytes)
            context.sink.write("Modified".toByteArray(Charsets.UTF_16LE))
        }

        // Then: Should still be UTF-16 LE with BOM
        val savedBytes = testFile.readBytes()
        savedBytes.take(2).toByteArray() shouldBe bomBytes

        // And: Content can be decoded as UTF-16 LE
        String(savedBytes.drop(2).toByteArray(), Charsets.UTF_16LE) shouldBe "Modified"
    }
}
