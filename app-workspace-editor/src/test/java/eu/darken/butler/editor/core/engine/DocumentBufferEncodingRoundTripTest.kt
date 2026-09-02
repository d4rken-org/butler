package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.nio.charset.Charset

/**
 * Edit/save round-trips for the two allowlist charsets that previously had no buffer-level
 * coverage (windows-1252, US-ASCII), plus a pin on what happens to UTF-16 files WITHOUT a
 * BOM (they fall back to UTF-8 and are flagged binary - intentional, previously unpinned).
 */
class DocumentBufferEncodingRoundTripTest : BaseTest() {

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

    private suspend fun openBuffer(
        file: File,
        charsetOverride: Charset?,
    ): Pair<FileDataSource, DocumentBuffer> {
        val dataSource = FileDataSource(
            workspaceId, LocalPath.build(file), createMockGateway(), charsetOverride,
        ).apply { open() }
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 8,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        return dataSource to buffer
    }

    @Test
    fun `windows-1252 content round-trips through edit and save`(@TempDir tempDir: File) = runTest {
        val cp1252 = Charset.forName("windows-1252")
        // Smart quotes and the euro sign live in the 0x80-0x9F range that is cp1252-specific
        // (undefined in ISO-8859-1) - the exact bytes this test must preserve
        val original = "price “42”\nsecond line"
        val file = File(tempDir, "cp1252.txt").apply { writeBytes(original.toByteArray(cp1252)) }

        val (_, buffer) = openBuffer(file, cp1252)
        buffer.getFullText().getOrThrow() shouldBe original

        buffer.insertText(TextPosition(6, 0, 6), "€").getOrThrow()
        buffer.saveFile().getOrThrow()

        val expected = "price €“42”\nsecond line"
        file.readBytes() shouldBe expected.toByteArray(cp1252)
        // The euro sign must be the single cp1252 byte 0x80, not a UTF-8 sequence
        file.readBytes()[6] shouldBe 0x80.toByte()

        val (_, reopened) = openBuffer(file, cp1252)
        reopened.getFullText().getOrThrow() shouldBe expected
    }

    @Test
    fun `US-ASCII content round-trips through edit and save`(@TempDir tempDir: File) = runTest {
        val original = "plain ascii\nsecond line"
        val file = File(tempDir, "ascii.txt").apply { writeBytes(original.toByteArray(Charsets.US_ASCII)) }

        val (_, buffer) = openBuffer(file, Charsets.US_ASCII)
        buffer.getFullText().getOrThrow() shouldBe original

        buffer.deleteText(TextPosition(0, 0, 0), TextPosition(6, 0, 6)).getOrThrow()
        buffer.insertText(TextPosition(0, 0, 0), "pure ").getOrThrow()
        buffer.saveFile().getOrThrow()

        file.readBytes() shouldBe "pure ascii\nsecond line".toByteArray(Charsets.US_ASCII)
    }

    @Test
    fun `UTF-16 without a BOM falls back to UTF-8 and is flagged binary`(@TempDir tempDir: File) = runTest {
        // "Hi\n" as BOM-less UTF-16LE: the NUL bytes are valid UTF-8, so detection cannot
        // distinguish this from binary content - it opens read-only instead of guessing
        val bytes = byteArrayOf(0x48, 0x00, 0x69, 0x00, 0x0A, 0x00)
        val file = File(tempDir, "no-bom.txt").apply { writeBytes(bytes) }

        val (dataSource, buffer) = openBuffer(file, charsetOverride = null)

        val source = dataSource.contentSource.value as ContentSource.File
        source.detectedCharset shouldBe Charsets.UTF_8
        source.hasBOM shouldBe false
        source.isLikelyBinary.shouldBeTrue()
        // Content decodes as UTF-8 with embedded NULs, byte-for-byte unmangled
        buffer.getFullText().getOrThrow() shouldBe "H\u0000i\u0000\n\u0000"
    }
}
