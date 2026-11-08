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
import eu.darken.butler.common.files.text.TextCharsets
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
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
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.nio.charset.Charset
import kotlin.random.Random

/** Engine side of encoding conversion: refusals surface as errors, success retires the engine. */
class EditorEngineConvertEncodingTest : BaseTest() {

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
        every { settings.syntaxHighlighting } returns value(true)
        return settings
    }

    private suspend fun createEngine(file: File, charsetOverride: Charset): EditorEngine = EditorEngine(
        workspaceId = workspaceId,
        filePath = LocalPath.build(file),
        initialContent = null,
        charsetOverride = charsetOverride,
        gatewaySwitch = createMockGateway(),
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

    private val text = "第一行你好ʘ😀\n第二行"

    @Test
    fun `edits on a converted engine are refused`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeBytes(text.toByteArray(TextCharsets.GB18030)) }
        val engine = createEngine(file, TextCharsets.GB18030)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.performInsert("X")
        val token = engine.visibleContent.value.token!!

        engine.convertEncoding(Charsets.UTF_8).getOrThrow()
        file.readBytes() shouldBe "X$text".toByteArray(Charsets.UTF_8)

        engine.performInsert("Y").shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()
            .error.shouldBeInstanceOf<ReadOnlyFileException>()
        engine.applyFieldDelta(
            EditorEngine.FieldDelta(
                token = token,
                start = TextPosition(offset = 0, line = 0, column = 0),
                end = TextPosition(offset = 0, line = 0, column = 0),
                oldText = "",
                newText = "Z",
                caret = TextPosition(offset = 1, line = 0, column = 1),
            ),
        ).shouldBeInstanceOf<EditorEngine.MutationResult.Failed>()
        engine.saveFile().isFailure shouldBe true
        engine.isModified.first() shouldBe false
        file.readBytes() shouldBe "X$text".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `a refused conversion reports the error and stays editable`(@TempDir tempDir: File) = runTest {
        val bytes = text.toByteArray(TextCharsets.GB18030)
        val file = File(tempDir, "doc.txt").apply { writeBytes(bytes) }
        val engine = createEngine(file, TextCharsets.GB18030)

        val result = engine.convertEncoding(Charsets.ISO_8859_1)

        result.exceptionOrNull().shouldBeInstanceOf<UnmappableCharactersException>()
            .charset shouldBe Charsets.ISO_8859_1
        engine.error.value.shouldBeInstanceOf<UnmappableCharactersException>()
        file.readBytes() shouldBe bytes
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.performInsert("X").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()
        engine.saveFile().getOrThrow()
        file.readBytes() shouldBe "X$text".toByteArray(TextCharsets.GB18030)
    }

    @Test
    fun `undecodable bytes refuse the conversion`(@TempDir tempDir: File) = runTest {
        val bytes = "head ".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + " tail".toByteArray()
        val file = File(tempDir, "doc.txt").apply { writeBytes(bytes) }
        val engine = createEngine(file, Charsets.UTF_8)

        engine.convertEncoding(Charsets.UTF_16LE).exceptionOrNull()
            .shouldBeInstanceOf<UndecodableBytesException>()
        engine.error.value.shouldBeInstanceOf<UndecodableBytesException>()
        file.readBytes() shouldBe bytes
    }
}
