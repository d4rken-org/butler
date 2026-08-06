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
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.booleans.shouldBeTrue
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
import kotlin.uuid.Uuid
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Read-only and binary documents must reject mutations at the ENGINE level - the UI disables
 * input, but nothing (tests, future callers, IME quirks) may bypass that and corrupt content.
 */
class EditorEngineEditabilityTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())
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
        val syntaxHighlighting = mockk<DataStoreValue<Boolean>>()
        every { syntaxHighlighting.flow } returns flowOf(true)
        every { settings.syntaxHighlighting } returns syntaxHighlighting
        return settings
    }

    private fun createMockGateway(canWrite: Boolean): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { this@apply.canWrite(any()) } returns canWrite
        coEvery { exists(any()) } coAnswers { fileSystemOps.exists(firstArg<APath<*>>() as LocalPath) }
        @Suppress("UNCHECKED_CAST")
        coEvery { lookup(any(), any()) } coAnswers {
            fileSystemOps.lookup(firstArg<APath<*>>() as LocalPath, secondArg<LookupOptions>()) as APathLookup<APath<*>>
        }
        coEvery { file(any(), any()) } coAnswers {
            fileSystemOps.file(firstArg<APath<*>>() as LocalPath, secondArg<Boolean>())
        }
        @Suppress("UNCHECKED_CAST")
        coEvery { listFiles(any()) } coAnswers {
            fileSystemOps.listFiles(firstArg<APath<*>>() as LocalPath) as List<APath<*>>
        }
        coEvery { delete(any<APath<*>>()) } coAnswers { (firstArg<APath<*>>() as LocalPath).file.delete() }
    }

    private suspend fun createEngine(filePath: APath<*>, canWrite: Boolean): EditorEngine {
        val gateway = createMockGateway(canWrite)
        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = filePath,
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
                ) = DocumentBuffer(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, 1024, true)
            },
        )
        engine.initialize().getOrThrow()
        return engine
    }

    @Test
    fun `read-only files reject every mutation path`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "readonly.txt").apply { writeText("original") }
        val engine = createEngine(LocalPath.build(file), canWrite = false)

        engine.performInsert("X").shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()
        engine.applyFieldDelta(
            EditorEngine.FieldDelta(
                token = engine.visibleContent.value.token!!,
                start = TextPosition(0, 0, 0),
                end = TextPosition(0, 0, 0),
                oldText = "",
                newText = "X",
                caret = TextPosition(1, 0, 1),
            ),
        ).shouldBeInstanceOf<EditorEngine.MutationResult.Failed>()
        engine.performDeleteForward().shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()
        engine.performDeleteSelection().shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()

        engine.textBuffer!!.getFullText().getOrThrow() shouldBe "original"
        engine.isModified.first() shouldBe false
    }

    @Test
    fun `binary files open read-only and reject mutations and saves`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "image.bin").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x00, 0x01, 0x00, 0x42))
        }
        val engine = createEngine(LocalPath.build(file), canWrite = true)

        val source = engine.contentSource.first() as ContentSource.File
        source.isLikelyBinary.shouldBeTrue()

        engine.performInsert("X")
        engine.performDeleteForward().shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()
            .error.shouldBeInstanceOf<ReadOnlyFileException>()

        // Even a direct buffer edit cannot reach the disk
        val buffer = engine.textBuffer!!
        buffer.insertText(TextPosition(0, 0, 0), "X").getOrThrow()
        buffer.saveFile().exceptionOrNull().shouldBeInstanceOf<ReadOnlyFileException>()
        file.readBytes() shouldBe byteArrayOf(0x50, 0x4B, 0x00, 0x01, 0x00, 0x42)
    }
}
