package eu.darken.butler.editor.core

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
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.DocumentBuffer
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.UnmappableCharactersException
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Workspace lifecycle of an encoding conversion: the file is rewritten by the old engine, then
 * reopened by a fresh one. Once the commit landed the old engine can't serve the file, so a failed
 * or cancelled reopen must never fall back to it.
 */
class EditorWorkspaceConvertEncodingTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)
    private val gb18030 = TextCharsets.GB18030

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(this@apply)
        }
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

    private fun failingGateway(): GatewaySwitch = createMockGateway().apply {
        coEvery { lookup(any(), any()) } throws IOException("injected reopen failure")
    }

    private fun createMockSettings(): EditorSettings {
        val settings = mockk<EditorSettings>()
        fun <T> value(v: T): DataStoreValue<T> = mockk<DataStoreValue<T>>().apply {
            every { flow } returns flowOf(v)
        }
        every { settings.showLineNumbers } returns value(true)
        every { settings.wordWrap } returns value(false)
        every { settings.fontSize } returns value(14)
        every { settings.tabSize } returns value(4)
        every { settings.autoSaveEnabled } returns value(false)
        every { settings.autoSaveInterval } returns value(30.seconds)
        every { settings.undoStackSize } returns value(100)
        every { settings.undoMaxMemory } returns value(10 * 1_048_576L)
        every { settings.syntaxHighlighting } returns value(true)
        return settings
    }

    /** [gatewayForEngine] picks the gateway per engine, by creation order starting at 0. */
    private fun createWorkspace(
        arguments: EditorArguments.Default,
        gatewayForEngine: (Int) -> GatewaySwitch = { createMockGateway() },
    ): EditorWorkspace {
        val settings = createMockSettings()
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
                maxDisplayLineChars: Int,
            ) = DocumentBuffer(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, 10, true)
        }
        var created = 0
        val engineFactory = object : EditorEngine.Factory {
            override fun create(
                workspaceId: Workspace.Id,
                filePath: APath<*>?,
                initialContent: String?,
                charsetOverride: Charset?,
            ) = EditorEngine(
                workspaceId = workspaceId,
                filePath = filePath,
                initialContent = initialContent,
                charsetOverride = charsetOverride,
                gatewaySwitch = gatewayForEngine(created++),
                editorSettings = settings,
                dispatcherProvider = TestDispatcherProvider(),
                fileDataSourceFactory = fileFactory,
                inMemoryDataSourceFactory = inMemoryFactory,
                documentBufferFactory = documentBufferFactory,
            )
        }
        val operationsManager = mockk<OperationsManager>().apply {
            every { operations } returns flowOf(emptyList())
        }
        return EditorWorkspace(
            id = workspaceId,
            creationArguments = arguments,
            gatewaySwitch = createMockGateway(),
            editorEngineFactory = engineFactory,
            editorSettings = settings,
            operationsManager = operationsManager,
            pasteFileReader = PasteFileReader(createMockGateway()),
        )
    }

    private suspend fun EditorWorkspace.awaitEditor(
        predicate: (EditorWorkspace.EditorState) -> Boolean,
    ): EditorWorkspace.EditorState = withTimeout(10_000) {
        state.first { (it as? EditorWorkspace.State.Ready)?.editor?.let(predicate) == true }
    }.let { (it as EditorWorkspace.State.Ready).editor }

    private val EditorWorkspace.EditorState.charset: Charset?
        get() = (contentSource as? ContentSource.File)?.detectedCharset

    private val text = "第一行你好ʘ😀\n第二行😀ʘ结束\n"

    /** A GB18030 tab with one unsaved edit at the start; returns the expected document text. */
    private suspend fun EditorWorkspace.openWithEdit(): String {
        awaitEditor { it.charset == gb18030 }
        performInsert("X")
        awaitEditor { it.isModified }
        return "X$text"
    }

    private suspend fun EditorWorkspace.assertNotEditable() {
        performEdit(EditorEngine.EditIntent.InsertAtCursor("Y"), Uuid.random())
            .shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()
    }

    @Test
    fun `a conversion reopens the file with the target charset`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeBytes(text.toByteArray(gb18030)) }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(file), charsetOverride = "GB18030"),
        )
        try {
            val expected = workspace.openWithEdit()

            workspace.convertEncoding("UTF-8").getOrThrow()

            file.readBytes() shouldBe expected.toByteArray(Charsets.UTF_8)
            val editor = workspace.awaitEditor { it.charset == Charsets.UTF_8 && it.currentContent == expected }
            editor.isModified shouldBe false
            editor.pendingReopen.shouldBeNull()
            (workspace.createArguments() as EditorArguments.Default).charsetOverride shouldBe "UTF-8"
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a refused conversion keeps the document editable`(@TempDir tempDir: File): Unit = runBlocking {
        val bytes = text.toByteArray(gb18030)
        val file = File(tempDir, "doc.txt").apply { writeBytes(bytes) }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(file), charsetOverride = "GB18030"),
        )
        try {
            workspace.openWithEdit()

            workspace.convertEncoding("ISO-8859-1").exceptionOrNull()
                .shouldBeInstanceOf<UnmappableCharactersException>()

            file.readBytes() shouldBe bytes
            val editor = workspace.awaitEditor { it.error != null }
            editor.charset shouldBe gb18030
            editor.isModified shouldBe true
            workspace.performInsert("Z").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()
            (workspace.createArguments() as EditorArguments.Default).charsetOverride shouldBe "GB18030"
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a failed reopen stays read-only until the retry succeeds`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeBytes(text.toByteArray(gb18030)) }
        val path = LocalPath.build(file)
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = path, charsetOverride = "GB18030"),
            gatewayForEngine = { index -> if (index == 1) failingGateway() else createMockGateway() },
        )
        try {
            val expected = workspace.openWithEdit()

            workspace.convertEncoding("UTF-8").isFailure.shouldBeTrue()

            file.readBytes() shouldBe expected.toByteArray(Charsets.UTF_8)
            val failed = workspace.awaitEditor { it.pendingReopen != null }
            failed.pendingReopen shouldBe EditorWorkspace.PendingReopen(path, Charsets.UTF_8)
            // Never the old engine: it would show the converted bytes through the GB18030 index
            (failed.contentSource is ContentSource.File) shouldBe false
            workspace.assertNotEditable()
            (workspace.createArguments() as EditorArguments.Default).apply {
                filePath shouldBe path
                charsetOverride shouldBe "UTF-8"
            }

            workspace.reloadFromDisk()

            val reopened = workspace.awaitEditor { it.charset == Charsets.UTF_8 && it.currentContent == expected }
            reopened.pendingReopen.shouldBeNull()
            workspace.performInsert("Z").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a cancelled reopen stays read-only with the file kept`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeBytes(text.toByteArray(gb18030)) }
        val path = LocalPath.build(file)
        val lookupReached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gatedGateway = createMockGateway().apply {
            coEvery { lookup(any(), any()) } coAnswers {
                lookupReached.complete(Unit)
                gate.await()
                @Suppress("UNCHECKED_CAST")
                fileSystemOps.lookup(
                    firstArg<APath<*>>() as LocalPath,
                    secondArg<LookupOptions>(),
                ) as APathLookup<APath<*>>
            }
        }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = path, charsetOverride = "GB18030"),
            gatewayForEngine = { index -> if (index == 1) gatedGateway else createMockGateway() },
        )
        try {
            val expected = workspace.openWithEdit()

            val conversion = launch { workspace.convertEncoding("UTF-8") }
            withTimeout(10_000) { lookupReached.await() }
            workspace.cancelFileOpen()
            conversion.join()

            file.readBytes() shouldBe expected.toByteArray(Charsets.UTF_8)
            workspace.awaitEditor { it.pendingReopen != null }
            workspace.assertNotEditable()
            // Unlike a cancelled plain open, the converted file stays this tab's file
            (workspace.createArguments() as EditorArguments.Default).apply {
                filePath shouldBe path
                charsetOverride shouldBe "UTF-8"
            }
        } finally {
            gate.complete(Unit)
            workspace.release()
        }
    }
}
