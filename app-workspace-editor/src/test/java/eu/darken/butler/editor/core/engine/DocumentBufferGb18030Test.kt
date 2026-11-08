package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.common.files.text.Gb18030
import eu.darken.butler.common.files.text.TextCharsets
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.nio.charset.Charset

class DocumentBufferGb18030Test : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)
    private val gb18030 = TextCharsets.GB18030

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
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = blockSize,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        return buffer
    }

    private fun pos(offset: Int) = TextPosition(offset.toLong(), 0, offset)

    private val DocumentBuffer.fileSource get() = contentSource.value as ContentSource.File

    private fun isValidGb18030(bytes: ByteArray): Boolean {
        var index = 0
        while (index < bytes.size) {
            index += Gb18030.sequenceLength(bytes, index).takeIf { it > 0 } ?: return false
        }
        return true
    }

    @Test
    fun `edits next to two and four byte sequences save as GB18030`(@TempDir tempDir: File) = runTest {
        val original = "第一行你好ʘ😀世界\n第二行😀ʘ结束\n"
        val file = File(tempDir, "gb.txt").apply { writeBytes(original.toByteArray(gb18030)) }

        for (blockSize in listOf(4, 5, 7, 11)) {
            file.writeBytes(original.toByteArray(gb18030))
            val buffer = openBuffer(file, gb18030, blockSize)
            buffer.getFullText().getOrThrow() shouldBe original
            buffer.fileSource.hasUndecodableBytes.shouldBeFalse()

            var expected = original
            fun expectEdit(offset: Int, deleteLength: Int, insert: String) {
                expected = expected.substring(0, offset) + insert + expected.substring(offset + deleteLength)
            }

            // Between ʘ and 😀
            val afterBmp = expected.indexOf('ʘ') + 1
            buffer.insertText(pos(afterBmp), "插入").getOrThrow()
            expectEdit(afterBmp, 0, "插入")
            // The 😀 on the second line
            val emoji = expected.lastIndexOf("😀")
            buffer.deleteText(pos(emoji), pos(emoji + 2)).getOrThrow()
            expectEdit(emoji, 2, "")
            // Inside the first line's hanzi
            buffer.deleteText(pos(3), pos(4)).getOrThrow()
            expectEdit(3, 1, "")
            buffer.insertText(pos(3), "x€").getOrThrow()
            expectEdit(3, 0, "x€")

            buffer.getFullText().getOrThrow() shouldBe expected
            buffer.saveFile().getOrThrow()

            val saved = file.readBytes()
            saved shouldBe expected.toByteArray(gb18030)
            isValidGb18030(saved).shouldBeTrue()
            // The untouched tail keeps its original bytes
            val tail = "ʘ结束\n".toByteArray(gb18030)
            saved.copyOfRange(saved.size - tail.size, saved.size) shouldBe tail

            buffer.getFullText().getOrThrow() shouldBe expected
            openBuffer(file, gb18030, blockSize).getFullText().getOrThrow() shouldBe expected
        }
    }

    @Test
    fun `insert between the halves of a supplementary character saves replacements`(@TempDir tempDir: File) =
        runTest {
            val file = File(tempDir, "gb.txt").apply { writeBytes("a😀b".toByteArray(gb18030)) }
            val buffer = openBuffer(file, gb18030, blockSize = 4)

            buffer.insertText(pos(2), "X").getOrThrow()
            buffer.saveFile().getOrThrow()

            val replacement = byteArrayOf(0x84.toByte(), 0x31, 0xA4.toByte(), 0x37)
            file.readBytes() shouldBe byteArrayOf(0x61) + replacement + byteArrayOf(0x58) + replacement +
                byteArrayOf(0x62)
            String(replacement, gb18030) shouldBe "�"
        }

    @Test
    fun `GB18030 file with invalid bytes is read-only`(@TempDir tempDir: File) = runTest {
        val bytes = "你好".toByteArray(gb18030) + byteArrayOf(0x81.toByte(), 0x20) + "世界\r\n结束".toByteArray(gb18030)
        val file = File(tempDir, "broken.txt").apply { writeBytes(bytes) }
        val buffer = openBuffer(file, gb18030, blockSize = 4)

        buffer.fileSource.hasUndecodableBytes.shouldBeTrue()
        buffer.fileSource.isUndecodableReadOnly.shouldBeTrue()

        // The buffer itself does not gate edits (the engine does), so this reaches the save gate
        buffer.insertText(pos(0), "X").getOrThrow()
        val saveError = buffer.saveFile().exceptionOrNull()
        saveError.shouldBeInstanceOf<ReadOnlyFileException>()
        saveError.message!! shouldContain "GB18030"

        buffer.convertLineEndings(LineEnding.LF).exceptionOrNull().shouldBeInstanceOf<ReadOnlyFileException>()
        file.readBytes() shouldBe bytes
    }

    @Test
    fun `UTF-8 file with invalid bytes is flagged but stays editable`(@TempDir tempDir: File) = runTest {
        val bytes = "head ".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + " tail".toByteArray()
        val file = File(tempDir, "broken-utf8.txt").apply { writeBytes(bytes) }
        val buffer = openBuffer(file, charsetOverride = null, blockSize = 4)

        buffer.fileSource.detectedCharset shouldBe Charsets.UTF_8
        buffer.fileSource.hasUndecodableBytes.shouldBeTrue()
        buffer.fileSource.isUndecodableReadOnly.shouldBeFalse()

        buffer.insertText(pos(0), "X").getOrThrow()
        buffer.saveFile().getOrThrow()
        file.readBytes() shouldBe "X".toByteArray() + bytes
        // Refreshed by the post-save rebase
        buffer.fileSource.hasUndecodableBytes.shouldBeTrue()
    }

    @Test
    fun `undecodable flag clears after reopening with a fitting encoding`(@TempDir tempDir: File) = runTest {
        // 0x80 is a C1 control in ISO-8859-1 and no GB18030 sequence at all
        val bytes = byteArrayOf(0x61, 0x80.toByte(), 0x62)
        val file = File(tempDir, "latin1.txt").apply { writeBytes(bytes) }

        openBuffer(file, gb18030).fileSource.isUndecodableReadOnly.shouldBeTrue()
        openBuffer(file, Charsets.ISO_8859_1).fileSource.hasUndecodableBytes.shouldBeFalse()
    }
}
