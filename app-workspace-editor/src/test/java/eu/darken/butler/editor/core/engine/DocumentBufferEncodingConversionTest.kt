package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.common.files.text.TextCharsets
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.nio.charset.Charset

class DocumentBufferEncodingConversionTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)
    private val gb18030 = TextCharsets.GB18030
    private val chunk = DocumentBuffer.CONVERT_CHUNK_BYTES

    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { canWrite(any()) } returns true
        coEvery { exists(any()) } coAnswers { fileSystemOps.exists(firstArg<APath<*>>() as LocalPath) }
        coEvery { existsStrict(any()) } coAnswers { fileSystemOps.existsStrict(firstArg<APath<*>>() as LocalPath) }
        @Suppress("UNCHECKED_CAST")
        coEvery { lookup(any(), any()) } coAnswers {
            fileSystemOps.lookup(firstArg<APath<*>>() as LocalPath, secondArg<LookupOptions>()) as APathLookup<APath<*>>
        }
        coEvery { file(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val readWrite = secondArg<Boolean>()
            if (readWrite && !fileSystemOps.exists(path)) path.file.createNewFile()
            fileSystemOps.file(path, readWrite)
        }
        coEvery { createFile(any(), any()) } coAnswers {
            (firstArg<APath<*>>() as LocalPath).file.createNewFile()
        }
        coEvery { delete(any<APath<*>>()) } coAnswers { fileSystemOps.delete(firstArg<APath<*>>() as LocalPath) }
        coEvery { move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            val renamed = (firstArg<APath<*>>() as LocalPath).file.renameTo((secondArg<APath<*>>() as LocalPath).file)
            if (renamed) MoveOutcome.Moved else MoveOutcome.NotSupported("rename failed")
        }
    }

    private suspend fun openBuffer(file: File, charsetOverride: Charset?, blockSize: Int = 7): DocumentBuffer {
        val dataSource = FileDataSource(
            workspaceId, LocalPath.build(file), createMockGateway(), charsetOverride,
        ).apply { open() }
        return DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = blockSize,
            assertions = true,
        ).apply { initialize().getOrThrow() }
    }

    private fun pos(offset: Int) = TextPosition(offset.toLong(), 0, offset)

    private val DocumentBuffer.fileSource get() = contentSource.value as ContentSource.File

    // Two-byte hanzi, a four-byte BMP character (ʘ U+0298) and a supplementary one in GB18030
    private val sample = "第一行你好ʘ😀世界\n第二行😀ʘ结束\n"

    /** Unsaved edits next to every sequence length; returns the expected document text. */
    private suspend fun DocumentBuffer.applySampleEdits(): String {
        var expected = sample
        val afterBmp = expected.indexOf('ʘ') + 1
        insertText(pos(afterBmp), "插入😀").getOrThrow()
        expected = expected.substring(0, afterBmp) + "插入😀" + expected.substring(afterBmp)
        deleteText(pos(3), pos(5)).getOrThrow()
        expected = expected.substring(0, 3) + expected.substring(5)
        getFullText().getOrThrow() shouldBe expected
        return expected
    }

    @Test
    fun `GB18030 converts to UTF-8 with unsaved edits`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "gb.txt").apply { writeBytes(sample.toByteArray(gb18030)) }
        val buffer = openBuffer(file, gb18030)
        val expected = buffer.applySampleEdits()

        buffer.convertEncoding(Charsets.UTF_8).getOrThrow()

        file.readBytes() shouldBe expected.toByteArray(Charsets.UTF_8)
        openBuffer(file, Charsets.UTF_8).getFullText().getOrThrow() shouldBe expected
    }

    @Test
    fun `UTF-8 converts to GB18030 with unsaved edits`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "utf8.txt").apply { writeBytes(sample.toByteArray(Charsets.UTF_8)) }
        val buffer = openBuffer(file, Charsets.UTF_8)
        val expected = buffer.applySampleEdits()

        buffer.convertEncoding(gb18030).getOrThrow()

        file.readBytes() shouldBe expected.toByteArray(gb18030)
        openBuffer(file, gb18030).getFullText().getOrThrow() shouldBe expected
    }

    @Test
    fun `the source BOM is not copied`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = File(tempDir, "bom.txt").apply { writeBytes(bom + sample.toByteArray(Charsets.UTF_8)) }
        val buffer = openBuffer(file, charsetOverride = null)
        buffer.fileSource.hasBOM.shouldBeTrue()

        buffer.convertEncoding(gb18030).getOrThrow()

        file.readBytes() shouldBe sample.toByteArray(gb18030)
    }

    @Test
    fun `UTF-16 targets start with their BOM and reopen with identical text`(@TempDir tempDir: File) = runTest {
        val text = "Hello 世界 😀\r\nline2"
        val file = File(tempDir, "doc.txt")

        file.writeBytes(text.toByteArray(Charsets.UTF_8))
        openBuffer(file, Charsets.UTF_8).convertEncoding(Charsets.UTF_16LE).getOrThrow()
        file.readBytes() shouldBe utf16LeBom + text.toByteArray(Charsets.UTF_16LE)
        openBuffer(file, charsetOverride = null).apply {
            fileSource.detectedCharset shouldBe Charsets.UTF_16LE
            getFullText().getOrThrow() shouldBe text
        }

        file.writeBytes(text.toByteArray(Charsets.UTF_8))
        openBuffer(file, Charsets.UTF_8).convertEncoding(Charsets.UTF_16BE).getOrThrow()
        file.readBytes() shouldBe utf16BeBom + text.toByteArray(Charsets.UTF_16BE)
        openBuffer(file, charsetOverride = null).apply {
            fileSource.detectedCharset shouldBe Charsets.UTF_16BE
            getFullText().getOrThrow() shouldBe text
        }
    }

    @Test
    fun `a converted buffer refuses further reads and edits`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeBytes(sample.toByteArray(gb18030)) }
        val buffer = openBuffer(file, gb18030)
        buffer.insertText(pos(0), "X").getOrThrow()

        buffer.convertEncoding(Charsets.UTF_8).getOrThrow()

        buffer.isRetired.value.shouldBeTrue()
        buffer.isModified.value.shouldBeFalse()
        buffer.getFullText().isFailure.shouldBeTrue()
        buffer.insertText(pos(0), "Y").isFailure.shouldBeTrue()
        buffer.saveFile().isFailure.shouldBeTrue()
        file.readBytes() shouldBe ("X$sample").toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `converting to the current charset is a no-op`(@TempDir tempDir: File) = runTest {
        val bytes = sample.toByteArray(gb18030)
        val file = File(tempDir, "doc.txt").apply { writeBytes(bytes) }
        val buffer = openBuffer(file, gb18030)

        buffer.convertEncoding(gb18030).getOrThrow()

        buffer.isRetired.value.shouldBeFalse()
        file.readBytes() shouldBe bytes
    }

    @Test
    fun `characters the target can't represent refuse the conversion`(@TempDir tempDir: File) = runTest {
        val bytes = sample.toByteArray(gb18030)
        val file = File(tempDir, "doc.txt").apply { writeBytes(bytes) }
        val buffer = openBuffer(file, gb18030)

        val error = buffer.convertEncoding(Charsets.ISO_8859_1).exceptionOrNull()

        error.shouldBeInstanceOf<UnmappableCharactersException>().charset shouldBe Charsets.ISO_8859_1
        file.readBytes() shouldBe bytes
        // Nothing was written, so the document stays editable and saveable
        buffer.isRetired.value.shouldBeFalse()
        buffer.insertText(pos(0), "X").getOrThrow()
        buffer.saveFile().getOrThrow()
        file.readBytes() shouldBe ("X$sample").toByteArray(gb18030)
    }

    @Test
    fun `a lone surrogate in added text refuses the conversion`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("abc") }
        val buffer = openBuffer(file, Charsets.UTF_8)
        buffer.insertText(pos(1), "\uD83D").getOrThrow()

        val error = buffer.convertEncoding(Charsets.UTF_16LE).exceptionOrNull()

        error.shouldBeInstanceOf<UnmappableCharactersException>().charset shouldBe Charsets.UTF_16LE
        file.readText() shouldBe "abc"
        buffer.isRetired.value.shouldBeFalse()
    }

    @Test
    fun `a surrogate pair split across added pieces converts as one character`(@TempDir tempDir: File) =
        runTest {
            val file = File(tempDir, "doc.txt").apply { writeText("ab") }
            val buffer = openBuffer(file, Charsets.UTF_8)
            // Low half first, then the high half before it: two Added pieces that can't merge
            buffer.insertText(pos(1), "\uDE00").getOrThrow()
            buffer.insertText(pos(1), "\uD83D").getOrThrow()
            buffer.getFullText().getOrThrow() shouldBe "a😀b"

            buffer.convertEncoding(gb18030).getOrThrow()

            file.readBytes() shouldBe "a😀b".toByteArray(gb18030)
        }

    @Test
    fun `a surrogate pair split by the added slice boundary converts as one character`(@TempDir tempDir: File) =
        runTest {
            val file = File(tempDir, "doc.txt").apply { writeText("tail") }
            val buffer = openBuffer(file, Charsets.UTF_8, blockSize = 4096)
            // The slice ends after the high half
            val inserted = "x".repeat(chunk - 1) + "😀" + "y"
            buffer.insertText(pos(0), inserted).getOrThrow()

            buffer.convertEncoding(Charsets.UTF_16BE).getOrThrow()

            file.readBytes() shouldBe utf16BeBom + "${inserted}tail".toByteArray(Charsets.UTF_16BE)
        }

    @Test
    fun `a character split by the original read chunk boundary converts correctly`(@TempDir tempDir: File) =
        runTest {
            // The emoji's four UTF-8 bytes straddle the first read chunk's end
            val text = "a".repeat(chunk - 2) + "😀" + "tail"
            val file = File(tempDir, "doc.txt").apply { writeBytes(text.toByteArray(Charsets.UTF_8)) }
            val buffer = openBuffer(file, Charsets.UTF_8, blockSize = 4096)

            buffer.convertEncoding(gb18030).getOrThrow()

            file.readBytes() shouldBe text.toByteArray(gb18030)
        }

    @Test
    fun `documents several chunks long convert exactly`(@TempDir tempDir: File) = runTest {
        val line = "Zeile 中文 ʘ 😀 é\n"
        val lineCount = 5 * chunk / line.toByteArray(Charsets.UTF_8).size + 7
        val original = line.repeat(lineCount)
        val file = File(tempDir, "big.txt").apply { writeBytes(original.toByteArray(Charsets.UTF_8)) }
        val buffer = openBuffer(file, Charsets.UTF_8, blockSize = 4096)
        val added = "插入😀".repeat(chunk)
        // A line start, so the insert can't land between the halves of a surrogate pair
        val middle = line.length * (lineCount / 2)
        buffer.insertText(pos(middle), added).getOrThrow()
        buffer.deleteText(pos(10), pos(20)).getOrThrow()
        val expected = original.substring(0, 10) + original.substring(20, middle) + added + original.substring(middle)
        buffer.getFullText().getOrThrow() shouldBe expected
        (original.toByteArray(Charsets.UTF_8).size > 5 * chunk).shouldBeTrue()

        buffer.convertEncoding(Charsets.UTF_16LE).getOrThrow()

        file.readBytes() shouldBe utf16LeBom + expected.toByteArray(Charsets.UTF_16LE)
    }
}
