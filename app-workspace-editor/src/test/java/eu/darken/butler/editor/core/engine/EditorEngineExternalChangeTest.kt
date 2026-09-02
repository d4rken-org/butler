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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.nio.charset.Charset
import kotlin.random.Random
import testhelpers.coroutine.TestDispatcherProvider

/**
 * External-change detection: the poll-side meta probe ([EditorEngine.checkExternalChange]) and
 * the save-time flagging that backs the "file changed on disk" banner.
 */
class EditorEngineExternalChangeTest : BaseTest() {

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
        content: String? = null,
        filePath: APath<*>? = null,
        gateway: GatewaySwitch = mockk(),
    ): EditorEngine = EditorEngine(
        workspaceId = workspaceId,
        filePath = filePath,
        initialContent = content,
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

    @Test
    fun `poll flags a size change with the observed meta`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val engine = createEngine(filePath = LocalPath.build(file), gateway = createMockGateway())

        engine.checkExternalChange()
        engine.externalChange.value.shouldBeNull()

        file.writeText("original plus external growth")
        engine.checkExternalChange()

        val flagged = engine.externalChange.value.shouldNotBeNull()
        flagged.generation shouldBe 1
        flagged.observedMeta.shouldNotBeNull().size shouldBe file.length()
    }

    @Test
    fun `same observation keeps the generation, a further change bumps it`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val engine = createEngine(filePath = LocalPath.build(file), gateway = createMockGateway())

        file.writeText("changed once, size differs")
        engine.checkExternalChange()
        engine.checkExternalChange()
        engine.externalChange.value.shouldNotBeNull().generation shouldBe 1

        file.writeText("changed twice, size differs even more")
        engine.checkExternalChange()
        engine.externalChange.value.shouldNotBeNull().generation shouldBe 2
    }

    @Test
    fun `restored file clears a meta-based flag`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val originalModified = file.lastModified()
        val engine = createEngine(filePath = LocalPath.build(file), gateway = createMockGateway())

        file.writeText("temporarily different content")
        engine.checkExternalChange()
        engine.externalChange.value.shouldNotBeNull()

        file.writeText("original")
        file.setLastModified(originalModified)
        engine.checkExternalChange()

        engine.externalChange.value.shouldBeNull()
    }

    @Test
    fun `failed save due to external change flags it`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val engine = createEngine(filePath = LocalPath.build(file), gateway = createMockGateway())

        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.performInsert("X")
        file.writeText("externally grown content")

        val result = engine.saveFile()

        result.exceptionOrNull().shouldBeInstanceOf<ExternalModificationException>()
        // The cheap meta is captured so the next poll keeps the generation
        val flagged = engine.externalChange.value.shouldNotBeNull()
        flagged.observedMeta.shouldNotBeNull().size shouldBe file.length()
        engine.checkExternalChange()
        engine.externalChange.value.shouldNotBeNull().generation shouldBe flagged.generation
    }

    @Test
    fun `deleting the file does not clear an existing flag`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val engine = createEngine(filePath = LocalPath.build(file), gateway = createMockGateway())

        file.writeText("externally grown content")
        engine.checkExternalChange()
        val flagged = engine.externalChange.value.shouldNotBeNull()

        file.delete()
        engine.checkExternalChange()

        engine.externalChange.value shouldBe flagged
    }

    @Test
    fun `successful save clears the flag`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val originalModified = file.lastModified()
        val engine = createEngine(filePath = LocalPath.build(file), gateway = createMockGateway())

        file.writeText("temporarily different content")
        engine.checkExternalChange()
        engine.externalChange.value.shouldNotBeNull()

        // The external change was undone before the user saved; the save re-baselines
        file.writeText("original")
        file.setLastModified(originalModified)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.performInsert("X")
        engine.saveFile().getOrThrow()

        engine.externalChange.value.shouldBeNull()
        file.readText() shouldBe "Xoriginal"
    }

    @Test
    fun `scratch buffers never flag`() = runTest {
        val engine = createEngine(content = "draft")

        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.performInsert("X")
        engine.checkExternalChange()

        engine.externalChange.value.shouldBeNull()
    }

    @Test
    fun `writeContentTo refuses and flags when the file changed on disk`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val engine = createEngine(filePath = LocalPath.build(file), gateway = createMockGateway())

        file.writeText("externally grown content")

        val result = runCatching { engine.writeContentTo(Buffer()) }

        result.exceptionOrNull().shouldBeInstanceOf<ExternalModificationException>()
        engine.externalChange.value.shouldNotBeNull()
    }

    @Test
    fun `writeContentTo still streams unchanged and scratch documents`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val fileEngine = createEngine(filePath = LocalPath.build(file), gateway = createMockGateway())
        val sink = Buffer()
        fileEngine.writeContentTo(sink)
        sink.readUtf8() shouldBe "original"

        val scratchEngine = createEngine(content = "draft")
        scratchEngine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        scratchEngine.performInsert("X")
        val scratchSink = Buffer()
        scratchEngine.writeContentTo(scratchSink)
        scratchSink.readUtf8() shouldBe "Xdraft"
    }
}
