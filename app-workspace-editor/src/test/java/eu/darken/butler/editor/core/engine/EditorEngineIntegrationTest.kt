package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.engine.text.WindowedSearch
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.buffer
import okio.use
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import java.nio.charset.Charset

/**
 * Engine-level cases for the piece-table rewrite: save checkpoints across engine save,
 * line navigation on multi-block multibyte documents, and byte-exact content streaming.
 */
class EditorEngineIntegrationTest : DocumentBufferTestBase() {

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockSettings(): EditorSettings {
        val settings = mockk<EditorSettings>()
        val undoStackSize = mockk<DataStoreValue<Int>>()
        val undoMaxMemory = mockk<DataStoreValue<Long>>()
        every { undoStackSize.flow } returns flowOf(100)
        every { undoMaxMemory.flow } returns flowOf(10 * 1_048_576L)
        every { settings.undoStackSize } returns undoStackSize
        every { settings.undoMaxMemory } returns undoMaxMemory
        return settings
    }

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { canWrite(any()) } returns true
        coEvery { exists(any()) } coAnswers { fileSystemOps.exists(firstArg<APath<*>>() as LocalPath) }
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
            (firstArg<APath<*>>() as LocalPath).file.renameTo((secondArg<APath<*>>() as LocalPath).file)
        }
    }

    private suspend fun createEngine(
        content: String? = null,
        filePath: APath<*>? = null,
        docBlockSize: Int = 64 * 1024,
    ): EditorEngine {
        val inMemoryFactory = object : InMemoryDataSource.Factory {
            override fun create(workspaceId: Workspace.Id, initialContent: String) =
                InMemoryDataSource(workspaceId, initialContent)
        }
        val fileFactory = object : FileDataSource.Factory {
            override fun create(
                workspaceId: Workspace.Id,
                filePath: APath<*>,
                gatewaySwitch: GatewaySwitch,
                charsetOverride: Charset?,
            ) = FileDataSource(workspaceId, filePath, gatewaySwitch, charsetOverride)
        }
        val documentBufferFactory = object : DocumentBuffer.Factory {
            override fun create(
                workspaceId: Workspace.Id,
                dataSource: EditorDataSource,
                maxUndoStackSize: Int,
                maxUndoMemoryBytes: Long,
                blockSize: Int,
                assertions: Boolean,
                staleSampleRandom: Random,
                timeSource: kotlin.time.TimeSource,
            ) = DocumentBuffer(
                workspaceId = workspaceId,
                dataSource = dataSource,
                maxUndoStackSize = maxUndoStackSize,
                maxUndoMemoryBytes = maxUndoMemoryBytes,
                blockSize = docBlockSize,
                assertions = true,
            )
        }
        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = filePath,
            initialContent = content,
            gatewaySwitch = createMockGateway(),
            editorSettings = createMockSettings(),
            fileDataSourceFactory = fileFactory,
            inMemoryDataSourceFactory = inMemoryFactory,
            documentBufferFactory = documentBufferFactory,
        )
        engine.initialize().getOrThrow()
        return engine
    }

    @Test
    fun `type save type undo past save point`() = runTest {
        val engine = createEngine(content = "base")
        val buffer = engine.textBuffer!!

        engine.setCursorPosition(TextPosition(0, 0, 0))
        engine.insertText("11")
        engine.saveFile().isSuccess shouldBe true
        buffer.isModified.value shouldBe false

        engine.insertText("22")
        buffer.isModified.value shouldBe true

        engine.undo().isSuccess shouldBe true
        buffer.isModified.value shouldBe false
        buffer.getFullText().getOrThrow() shouldBe "11base"

        engine.undo().isSuccess shouldBe true
        buffer.isModified.value shouldBe true
        buffer.getFullText().getOrThrow() shouldBe "base"

        engine.saveFile().isSuccess shouldBe true
        buffer.isModified.value shouldBe false
    }

    @Test
    fun `engine isModified flow follows undo and redo across the save point`() = runTest {
        val engine = createEngine(content = "base")

        engine.setCursorPosition(TextPosition(0, 0, 0))
        engine.insertText("11")
        engine.saveFile().isSuccess shouldBe true
        engine.isModified.first() shouldBe false

        // The Loaded state's isModified is a snapshot: undo/redo must refresh it or auto-save
        // and the unsaved-changes close warning act on stale state
        engine.undo().isSuccess shouldBe true
        engine.isModified.first() shouldBe true

        engine.redo().isSuccess shouldBe true
        engine.isModified.first() shouldBe false
    }

    @Test
    fun `goToLine and range reads on a multibyte doc spanning many blocks`() = runTest {
        val lines = (0 until 20).map { "中文行$it" }
        val engine = createEngine(content = lines.joinToString("\n"), docBlockSize = 8)
        val buffer = engine.textBuffer!!

        engine.goToLine(15).isSuccess shouldBe true
        val cursor = engine.cursorPosition.value
        cursor.line shouldBe 15L
        cursor.column shouldBe 0
        cursor.offset shouldBe buffer.findOffset(15, 0)

        buffer.getTextForRange(5, 7).getOrThrow() shouldBe "中文行5\n中文行6\n中文行7"
        buffer.getTextForLine(19).getOrThrow() shouldBe "中文行19"
    }

    @Test
    fun `search results invalidate after edits`() = runTest {
        val engine = createEngine(content = "cat dog cat")

        engine.search("cat").getOrThrow().size shouldBe 2
        engine.searchState.value.results.size shouldBe 2

        engine.setCursorPosition(TextPosition(0, 0, 0))
        engine.insertText("X")

        engine.searchState.value.results.size shouldBe 0
    }

    @Test
    fun `truncated search state publishes as one value and resets on edit`() = runTest {
        val engine = createEngine(content = "cat ".repeat(5))
        engine.textBuffer!!.windowedSearchFactory = { readText ->
            WindowedSearch(maxResults = 3, readText = readText)
        }

        engine.search("cat").getOrThrow().size shouldBe 3
        val published = engine.searchState.value
        published.truncated shouldBe true
        published.results.map { it.position.offset } shouldBe listOf(0L, 4L, 8L)

        engine.setCursorPosition(TextPosition(0, 0, 0))
        engine.insertText("X")

        engine.searchState.value shouldBe EditorEngine.SearchState()
    }

    @Test
    fun `writeContentTo is byte-exact for a BOM file`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val content = "中文\r\nabc"
        val file = File(tempDir, "stream.txt").apply { writeBytes(bom + content.toByteArray()) }

        val engine = createEngine(filePath = LocalPath.build(file))
        val streamed = Buffer().also { engine.writeContentTo(it) }.readByteArray()

        streamed shouldBe bom + content.toByteArray()
    }

    @Test
    fun `writeContentTo reflects unsaved edits`(@TempDir tempDir: File) = runTest {
        val content = "hello world"
        val file = File(tempDir, "stream.txt").apply { writeBytes(content.toByteArray()) }

        val engine = createEngine(filePath = LocalPath.build(file))
        engine.setCursorPosition(TextPosition(0, 0, 0))
        engine.insertText("X")

        val streamed = Buffer().also { engine.writeContentTo(it) }.readByteArray()
        streamed shouldBe "Xhello world".toByteArray()
    }
}
