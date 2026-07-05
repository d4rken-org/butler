package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.engine.text.BlockIndexBuilder
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Source
import okio.buffer
import okio.use
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Streaming-splice save: exact on-disk bytes (the multi-block cases fail on the old chunk
 * engine), failure semantics, cancellation, external-modification guard, and in-place mode.
 */
class DocumentBufferSaveTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(failTempWrites: Boolean = false): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { exists(any()) } coAnswers { fileSystemOps.exists(firstArg<APath<*>>() as LocalPath) }
        @Suppress("UNCHECKED_CAST")
        coEvery { lookup(any(), any()) } coAnswers {
            fileSystemOps.lookup(firstArg<APath<*>>() as LocalPath, secondArg<LookupOptions>()) as APathLookup<APath<*>>
        }
        coEvery { file(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val readWrite = secondArg<Boolean>()
            if (failTempWrites && readWrite && path.name.contains("butler-save-tmp")) {
                throw IOException("Simulated temp write failure")
            }
            if (readWrite && !fileSystemOps.exists(path)) path.file.createNewFile()
            fileSystemOps.file(path, readWrite)
        }
        coEvery { createFile(any(), any()) } coAnswers {
            (firstArg<APath<*>>() as LocalPath).file.createNewFile()
        }
        coEvery { delete(any<APath<*>>()) } coAnswers { fileSystemOps.delete(firstArg<APath<*>>() as LocalPath) }
        coEvery { move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            (firstArg<APath<*>>() as LocalPath).file.renameTo((secondArg<APath<*>>() as LocalPath).file)
        }
    }

    private suspend fun fileBuffer(
        tempDir: File,
        bytes: ByteArray,
        blockSize: Int = 10,
        gateway: GatewaySwitch = createMockGateway(),
    ): Pair<File, DocumentBuffer> {
        val file = File(tempDir, "save.txt").apply { writeBytes(bytes) }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(file), gateway).apply { open() }
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = blockSize,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        return file to buffer
    }

    private fun blockContent(blocks: Int = 10): String =
        (0 until blocks).joinToString("") { i -> "$i" + "23456789" }

    // ── exact on-disk bytes ─────────────────────────────────────────────────────

    @Test
    fun `multi-block insert then save writes exact bytes`(@TempDir tempDir: File) = runTest {
        val content = blockContent()
        val (file, buffer) = fileBuffer(tempDir, content.toByteArray(), blockSize = 10)

        buffer.insertText(TextPosition(5, 0, 5), "XYZ").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        val expected = StringBuilder(content).insert(5, "XYZ").toString()
        file.readBytes() shouldBe expected.toByteArray()
        buffer.isModified.value shouldBe false
        buffer.getFullText().getOrThrow() shouldBe expected
    }

    @Test
    fun `multi-block delete then save writes exact bytes`(@TempDir tempDir: File) = runTest {
        val content = blockContent()
        val (file, buffer) = fileBuffer(tempDir, content.toByteArray(), blockSize = 10)

        buffer.deleteText(TextPosition(2, 0, 2), TextPosition(37, 0, 37)).getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        val expected = StringBuilder(content).delete(2, 37).toString()
        file.readBytes() shouldBe expected.toByteArray()
        buffer.getFullText().getOrThrow() shouldBe expected
    }

    @Test
    fun `save then reread through cache eviction`(@TempDir tempDir: File) = runTest {
        val content = "0123456789".repeat(30)  // 30 blocks, more than the decode cache holds
        val (file, buffer) = fileBuffer(tempDir, content.toByteArray(), blockSize = 10)

        buffer.insertText(TextPosition(5, 0, 5), "XYZ").getOrThrow()
        buffer.deleteText(TextPosition(200, 0, 0), TextPosition(220, 0, 0)).getOrThrow()
        val expected = StringBuilder(content).insert(5, "XYZ").delete(200, 220).toString()

        buffer.saveFile().isSuccess shouldBe true

        file.readBytes() shouldBe expected.toByteArray()
        buffer.getFullText().getOrThrow() shouldBe expected
        buffer.getText(250, 270).getOrThrow() shouldBe expected.substring(250, 270)
    }

    @Test
    fun `BOM multibyte CRLF save writes exact bytes`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val content = "中文\r\n日本"
        val (file, buffer) = fileBuffer(tempDir, bom + content.toByteArray(), blockSize = 8)

        // Offset 3 lands between the \r and \n - seam split plus save
        buffer.insertText(TextPosition(3, 0, 0), "té😀").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        val expected = StringBuilder(content).insert(3, "té😀").toString()
        file.readBytes() shouldBe bom + expected.toByteArray()
        buffer.getFullText().getOrThrow() shouldBe expected
    }

    @Test
    fun `BOM file with multiple dirty regions writes exact bytes`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val content = blockContent()
        val (file, buffer) = fileBuffer(tempDir, bom + content.toByteArray(), blockSize = 10)
        val reference = StringBuilder(content)

        buffer.insertText(TextPosition(5, 0, 5), "AA").getOrThrow()
        reference.insert(5, "AA")
        buffer.insertText(TextPosition(85, 0, 0), "BB").getOrThrow()
        reference.insert(85, "BB")
        buffer.deleteText(TextPosition(40, 0, 0), TextPosition(60, 0, 0)).getOrThrow()
        reference.delete(40, 60)

        buffer.saveFile().isSuccess shouldBe true
        file.readBytes() shouldBe bom + reference.toString().toByteArray()
    }

    @Test
    fun `UTF-16LE save writes BOM once with multiple added runs`(@TempDir tempDir: File) = runTest {
        val content = "Hello\r\nWorld"
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val (file, buffer) = fileBuffer(tempDir, bom + content.toByteArray(Charsets.UTF_16LE), blockSize = 8)
        val reference = StringBuilder(content)

        buffer.insertText(TextPosition(2, 0, 2), "aä").getOrThrow()
        reference.insert(2, "aä")
        buffer.insertText(TextPosition(10, 0, 0), "中").getOrThrow()
        reference.insert(10, "中")

        buffer.saveFile().isSuccess shouldBe true
        file.readBytes() shouldBe bom + reference.toString().toByteArray(Charsets.UTF_16LE)
        buffer.getFullText().getOrThrow() shouldBe reference.toString()
    }

    @Test
    fun `UTF-16BE save preserves BOM and encoding`(@TempDir tempDir: File) = runTest {
        val content = "abc中"
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val (file, buffer) = fileBuffer(tempDir, bom + content.toByteArray(Charsets.UTF_16BE), blockSize = 4)

        buffer.insertText(TextPosition(1, 0, 1), "X😀").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        val expected = StringBuilder(content).insert(1, "X😀").toString()
        file.readBytes() shouldBe bom + expected.toByteArray(Charsets.UTF_16BE)
    }

    @Test
    fun `malformed bytes in untouched region survive byte-verbatim`(@TempDir tempDir: File) = runTest {
        // 0xC3 without a continuation byte is malformed UTF-8; it must round-trip untouched
        val original = "head ".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + " tail".toByteArray()
        val (file, buffer) = fileBuffer(tempDir, original, blockSize = 4)

        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        file.readBytes() shouldBe "X".toByteArray() + original
    }

    // ── empty files ─────────────────────────────────────────────────────────────

    @Test
    fun `empty file insert save then delete-all save`(@TempDir tempDir: File) = runTest {
        val (file, buffer) = fileBuffer(tempDir, ByteArray(0), blockSize = 10)

        buffer.insertText(TextPosition(0, 0, 0), "hello").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true
        file.readBytes() shouldBe "hello".toByteArray()

        buffer.deleteText(TextPosition(0, 0, 0), TextPosition(5, 0, 5)).getOrThrow()
        buffer.saveFile().isSuccess shouldBe true
        file.readBytes().size shouldBe 0
        buffer.totalLines.value shouldBe 1
    }

    @Test
    fun `delete-all on BOM file saves the BOM only`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val (file, buffer) = fileBuffer(tempDir, bom + "content".toByteArray(), blockSize = 4)

        buffer.deleteText(TextPosition(0, 0, 0), TextPosition(7, 0, 7)).getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        file.readBytes() shouldBe bom
    }

    @Test
    fun `unmodified buffer save is a no-op`(@TempDir tempDir: File) = runTest {
        val original = "stable content".toByteArray()
        val (file, buffer) = fileBuffer(tempDir, original)

        buffer.saveFile().isSuccess shouldBe true

        file.readBytes() shouldBe original
        tempDir.listFiles()!!.map { it.name } shouldBe listOf("save.txt")
    }

    // ── failure semantics ───────────────────────────────────────────────────────

    @Test
    fun `write failure leaves original intact and buffer editable`(@TempDir tempDir: File) = runTest {
        val content = blockContent()
        val (file, buffer) = fileBuffer(
            tempDir,
            content.toByteArray(),
            blockSize = 10,
            gateway = createMockGateway(failTempWrites = true),
        )

        buffer.insertText(TextPosition(5, 0, 5), "XYZ").getOrThrow()
        val result = buffer.saveFile()

        result.isFailure shouldBe true
        file.readBytes() shouldBe content.toByteArray()
        tempDir.listFiles()!!.map { it.name } shouldBe listOf("save.txt")
        buffer.isModified.value shouldBe true

        // Buffer stays fully usable after the failed save
        val expected = StringBuilder(content).insert(5, "XYZ").toString()
        buffer.getFullText().getOrThrow() shouldBe expected
        buffer.insertText(TextPosition(0, 0, 0), "A").getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "A$expected"
    }

    private class RebaseFailingDataSource(
        private val delegate: EditorDataSource,
    ) : EditorDataSource by delegate {
        private var failReads = false

        override suspend fun commit(writer: suspend (EditorDataSource.CommitContext) -> Unit) {
            delegate.commit(writer)
            failReads = true
        }

        override suspend fun openByteSource(offset: Long): Source {
            if (failReads) throw IOException("Simulated read failure after commit")
            return delegate.openByteSource(offset)
        }
    }

    @Test
    fun `rebase failure after successful commit enters error state`() = runTest {
        val delegate = InMemoryDataSource(workspaceId, "hello world").apply { open() }
        val dataSource = RebaseFailingDataSource(delegate)
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        val result = buffer.saveFile()

        result.isFailure shouldBe true
        // The commit itself landed
        delegate.getMeta().size shouldBe "Xhello world".toByteArray().size.toLong()
        // But the buffer refuses to serve stale pieces and stays "modified"
        buffer.isModified.value shouldBe true
        buffer.getText(0, 5).isFailure shouldBe true
        buffer.saveFile().isFailure shouldBe true
    }

    private class GatedCommitDataSource(
        private val delegate: InMemoryDataSource,
    ) : EditorDataSource by delegate {
        val gate = CompletableDeferred<Unit>()
        var commitRan = false

        override suspend fun commit(writer: suspend (EditorDataSource.CommitContext) -> Unit) {
            gate.await()
            delegate.commit(writer)
            commitRan = true
        }
    }

    @Test
    fun `cancellation before point of no return aborts cleanly`() = runTest {
        val delegate = InMemoryDataSource(workspaceId, "hello world").apply { open() }
        val dataSource = GatedCommitDataSource(delegate)
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        var result: Result<Unit>? = null
        val job = launch { result = buffer.saveFile() }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        result.shouldBeNull()
        dataSource.commitRan.shouldBeFalse()
        delegate.getMeta().size shouldBe "hello world".toByteArray().size.toLong()
        buffer.isModified.value shouldBe true
        buffer.getFullText().getOrThrow() shouldBe "Xhello world"

        // A later save still works
        dataSource.gate.complete(Unit)
        buffer.saveFile().isSuccess shouldBe true
        buffer.isModified.value shouldBe false
        buffer.getFullText().getOrThrow() shouldBe "Xhello world"
    }

    private class CancelAfterCommitDataSource(
        private val delegate: InMemoryDataSource,
    ) : EditorDataSource by delegate {
        override suspend fun commit(writer: suspend (EditorDataSource.CommitContext) -> Unit) {
            delegate.commit(writer)
            // Cancellation lands exactly at the point of no return
            coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun `cancellation after point of no return still rebases`() = runTest {
        val delegate = InMemoryDataSource(workspaceId, "hello").apply { open() }
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = CancelAfterCommitDataSource(delegate),
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        val job = launch { buffer.saveFile() }
        job.join()

        delegate.getMeta().size shouldBe "Xhello".toByteArray().size.toLong()
        buffer.isModified.value shouldBe false
        buffer.getFullText().getOrThrow() shouldBe "Xhello"
    }

    // ── external modification guard ─────────────────────────────────────────────

    @Test
    fun `externally resized file fails save with explicit error`(@TempDir tempDir: File) = runTest {
        val content = blockContent(3)
        val (file, buffer) = fileBuffer(tempDir, content.toByteArray(), blockSize = 10)
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        val external = (content + "MORE").toByteArray()
        file.writeBytes(external)

        val result = buffer.saveFile()
        (result.exceptionOrNull() is ExternalModificationException).shouldBeTrue()
        file.readBytes() shouldBe external
        buffer.isModified.value shouldBe true
    }

    @Test
    fun `same-size external change fails via content hash`(@TempDir tempDir: File) = runTest {
        val original = "0123456789".repeat(3).toByteArray()
        val (file, buffer) = fileBuffer(tempDir, original, blockSize = 10)
        val originalMtime = file.lastModified()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        val tampered = "9876543210".repeat(3).toByteArray()
        file.writeBytes(tampered)
        file.setLastModified(originalMtime)

        val result = buffer.saveFile()
        (result.exceptionOrNull() is ExternalModificationException).shouldBeTrue()
        file.readBytes() shouldBe tampered
    }

    // ── in-place (SAF-style) mode ───────────────────────────────────────────────

    @Test
    fun `in-place commit reads original ranges from the backup`(@TempDir tempDir: File) = runTest {
        val original = "ABCDEFGHIJKLMNOP".toByteArray()
        val file = File(tempDir, "inplace.txt").apply { writeBytes(original) }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(file), createMockGateway()).apply { open() }
        val backupFile = File(tempDir, "inplace.txt.butler-save-bak-test")

        dataSource.commitViaInPlace(LocalPath.build(backupFile)) { context ->
            // The target is already truncated; reads must serve the pre-commit content
            file.length() shouldBe 0L
            context.openOriginalSource(0).buffer().use { it.readByteArray() } shouldBe original
            context.sink.write("NEW-".toByteArray())
            context.openOriginalSource(8).buffer().use { source -> context.sink.write(source, 4) }
        }

        file.readBytes() shouldBe "NEW-".toByteArray() + original.copyOfRange(8, 12)
        backupFile.exists().shouldBeFalse()
    }

    @Test
    fun `in-place writer failure restores original and keeps the backup`(@TempDir tempDir: File) = runTest {
        val original = "precious content".toByteArray()
        val file = File(tempDir, "inplace.txt").apply { writeBytes(original) }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(file), createMockGateway()).apply { open() }
        val backupFile = File(tempDir, "inplace.txt.butler-save-bak-test")

        val result = runCatching {
            dataSource.commitViaInPlace(LocalPath.build(backupFile)) { context ->
                context.sink.write("junk".toByteArray())
                throw IOException("Simulated writer failure")
            }
        }

        result.isFailure.shouldBeTrue()
        file.readBytes() shouldBe original
        // The backup is retained as a recovery copy after an in-place failure
        backupFile.exists().shouldBeTrue()
        backupFile.readBytes() shouldBe original
    }
}
