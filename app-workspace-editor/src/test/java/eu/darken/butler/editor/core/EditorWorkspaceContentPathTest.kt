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
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.DocumentBuffer
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
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
import kotlin.time.Duration.Companion.seconds

/**
 * [Workspace.Info.contentPath] lifecycle: it is the identity per-path open dedup keys on, so it
 * must be visible synchronously at construction and track every engine switch.
 */
class EditorWorkspaceContentPathTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

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
        coEvery { listFiles(any<APath<*>>()) } returns emptyList()
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

    private fun createWorkspace(
        filePath: APath<*>?,
        gateway: GatewaySwitch,
        initialContent: String? = null,
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
                gatewaySwitch = gateway,
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
            creationArguments = EditorArguments.Default(filePath = filePath, initialContent = initialContent),
            gatewaySwitch = gateway,
            editorEngineFactory = engineFactory,
            editorSettings = settings,
            operationsManager = operationsManager,
            pasteFileReader = PasteFileReader(gateway),
        )
    }

    private suspend fun EditorWorkspace.awaitFileLoaded() {
        state.first { (it as? EditorWorkspace.State.Ready)?.editor?.contentSource is ContentSource.File }
    }

    @Test
    fun `contentPath is seeded from creation arguments before the engine loads`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("content") }
        val path = LocalPath.build(file)
        val workspace = createWorkspace(path, createMockGateway())
        try {
            // Synchronous visibility: no awaiting Ready - this is what in-batch dedup relies on
            workspace.info.value.contentPath shouldBe path
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `scratch tabs publish no contentPath`() = runTest {
        val workspace = createWorkspace(null, createMockGateway(), initialContent = "scratch")
        try {
            workspace.info.value.contentPath shouldBe null
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `openFile updates contentPath to the new file`(@TempDir tempDir: File) = runTest {
        val fileA = File(tempDir, "a.txt").apply { writeText("aaa") }
        val fileB = File(tempDir, "b.txt").apply { writeText("bbb") }
        val workspace = createWorkspace(LocalPath.build(fileA), createMockGateway())
        try {
            workspace.awaitFileLoaded()
            workspace.openFile(LocalPath.build(fileB))
            workspace.info.value.contentPath shouldBe LocalPath.build(fileB)
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `saveFileAs moves contentPath to the destination`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("content") }
        val target = File(tempDir, "copy.txt")
        val workspace = createWorkspace(LocalPath.build(file), createMockGateway())
        try {
            workspace.awaitFileLoaded()
            workspace.performInsert("X")
            workspace.state.first { (it as? EditorWorkspace.State.Ready)?.editor?.isModified == true }

            workspace.saveFileAs(LocalPath.build(target)).isSuccess shouldBe true
            workspace.info.value.contentPath shouldBe LocalPath.build(target)
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `closeFile clears contentPath`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("content") }
        val workspace = createWorkspace(LocalPath.build(file), createMockGateway())
        try {
            workspace.awaitFileLoaded()
            workspace.closeFile()
            workspace.info.value.contentPath shouldBe null
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `reopenWithCharset keeps contentPath`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("content") }
        val path = LocalPath.build(file)
        val workspace = createWorkspace(path, createMockGateway())
        try {
            workspace.awaitFileLoaded()
            workspace.reopenWithCharset("ISO-8859-1")
            workspace.awaitFileLoaded()
            workspace.info.value.contentPath shouldBe path
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `cancelled initial load clears contentPath`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("content") }
        val path = LocalPath.build(file)
        val gateway = createMockGateway()
        // The load hangs on the first gateway access, so cancellation deterministically lands
        // mid-initialization. That access is the lookup: open() attempts the real read and only
        // probes existence afterwards, to tell a deleted file apart from an unreadable one.
        coEvery { gateway.lookup(any(), any()) } coAnswers { awaitCancellation() }
        val workspace = createWorkspace(path, gateway)
        try {
            // Seeded identity is visible while the load is stuck
            workspace.info.value.contentPath shouldBe path

            // The engine is created asynchronously; retry until the cancel connects
            workspace.info.first { info ->
                workspace.cancelFileOpen()
                info.contentPath == null
            }
        } finally {
            workspace.release()
        }
    }
}
