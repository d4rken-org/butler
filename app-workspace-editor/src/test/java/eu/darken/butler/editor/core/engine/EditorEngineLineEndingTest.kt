package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.nio.charset.Charset
import kotlin.random.Random
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Insert-side line-ending consistency: text entering the engine conforms to the document's
 * uniform ending (CRLF or LF) so ordinary editing never turns a uniform file MIXED, and the
 * re-detected ending propagates to the UI-facing contentSource after a save.
 */
class EditorEngineLineEndingTest : BaseTest() {

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

    private fun createMockSettings(): EditorSettings {
        val settings = mockk<EditorSettings>()
        fun <T> value(v: T): DataStoreValue<T> = mockk<DataStoreValue<T>>().apply {
            every { flow } returns flowOf(v)
        }
        every { settings.undoStackSize } returns value(100)
        every { settings.undoMaxMemory } returns value(10 * 1_048_576L)
        val syntaxHighlighting = mockk<DataStoreValue<Boolean>>()
        every { syntaxHighlighting.flow } returns flowOf(true)
        every { settings.syntaxHighlighting } returns syntaxHighlighting
        return settings
    }

    private suspend fun createEngine(
        file: File,
        gateway: GatewaySwitch = createMockGateway(),
    ): EditorEngine = EditorEngine(
        workspaceId = workspaceId,
        filePath = LocalPath.build(file),
        initialContent = null,
        gatewaySwitch = gateway,
        editorSettings = createMockSettings(),
        dispatcherProvider = TestDispatcherProvider(),
        fileDataSourceFactory = object : FileDataSource.Factory {
            override fun create(
                workspaceId: Workspace.Id,
                filePath: APath<*>,
                gatewaySwitch: GatewaySwitch,
                charsetOverride: Charset?,
            ) = FileDataSource(workspaceId, filePath, gatewaySwitch, charsetOverride)
        },
        inMemoryDataSourceFactory = object : InMemoryDataSource.Factory {
            override fun create(workspaceId: Workspace.Id, initialContent: String) =
                InMemoryDataSource(workspaceId, initialContent)
        },
        documentBufferFactory = object : DocumentBuffer.Factory {
            override fun create(
                workspaceId: Workspace.Id,
                dataSource: EditorDataSource,
                maxUndoStackSize: Int,
                maxUndoMemoryBytes: Long,
                blockSize: Int,
                assertions: Boolean,
                staleSampleRandom: Random,
                timeSource: kotlin.time.TimeSource,
                maxDisplayLineChars: Int,
            ) = DocumentBuffer(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, 10, true)
        },
    ).apply { initialize().getOrThrow() }

    private suspend fun EditorEngine.fullText(): String = textBuffer!!.getFullText().getOrThrow()

