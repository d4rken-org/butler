package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.engine.ChunkBoundary
import eu.darken.butler.editor.core.engine.TextChunk
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
 * - BOM preservation on save
 * - Encoding preservation on save
 * - Round-trip integrity
 */
class FileDataSourceEncodingTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { exists(any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            fileSystemOps.exists(path)
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
            source.file.renameTo(dest.file)
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
        val fileInfo = dataSource.fileInfo.value
        fileInfo shouldNotBe null
        fileInfo!!.detectedCharset shouldBe Charsets.UTF_8
        fileInfo.hasBOM shouldBe true
        fileInfo.bomBytes shouldBe byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
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
        val fileInfo = dataSource.fileInfo.value
        fileInfo shouldNotBe null
        fileInfo!!.detectedCharset shouldBe Charsets.UTF_8
        fileInfo.hasBOM shouldBe false
        fileInfo.bomBytes shouldBe null
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
        val fileInfo = dataSource.fileInfo.value
        fileInfo shouldNotBe null
        fileInfo!!.detectedCharset shouldBe Charsets.UTF_16LE
        fileInfo.hasBOM shouldBe true
        fileInfo.bomBytes shouldBe bomBytes
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
        val fileInfo = dataSource.fileInfo.value
        fileInfo shouldNotBe null
        fileInfo!!.detectedCharset shouldBe Charsets.UTF_16BE
        fileInfo.hasBOM shouldBe true
        fileInfo.bomBytes shouldBe bomBytes
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
        val fileInfo = dataSource.fileInfo.value
        fileInfo shouldNotBe null
        fileInfo!!.detectedCharset shouldBe Charsets.UTF_8
        fileInfo.hasBOM shouldBe false
    }

    // ==================== BOM Stripping Tests ====================

    @Test
    fun `strip BOM from first chunk`(@TempDir tempDir: File) = runTest {
        // Given: UTF-8 file with BOM
        val testFile = File(tempDir, "test.txt")
        testFile.writeBytes(
            byteArrayOf(
                0xEF.toByte(),
                0xBB.toByte(),
                0xBF.toByte()
            ) + "Hello".toByteArray(Charsets.UTF_8)
        )

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        dataSource.open()

        // When: Read first chunk
        val content = dataSource.readChunk(0, 100)

        // Then: BOM should be stripped
        content shouldBe "Hello"
        content.startsWith("\uFEFF") shouldBe false // No BOM character
    }

    @Test
    fun `do not strip BOM from non-first chunk`(@TempDir tempDir: File) = runTest {
        // Given: File with BOM at start
        val testFile = File(tempDir, "test.txt")
        testFile.writeBytes(
            byteArrayOf(
                0xEF.toByte(),
                0xBB.toByte(),
                0xBF.toByte()
            ) + "0123456789".toByteArray(Charsets.UTF_8)
        )

        val dataSource = FileDataSource(
            workspaceId = workspaceId,
            filePath = LocalPath.build(testFile),
            gatewaySwitch = createMockGateway()
        )

        dataSource.open()

        // When: Read chunk starting after BOM
        val content = dataSource.readChunk(5, 10) // Offset 5 is past the 3-byte BOM

        // Then: Should just read content (BOM already skipped in file)
        content.length shouldBe 8 // "23456789"
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

        // When: Modify and save
        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified content",
            lineCount = 1,
            lineEnding = eu.darken.butler.editor.core.engine.LineEnding.LF,
            isDirty = true,
            isLoaded = true
        )

        val boundaries = mapOf(
            chunk.id to ChunkBoundary(startOffset = 0, endOffset = 16, lineCount = 1) // BOM is handled separately
        )

        dataSource.save(listOf(chunk), boundaries)

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

        // When: Save with modification
        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified",
            lineCount = 1,
            lineEnding = eu.darken.butler.editor.core.engine.LineEnding.LF,
            isDirty = true,
            isLoaded = true
        )

        val boundaries = mapOf(
            chunk.id to ChunkBoundary(startOffset = 0, endOffset = 16, lineCount = 1) // BOM is handled separately
        )

        dataSource.save(listOf(chunk), boundaries)

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

        // When: Save with modification
        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified content",
            lineCount = 1,
            lineEnding = eu.darken.butler.editor.core.engine.LineEnding.LF,
            isDirty = true,
            isLoaded = true
        )

        val boundaries = mapOf(
            chunk.id to ChunkBoundary(startOffset = 0, endOffset = 16, lineCount = 1)
        )

        dataSource.save(listOf(chunk), boundaries)

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

        // When: Modify and save
        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified with emoji 🚀",
            lineCount = 1,
            lineEnding = eu.darken.butler.editor.core.engine.LineEnding.LF,
            isDirty = true,
            isLoaded = true
        )

        val boundaries = mapOf(
            chunk.id to ChunkBoundary(startOffset = 0, endOffset = 21, lineCount = 1)
        )

        dataSource.save(listOf(chunk), boundaries)

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

        // When: Read, modify, and save
        val originalContent = dataSource.readChunk(0, 100)

        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = originalContent + "\nLine 4",
            lineCount = 4,
            lineEnding = eu.darken.butler.editor.core.engine.LineEnding.LF,
            isDirty = true,
            isLoaded = true
        )

        val boundaries = mapOf(
            chunk.id to ChunkBoundary(startOffset = 0, endOffset = 22, lineCount = 3) // BOM is handled separately
        )

        dataSource.save(listOf(chunk), boundaries)

        // Then: BOM should be preserved
        val savedBytes = testFile.readBytes()
        savedBytes.take(3).toByteArray() shouldBe originalBOM

        // And: Content is correct
        String(savedBytes.drop(3).toByteArray(), Charsets.UTF_8) shouldBe "Line 1\nLine 2\nLine 3\nLine 4"
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
        dataSource.fileInfo.value!!.detectedCharset shouldBe Charsets.UTF_16LE

        // When: Modify and save
        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified",
            lineCount = 1,
            lineEnding = eu.darken.butler.editor.core.engine.LineEnding.LF,
            isDirty = true,
            isLoaded = true
        )

        val boundaries = mapOf(
            chunk.id to ChunkBoundary(startOffset = 0, endOffset = 16, lineCount = 1) // BOM is handled separately
        )

        dataSource.save(listOf(chunk), boundaries)

        // Then: Should still be UTF-16 LE with BOM
        val savedBytes = testFile.readBytes()
        savedBytes.take(2).toByteArray() shouldBe bomBytes

        // And: Content can be decoded as UTF-16 LE
        String(savedBytes.drop(2).toByteArray(), Charsets.UTF_16LE) shouldBe "Modified"
    }
}
