package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File

/**
 * Multibyte regression tests on a real (byte-addressed) file source, ported from the retired
 * dual-offset worktree. The old chunk engine conflated file byte offsets with UTF-16 char
 * offsets; ASCII hid it (byte==char). Small block sizes force block edges inside multibyte runs.
 */
class DocumentBufferMultibyteTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { canWrite(any()) } returns true
        coEvery { exists(any()) } coAnswers { fileSystemOps.exists(firstArg<APath<*>>() as LocalPath) }
        @Suppress("UNCHECKED_CAST")
        coEvery { lookup(any(), any()) } coAnswers {
            fileSystemOps.lookup(firstArg<APath<*>>() as LocalPath, secondArg<LookupOptions>()) as APathLookup<APath<*>>
        }
        coEvery { file(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val rw = secondArg<Boolean>()
            if (rw && !fileSystemOps.exists(path)) path.file.createNewFile()
            fileSystemOps.file(path, rw)
        }
        coEvery { delete(any<APath<*>>()) } coAnswers { fileSystemOps.delete(firstArg<APath<*>>() as LocalPath) }
        coEvery { move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            (firstArg<APath<*>>() as LocalPath).file.renameTo((secondArg<APath<*>>() as LocalPath).file)
        }
    }

    private suspend fun fileBuffer(tempDir: File, bytes: ByteArray, blockSize: Int): DocumentBuffer {
        val file = File(tempDir, "mb.txt").apply { writeBytes(bytes) }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(file), createMockGateway()).apply { open() }
        return DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = blockSize,
            assertions = true,
        ).apply { initialize().getOrThrow() }
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    // ── reads ───────────────────────────────────────────────────────────────────

    @Test
    fun `getText over the whole document does not overrun on CJK content`(@TempDir tempDir: File) = runTest {
        val content = "中文a中文b中"   // CJK = 3 bytes each
        val buffer = fileBuffer(tempDir, bytes(content), blockSize = 1024)

        buffer.totalLength.value shouldBe content.length.toLong()
        buffer.getText(0, buffer.totalLength.value).getOrThrow() shouldBe content
    }

    @Test
    fun `getFullText equals content across a block boundary splitting a 3-byte char`(@TempDir tempDir: File) =
        runTest {
            // "中" is 3 bytes (E4 B8 AD). blockSize 8 forces an edge inside a CJK char.
            val content = "中文中文中文"
            val buffer = fileBuffer(tempDir, bytes(content), blockSize = 8)
            buffer.getFullText().getOrThrow() shouldBe content
        }

    @Test
    fun `findOffset and findPosition round-trip on multibyte content`(@TempDir tempDir: File) = runTest {
        val content = "aé中\nx中é"     // mix 1/2/3-byte + newline
        val buffer = fileBuffer(tempDir, bytes(content), blockSize = 1024)

        // char offset of the '中' on line 1 (after "x") == 5 ("aé中\nx" = a,é,中,\n,x => index 5)
        val offset = buffer.findOffset(line = 1, column = 1)
        offset shouldBe 5L
        val pos = buffer.findPosition(5L)
        pos.line shouldBe 1L
        pos.column shouldBe 1
    }

    // ── edits ───────────────────────────────────────────────────────────────────

    @Test
    fun `insert after a multibyte char lands at the right place`(@TempDir tempDir: File) = runTest {
        val content = "中文"
        val buffer = fileBuffer(tempDir, bytes(content), blockSize = 1024)
        buffer.insertText(TextPosition(offset = 1, line = 0, column = 1), "X").getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "中X文"
    }

    @Test
    fun `delete a multibyte char by char range`(@TempDir tempDir: File) = runTest {
        val content = "a中b"
        val buffer = fileBuffer(tempDir, bytes(content), blockSize = 1024)
        buffer.deleteText(
            TextPosition(offset = 1, line = 0, column = 1),
            TextPosition(offset = 2, line = 0, column = 2),
        ).getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "ab"
    }

    // ── BOM + CRLF + multibyte ────────────────────────────────────────────────────

    @Test
    fun `BOM plus multibyte plus CRLF reads correctly`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val content = "中文\r\n日本"
        val buffer = fileBuffer(tempDir, bom + bytes(content), blockSize = 1024)

        buffer.totalLength.value shouldBe content.length.toLong()
        buffer.getText(0, buffer.totalLength.value).getOrThrow() shouldBe content
    }

    // ── search ───────────────────────────────────────────────────────────────────

    @Test
    fun `search offsets are char based on multibyte content`(@TempDir tempDir: File) = runTest {
        val content = "中文 abc 中文 abc"
        val buffer = fileBuffer(tempDir, bytes(content), blockSize = 8)
        val results = buffer.search("abc", options = SearchOptions(caseSensitive = true)).getOrThrow()
        results.map { it.position.offset } shouldBe listOf(
            content.indexOf("abc").toLong(),
            content.lastIndexOf("abc").toLong(),
        )
    }

    // ── invariant ─────────────────────────────────────────────────────────────────

    @Test
    fun `total length equals getFullText length for multibyte across blocks`(@TempDir tempDir: File) = runTest {
        val content = "中文a中文b中文c中文"
        val buffer = fileBuffer(tempDir, bytes(content), blockSize = 8)
        buffer.totalLength.value shouldBe buffer.getFullText().getOrThrow().length.toLong()
    }
}
