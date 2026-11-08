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

/** Whole-file rewrites refuse while the document still references bytes its charset can't decode. */
class DocumentBufferUndecodableConversionTest : BaseTest() {

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

    private suspend fun openBuffer(file: File): DocumentBuffer {
        val dataSource = FileDataSource(workspaceId, LocalPath.build(file), createMockGateway(), null)
            .apply { open() }
        return DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 4,
            assertions = true,
        ).apply { initialize().getOrThrow() }
    }

    private fun pos(offset: Int) = TextPosition(offset.toLong(), 0, offset)

    // 0xC3 opens a two-byte UTF-8 sequence that '(' can't continue
    private val head = "head line one\n"
    private val brokenBytes = head.toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + " tail\r\nmore\r\n".toByteArray()

    @Test
    fun `line-ending conversion refuses over undecodable bytes`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "broken.txt").apply { writeBytes(brokenBytes) }
        val buffer = openBuffer(file)
        (buffer.contentSource.value as ContentSource.File).hasUndecodableBytes.shouldBeTrue()

        val error = buffer.convertLineEndings(LineEnding.LF).exceptionOrNull()

        error.shouldBeInstanceOf<UndecodableBytesException>().charset shouldBe Charsets.UTF_8
        file.readBytes() shouldBe brokenBytes
    }

    @Test
    fun `line-ending conversion succeeds once the undecodable region is deleted`(@TempDir tempDir: File) =
        runTest {
            val file = File(tempDir, "broken.txt").apply { writeBytes(brokenBytes) }
            val buffer = openBuffer(file)
            val bad = head.length
            buffer.getFullText().getOrThrow()[bad] shouldBe '�'

            // Wide enough to cover the whole flagged block, whatever its snapped edges
            buffer.deleteText(pos(bad - 8), pos(bad + 8)).getOrThrow()
            val remaining = buffer.getFullText().getOrThrow()

            buffer.convertLineEndings(LineEnding.LF).getOrThrow()

            file.readText() shouldBe remaining.replace("\r\n", "\n")
        }
}
