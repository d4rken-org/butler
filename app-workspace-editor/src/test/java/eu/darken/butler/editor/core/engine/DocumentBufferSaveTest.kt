package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.common.files.write.FileCommitContext
import eu.darken.butler.editor.core.engine.text.BlockIndexBuilder
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import okio.buffer
import okio.use
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Streaming-splice save: exact on-disk bytes (the multi-block cases fail on the old chunk
 * engine), failure semantics, cancellation, external-modification guard, and in-place mode.
 */
class DocumentBufferSaveTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(failTempWrites: Boolean = false): GatewaySwitch = mockk<GatewaySwitch>().apply {
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
            val renamed = (firstArg<APath<*>>() as LocalPath).file.renameTo((secondArg<APath<*>>() as LocalPath).file)
            if (renamed) MoveOutcome.Moved else MoveOutcome.NotSupported("rename failed")
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
    fun `writeContentTo streams the same bytes saveFile writes`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val (file, buffer) = fileBuffer(tempDir, bom + blockContent().toByteArray(), blockSize = 10)
        buffer.insertText(TextPosition(5, 0, 5), "édit😀").getOrThrow()
        buffer.deleteText(TextPosition(40, 0, 0), TextPosition(60, 0, 0)).getOrThrow()

        val streamed = Buffer().also { buffer.writeContentTo(it).getOrThrow() }.readByteArray()
        buffer.saveFile().isSuccess shouldBe true
        file.readBytes() shouldBe streamed
    }

    @Test
    fun `ISO-8859-1 override round-trips high bytes and encodes added text`(@TempDir tempDir: File) = runTest {
        // é = 0xE9 in ISO-8859-1, invalid as UTF-8; blockSize 4 forces edges through high bytes
        val original = "café\ncafé".toByteArray(Charsets.ISO_8859_1)
        val file = File(tempDir, "latin.txt").apply { writeBytes(original) }
        val dataSource = FileDataSource(
            workspaceId, LocalPath.build(file), createMockGateway(), Charsets.ISO_8859_1,
        ).apply { open() }
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 4,
            assertions = true,
        )
        buffer.initialize().getOrThrow()

        buffer.getFullText().getOrThrow() shouldBe "café\ncafé"

        buffer.insertText(TextPosition(4, 0, 4), "s è").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        file.readBytes() shouldBe "cafés è\ncafé".toByteArray(Charsets.ISO_8859_1)
        buffer.getFullText().getOrThrow() shouldBe "cafés è\ncafé"
    }

    @Test
    fun `read-only file fails save explicitly but unmodified save stays no-op`(@TempDir tempDir: File) = runTest {
        val gateway = createMockGateway().apply {
            coEvery { canWrite(any()) } returns false
        }
        val (file, buffer) = fileBuffer(tempDir, "hello".toByteArray(), gateway = gateway)

        // No modifications: save succeeds as a no-op even though the file is read-only
        buffer.saveFile().isSuccess shouldBe true

        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()
        val result = buffer.saveFile()
        (result.exceptionOrNull() is ReadOnlyFileException).shouldBeTrue()
        buffer.isModified.value shouldBe true
        file.readBytes() shouldBe "hello".toByteArray()
    }

    @Test
    fun `lone surrogate in added text saves deterministic UTF-8 replacement bytes`(@TempDir tempDir: File) = runTest {
        val (file, buffer) = fileBuffer(tempDir, "ab".toByteArray())

        buffer.insertText(TextPosition(1, 0, 1), "\uD800").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        file.readBytes() shouldBe byteArrayOf(
            'a'.code.toByte(), 0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte(), 'b'.code.toByte(),
        )
    }

    @Test
    fun `lone surrogate saves deterministic replacement bytes in UTF-16LE`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val (file, buffer) = fileBuffer(tempDir, bom + "ab".toByteArray(Charsets.UTF_16LE))

        buffer.insertText(TextPosition(1, 0, 1), "\uDC00").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        file.readBytes() shouldBe bom + byteArrayOf(
            'a'.code.toByte(), 0, 0xFD.toByte(), 0xFF.toByte(), 'b'.code.toByte(), 0,
        )
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
        buffer.totalLines.value shouldBe 1L
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

        override suspend fun commit(writer: suspend (FileCommitContext) -> Unit) {
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

    private class IntegrityFailingDataSource(
        private val delegate: InMemoryDataSource,
    ) : EditorDataSource by delegate {
        override suspend fun commit(writer: suspend (FileCommitContext) -> Unit) {
            throw eu.darken.butler.editor.core.sources.CommitIntegrityException(
                "Simulated unrestorable commit",
                IOException("boom"),
            )
        }
    }

    @Test
    fun `commit integrity failure poisons the buffer`() = runTest {
        val delegate = InMemoryDataSource(workspaceId, "hello world").apply { open() }
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = IntegrityFailingDataSource(delegate),
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        val result = buffer.saveFile()

        result.isFailure shouldBe true
        buffer.isModified.value shouldBe true
        // The target may have mutated: stale pieces must not be served
        buffer.getText(0, 5).isFailure shouldBe true
        buffer.insertText(TextPosition(0, 0, 0), "Y").isFailure shouldBe true
    }

    private class GatedCommitDataSource(
        private val delegate: InMemoryDataSource,
    ) : EditorDataSource by delegate {
        val gate = CompletableDeferred<Unit>()
        var commitRan = false

        override suspend fun commit(writer: suspend (FileCommitContext) -> Unit) {
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
        override suspend fun commit(writer: suspend (FileCommitContext) -> Unit) {
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

    @Test
    fun `interior same-size external change fails via sampled digest`(@TempDir tempDir: File) = runTest {
        // 3 blocks: the single interior block is always in the sample set
        val original = ("A".repeat(10) + "B".repeat(10) + "C".repeat(10)).toByteArray()
        val (file, buffer) = fileBuffer(tempDir, original, blockSize = 10)
        val originalMtime = file.lastModified()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        val tampered = ("A".repeat(10) + "b".repeat(10) + "C".repeat(10)).toByteArray()
        file.writeBytes(tampered)
        file.setLastModified(originalMtime)

        val result = buffer.saveFile()
        (result.exceptionOrNull() is ExternalModificationException).shouldBeTrue()
        file.readBytes() shouldBe tampered
    }

    @Test
    fun `unsampled interior tamper is absorbed - the documented staleness gap`(@TempDir tempDir: File) = runTest {
        // 10 blocks; a zero Random samples only interior block 1; block 5 is tampered.
        // The sampled check misses it: the save splices around the tampered bytes and the
        // post-save rebase makes them the permanent new baseline. This is the accepted
        // trade-off for not re-reading the whole file at every save.
        val original = "0123456789".repeat(10).toByteArray()
        val file = File(tempDir, "save.txt").apply { writeBytes(original) }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(file), createMockGateway()).apply { open() }
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 10,
            assertions = true,
            staleSampleRandom = FixedZeroRandom(),
        )
        buffer.initialize().getOrThrow()
        val originalMtime = file.lastModified()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        val tampered = original.copyOf().also { it[55] = 'x'.code.toByte() }
        file.writeBytes(tampered)
        file.setLastModified(originalMtime)

        buffer.saveFile().isSuccess shouldBe true
        file.readBytes() shouldBe "X".toByteArray() + tampered
    }

    @Test
    fun `BOM tampered externally fails save`(@TempDir tempDir: File) = runTest {
        // Same size, same mtime, post-BOM content unchanged: only the BOM check can catch this
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val (file, buffer) = fileBuffer(tempDir, bom + "hello".toByteArray())
        val originalMtime = file.lastModified()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0x21) + "hello".toByteArray())
        file.setLastModified(originalMtime)

        val result = buffer.saveFile()
        (result.exceptionOrNull() is ExternalModificationException).shouldBeTrue()
    }

    // ── external change probe (meta-only, poll-side) ────────────────────────────

    @Test
    fun `meta probe reports mtime-only change`(@TempDir tempDir: File) = runTest {
        val content = blockContent(3)
        val (file, buffer) = fileBuffer(tempDir, content.toByteArray(), blockSize = 10)

        buffer.checkExternalChange() shouldBe DocumentBuffer.ExternalChangeProbe.Unchanged

        file.setLastModified(file.lastModified() + 5_000)

        val probe = buffer.checkExternalChange()
        probe.shouldBeInstanceOf<DocumentBuffer.ExternalChangeProbe.Changed>()
        probe.meta.size shouldBe content.length.toLong()
    }

    @Test
    fun `meta probe misses same-size same-mtime change but save still refuses`(@TempDir tempDir: File) = runTest {
        val original = "0123456789".repeat(3).toByteArray()
        val (file, buffer) = fileBuffer(tempDir, original, blockSize = 10)
        val originalMtime = file.lastModified()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        val tampered = "9876543210".repeat(3).toByteArray()
        file.writeBytes(tampered)
        file.setLastModified(originalMtime)

        // Documented degradation: the cheap probe only compares size + mtime
        buffer.checkExternalChange() shouldBe DocumentBuffer.ExternalChangeProbe.Unchanged
        // The digest tier of the save-time guard still catches it
        (buffer.saveFile().exceptionOrNull() is ExternalModificationException).shouldBeTrue()
    }

    @Test
    fun `meta probe reports unknown for a deleted file`(@TempDir tempDir: File) = runTest {
        val content = blockContent(3)
        val (file, buffer) = fileBuffer(tempDir, content.toByteArray(), blockSize = 10)

        file.delete()

        // An unreadable file is no evidence the baseline was restored
        buffer.checkExternalChange() shouldBe DocumentBuffer.ExternalChangeProbe.Unknown
    }

    @Test
    fun `meta probe is unchanged for in-memory sources`() = runTest {
        val dataSource = InMemoryDataSource(workspaceId, "draft")
        dataSource.open()
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 10,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()

        buffer.checkExternalChange() shouldBe DocumentBuffer.ExternalChangeProbe.Unchanged
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

    /** Deterministic sampler: always draws 0, so interior sampling always picks block 1. */
    private class FixedZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }
}
