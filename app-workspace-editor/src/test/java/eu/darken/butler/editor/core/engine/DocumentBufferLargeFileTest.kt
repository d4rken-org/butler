package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File

/**
 * The one enabled test that runs a REAL multi-megabyte file through the buffer at the
 * PRODUCTION block size (64KB): every other multi-block test shrinks blocks instead of
 * content, which exercises the same code paths but can't catch bugs that only manifest
 * with realistic block counts, real file I/O, and multibyte chars at real 64KB edges.
 * Correctness-only - no timing assertions (perf stays a manual benchmark concern).
 */
class DocumentBufferLargeFileTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

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

    companion object {
        private const val LINE_COUNT = 120_000

        /**
         * ~6.1MB / ~95 production blocks. Every 1000th line carries multibyte chars (2- and
         * 3-byte UTF-8); dedicated straddling coverage is in the block-edge test below.
         */
        private fun generateContent(): String = buildString(LINE_COUNT * 52) {
            for (i in 0 until LINE_COUNT) {
                if (i % 1000 == 0) {
                    append("Zeile %06d äöü☃ multibyte content padding XYZWV\n".format(java.util.Locale.ROOT, i))
                } else {
                    append("Line %06d abcdefghijklmnopqrstuvwxyz0123456789AB\n".format(java.util.Locale.ROOT, i))
                }
            }
        }

        private fun lineOf(content: String, index: Int): String =
            content.lineSequence().drop(index).first()
    }

    private suspend fun openLargeBuffer(tempDir: File, content: String): Pair<File, DocumentBuffer> {
        val file = File(tempDir, "large.txt").apply { writeBytes(content.toByteArray()) }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(file), createMockGateway()).apply { open() }
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            // Explicitly the production default - the whole point of this test
            blockSize = eu.darken.butler.editor.core.engine.text.BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        return file to buffer
    }

    @Test
    fun `opens a 6MB file with exact line and char counts`(@TempDir tempDir: File) = runTest {
        val content = generateContent()
        content.toByteArray().size shouldBeGreaterThan 6_000_000
        val (_, buffer) = openLargeBuffer(tempDir, content)

        // +1: the trailing break separates a final empty line
        buffer.totalLines.value shouldBe LINE_COUNT.toLong() + 1
        buffer.totalLength.value shouldBe content.length.toLong()
    }

    @Test
    fun `line reads are exact across the whole document including multibyte lines`(
        @TempDir tempDir: File,
    ) = runTest {
        val content = generateContent()
        val (_, buffer) = openLargeBuffer(tempDir, content)

        // First, last, interior, and multibyte lines - all decoded through different 64KB blocks
        for (line in listOf(0, 1, 999, 1000, 59_999, 60_000, 100_000, LINE_COUNT - 1)) {
            buffer.getTextForLine(line.toLong()).getOrThrow() shouldBe lineOf(content, line)
        }
    }

    @Test
    fun `cross-block edits save byte-exact and undo back to the original`(@TempDir tempDir: File) = runTest {
        val content = generateContent()
        val (file, buffer) = openLargeBuffer(tempDir, content)
        val reference = StringBuilder(content)

        // Insert deep in the document, delete a range spanning a 64KB block edge region,
        // and append near the end - three separate dirty regions across distinct blocks
        val insertAt = content.length / 2L
        buffer.insertText(buffer.findPosition(insertAt), "<INSERTED äöü>").getOrThrow()
        reference.insert(insertAt.toInt(), "<INSERTED äöü>")

        val deleteStart = 65_500L
        val deleteEnd = 66_100L
        buffer.deleteText(buffer.findPosition(deleteStart), buffer.findPosition(deleteEnd)).getOrThrow()
        reference.delete(deleteStart.toInt(), deleteEnd.toInt())

        val appendAt = reference.length.toLong() - 10L
        buffer.insertText(buffer.findPosition(appendAt), "TAIL").getOrThrow()
        reference.insert(appendAt.toInt(), "TAIL")

        buffer.saveFile().getOrThrow()
        file.readBytes() shouldBe reference.toString().toByteArray()

        // Undo survives the post-save rebase and restores the exact original content
        buffer.undo().getOrThrow()
        buffer.undo().getOrThrow()
        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe content
    }

    @Test
    fun `multibyte chars straddling production 64KB block edges decode exactly`(@TempDir tempDir: File) = runTest {
        val blockSize = eu.darken.butler.editor.core.engine.text.BlockIndexBuilder.DEFAULT_BLOCK_SIZE
        // First straddler: 3-byte '☃' whose bytes span the first 64KB edge (65535..65537);
        // second straddler: 4-byte '🎉' spanning the second edge. Edge snapping must carry
        // both whole, and every offset after them shifts relative to the byte positions.
        val content = buildString {
            append("a".repeat(blockSize - 1))   // bytes 0 .. blockSize-2
            append("☃")                          // 3 bytes spanning the edge at blockSize
            append("b".repeat(blockSize - 4))   // brings the byte length to 2*blockSize - 2
            append("🎉")                         // 4 bytes spanning the edge at 2*blockSize
            append("tail content\nlast line")
        }
        val (file, buffer) = openLargeBuffer(tempDir, content)

        buffer.getFullText().getOrThrow() shouldBe content
        buffer.totalLength.value shouldBe content.length.toLong()

        // Edit AFTER both straddlers, save, and verify byte-exact output
        val insertAt = content.length.toLong() - 9L
        buffer.insertText(buffer.findPosition(insertAt), "<X>").getOrThrow()
        buffer.saveFile().getOrThrow()
        val reference = StringBuilder(content).insert(insertAt.toInt(), "<X>").toString()
        file.readBytes() shouldBe reference.toByteArray()
    }

    @Test
    fun `search finds needles across the whole document with exact offsets`(@TempDir tempDir: File) = runTest {
        val content = generateContent()
        val (_, buffer) = openLargeBuffer(tempDir, content)

        // "Zeile 059000" exists exactly once; the multibyte marker lines exist LINE_COUNT/1000 times
        val single = buffer.search("Zeile 059000", SearchOptions()).getOrThrow().results
        single shouldHaveSize 1
        single.single().position.offset shouldBe content.indexOf("Zeile 059000").toLong()

        val all = buffer.search("☃", SearchOptions()).getOrThrow().results
        all shouldHaveSize LINE_COUNT / 1000
    }

    @Test
    fun `multi-MB single-line file stays bounded through the display API`(@TempDir tempDir: File) = runTest {
        // One giant line at the PRODUCTION block size and PRODUCTION display cap
        val content = buildString(3_000_000) { repeat(300_000) { append("0123456789") } }
        val (_, buffer) = openLargeBuffer(tempDir, content)

        buffer.totalLines.value shouldBe 1L
        val window = buffer.getDisplayRange(0, 0).getOrThrow()
        window.text.length shouldBe DocumentBuffer.MAX_DISPLAY_LINE_CHARS
        window.text shouldBe content.substring(0, DocumentBuffer.MAX_DISPLAY_LINE_CHARS)
        window.truncatedLines shouldBe
            mapOf(0L to (content.length - DocumentBuffer.MAX_DISPLAY_LINE_CHARS).toLong())

        buffer.getLineLength(0).getOrThrow() shouldBe content.length.toLong()
        (buffer.contentSource.value as ContentSource.File).hasLongLines shouldBe true
    }
}