    @Test
    fun `enter in a CRLF document stays CRLF on disk`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("line1\r\nline2") }
        val engine = createEngine(file)

        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))
        engine.performInsert("\n")
        engine.saveFile().getOrThrow()

        file.readText() shouldBe "line1\r\n\r\nline2"
        // The re-detected ending after the post-save rebase reaches the UI-facing flow
        val source = engine.contentSource.first()
        source.shouldBeInstanceOf<ContentSource.File>().lineEnding shouldBe LineEnding.CRLF
    }

    @Test
    fun `undo removes exactly the translated break`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("line1\r\nline2") }
        val engine = createEngine(file)

        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))
        engine.performInsert("\n")
        engine.fullText() shouldBe "line1\r\n\r\nline2"

        engine.performUndo()

        engine.fullText() shouldBe "line1\r\nline2"
        engine.isModified.first() shouldBe false
    }

    @Test
    fun `the field delta path conforms the enter key in a CRLF document`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("line1\r\nline2") }
        val engine = createEngine(file)

        engine.applyFieldDelta(
            EditorEngine.FieldDelta(
                token = engine.visibleContent.value.token!!,
                start = TextPosition(offset = 0, line = 0, column = 5),
                end = TextPosition(offset = 0, line = 0, column = 5),
                oldText = "",
                newText = "\n",
                caret = TextPosition(offset = 0, line = 1, column = 0),
            ),
        )

        engine.fullText() shouldBe "line1\r\n\r\nline2"
    }

    @Test
    fun `pasting mixed breaks into a CRLF document conforms them`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("line1\r\nline2") }
        val engine = createEngine(file)

        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))
        engine.performInsert("X\nY\r\nZ\rW")

        engine.fullText() shouldBe "line1X\r\nY\r\nZ\r\nW\r\nline2"
    }

    @Test
    fun `pasting CRLF text into an LF document conforms to LF`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("alpha\nbeta") }
        val engine = createEngine(file)

        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))
        engine.performInsert("P\r\nQ")

        engine.fullText() shouldBe "alphaP\nQ\nbeta"
    }

    @Test
    fun `mixed documents receive breaks verbatim`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("a\nb\r\nc") }
        val engine = createEngine(file)

        engine.setCursorPosition(TextPosition(offset = 1, line = 0, column = 1))
        engine.performInsert("\n")

        engine.fullText() shouldBe "a\n\nb\r\nc"
    }

    @Test
    fun `replace-all conforms replacement breaks in a CRLF document`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("aXb\r\ncXd") }
        val engine = createEngine(file)

        engine.replaceAll("X", SearchOptions(), "\n").getOrThrow()

        engine.fullText() shouldBe "a\r\nb\r\nc\r\nd"
    }

    // ── explicit conversion ─────────────────────────────────────────────────────

    @Test
    fun `converts CRLF to LF byte-exact on disk`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("line1\r\nline2\r\nline3") }
        val engine = createEngine(file)

        engine.convertLineEndings(LineEnding.LF).getOrThrow()

        file.readText() shouldBe "line1\nline2\nline3"
        val source = engine.contentSource.first()
        source.shouldBeInstanceOf<ContentSource.File>().lineEnding shouldBe LineEnding.LF
        engine.isModified.first() shouldBe false
    }

    @Test
    fun `converts LF to CRLF including unsaved edits`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("alpha\nbeta") }
        val engine = createEngine(file)
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))
        engine.performInsert("X")

        engine.convertLineEndings(LineEnding.CRLF).getOrThrow()

        file.readText() shouldBe "alphaX\r\nbeta"
        engine.isModified.first() shouldBe false
    }

    @Test
    fun `converts MIXED endings to a uniform target`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("a\nb\r\nc\rd") }
        val engine = createEngine(file)

        engine.convertLineEndings(LineEnding.LF).getOrThrow()

        file.readText() shouldBe "a\nb\nc\nd"
        val source = engine.contentSource.first()
        source.shouldBeInstanceOf<ContentSource.File>().lineEnding shouldBe LineEnding.LF
    }

    @Test
    fun `conversion clears undo history`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("a\r\nb") }
        val engine = createEngine(file)
        engine.setCursorPosition(TextPosition(offset = 1, line = 0, column = 1))
        engine.performInsert("X")
        engine.canUndo.first() shouldBe true

        engine.convertLineEndings(LineEnding.LF).getOrThrow()

        engine.canUndo.first() shouldBe false
        engine.canRedo.first() shouldBe false
        engine.performUndo()
        engine.fullText() shouldBe "aX\nb"
    }

    @Test
    fun `conversion preserves the BOM`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = File(tempDir, "doc.txt").apply { writeBytes(bom + "x\r\ny".toByteArray()) }
        val engine = createEngine(file)

        engine.convertLineEndings(LineEnding.LF).getOrThrow()

        file.readBytes().toList() shouldBe (bom + "x\ny".toByteArray()).toList()
    }

    @Test
    fun `conversion survives multibyte chars across streaming chunk boundaries`(@TempDir tempDir: File) = runTest {
        // 20k repetitions of a 4-byte unit ("é" = 2 bytes in UTF-8 + CRLF) = ~80 KB, forcing the
        // 64 KB conversion chunk boundary to land mid-content (and likely mid-multibyte-char)
        val unit = "é\r\n"
        val file = File(tempDir, "doc.txt").apply { writeText(unit.repeat(20_000)) }
        val engine = createEngine(file)

        engine.convertLineEndings(LineEnding.LF).getOrThrow()

        file.readText() shouldBe "é\n".repeat(20_000)
    }

    @Test
    fun `conversion is a no-op for an already uniform unmodified document`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("a\nb") }
        val engine = createEngine(file)

        engine.convertLineEndings(LineEnding.LF).getOrThrow()

        file.readText() shouldBe "a\nb"
    }

    @Test
    fun `conversion refuses read-only files`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("a\r\nb") }
        val gateway = createMockGateway().apply {
            coEvery { canWrite(any()) } returns false
        }
        val engine = createEngine(file, gateway)

        val result = engine.convertLineEndings(LineEnding.LF)

        result.exceptionOrNull().shouldBeInstanceOf<ReadOnlyFileException>()
        file.readText() shouldBe "a\r\nb"
    }

    @Test
    fun `cursor offset is recomputed after conversion`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("a\r\nbc") }
        val engine = createEngine(file)
        // Offset 3 = start of line 1 in CRLF char space; after conversion the same line/column
        // sits at offset 2 - forward delete must hit 'b', not 'c'
        engine.setCursorPosition(TextPosition(offset = 3, line = 1, column = 0))

        engine.convertLineEndings(LineEnding.LF).getOrThrow()
        engine.performDeleteForward()

        engine.fullText() shouldBe "a\nc"
    }

    @Test
    fun `conversion refuses when the file changed on disk, even for a same-target no-op`(
        @TempDir tempDir: File,
    ) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("a\nb") }
        val engine = createEngine(file)

        file.writeText("externally grown content")

        val result = engine.convertLineEndings(LineEnding.LF)

        result.exceptionOrNull().shouldBeInstanceOf<ExternalModificationException>()
        engine.externalChange.first().shouldNotBeNull()
    }
}
