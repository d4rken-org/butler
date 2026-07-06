package eu.darken.butler.editor.core

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
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
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.nio.charset.Charset
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Workspace lifecycle behavior: engine-switch rollback, auto-save gating, and session-argument
 * round-trips - previously only the Save-As slice of this class was tested.
 */
class EditorWorkspaceLifecycleTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(this@apply)
        }
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

    private fun createMockSettings(
        autoSaveEnabled: Boolean = false,
        autoSaveInterval: Duration = 30.seconds,
    ): EditorSettings {
        val settings = mockk<EditorSettings>()
        fun <T> value(v: T): DataStoreValue<T> = mockk<DataStoreValue<T>>().apply {
            every { flow } returns flowOf(v)
        }
        every { settings.showLineNumbers } returns value(true)
        every { settings.wordWrap } returns value(false)
        every { settings.fontSize } returns value(14)
        every { settings.tabSize } returns value(4)
        every { settings.autoSaveEnabled } returns value(autoSaveEnabled)
        every { settings.autoSaveInterval } returns value(autoSaveInterval)
        every { settings.undoStackSize } returns value(100)
        every { settings.undoMaxMemory } returns value(10 * 1_048_576L)
        return settings
    }

    private fun createWorkspace(
        arguments: EditorArguments.Default,
        gateway: GatewaySwitch,
        settings: EditorSettings = createMockSettings(),
    ): EditorWorkspace {
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
            gatewaySwitch = gateway,
            editorEngineFactory = engineFactory,
            editorSettings = settings,
            operationsManager = operationsManager,
            pasteFileReader = PasteFileReader(gateway),
        )
    }

    private suspend fun EditorWorkspace.awaitFile(name: String) {
        withTimeout(10_000) {
            state.first {
                ((it as? EditorWorkspace.State.Ready)?.editor?.contentSource as? ContentSource.File)
                    ?.path?.name == name
            }
        }
    }

    @Test
    fun `failed engine switch keeps the current document usable`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(file)),
            createMockGateway(),
        )
        try {
            workspace.awaitFile("doc.txt")
            workspace.insertText("X")

            runCatching { workspace.openFile(LocalPath.build(File(tempDir, "missing.txt"))) }
                .isFailure.shouldBeTrue()

            // Old engine restored: still on doc.txt with the pending edit intact. Await it -
            // the state flow re-subscribes to the restored engine asynchronously.
            withTimeout(10_000) {
                workspace.state.first {
                    val editor = (it as? EditorWorkspace.State.Ready)?.editor
                    (editor?.contentSource as? ContentSource.File)?.path?.name == "doc.txt" &&
                        editor.isModified
                }
            }
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `createArguments round-trips path and charset override`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(file), charsetOverride = "ISO-8859-1"),
            createMockGateway(),
        )
        try {
            workspace.awaitFile("doc.txt")

            val arguments = workspace.createArguments() as EditorArguments.Default

            arguments.filePath?.name shouldBe "doc.txt"
            arguments.charsetOverride shouldBe "ISO-8859-1"
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `auto-save persists modifications after the interval`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(file)),
            createMockGateway(),
            createMockSettings(autoSaveEnabled = true, autoSaveInterval = 150.milliseconds),
        )
        try {
            workspace.awaitFile("doc.txt")
            workspace.insertText("X")

            withTimeout(10_000) {
                while (file.readText() != "Xoriginal") delay(50)
            }
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `auto-save stays silent when disabled`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(file)),
            createMockGateway(),
            createMockSettings(autoSaveEnabled = false, autoSaveInterval = 100.milliseconds),
        )
        try {
            workspace.awaitFile("doc.txt")
            workspace.insertText("X")

            delay(500)
            file.readText() shouldBe "original"
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `failed file init stays in error state`(@TempDir tempDir: File): Unit = runBlocking {
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(File(tempDir, "missing.txt"))),
            createMockGateway(),
        )
        try {
            withTimeout(10_000) { workspace.state.first { it is EditorWorkspace.State.Error } }

            // Poke an engine state flow: the resulting internal emission must not flip the
            // tab back to a Ready scratch view
            runCatching { workspace.updateVisibleRange(0, 10) }
            delay(200)

            (workspace.state.value is EditorWorkspace.State.Error).shouldBeTrue()
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `createArguments keeps file identity and session state after failed init`(
        @TempDir tempDir: File,
    ): Unit = runBlocking {
        val missing = LocalPath.build(File(tempDir, "missing.txt"))
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = missing, cursorLine = 5L, cursorColumn = 2),
            createMockGateway(),
        )
        try {
            withTimeout(10_000) { workspace.state.first { it is EditorWorkspace.State.Error } }

            val arguments = workspace.createArguments() as EditorArguments.Default

            arguments.filePath shouldBe missing
            arguments.cursorLine shouldBe 5L
            arguments.cursorColumn shouldBe 2
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `cancelled initial file load persists as a scratch tab`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val lookupReached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gateway = createMockGateway().apply {
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
            EditorArguments.Default(filePath = LocalPath.build(file)),
            gateway,
        )
        try {
            // The engine is provably mid-initialization once the gated lookup is reached
            withTimeout(10_000) { lookupReached.await() }
            workspace.cancelFileOpen()

            // The user aborted the load; the persisted tab must not resurrect it on restore
            (workspace.createArguments() as EditorArguments.Default).filePath shouldBe null
        } finally {
            gate.complete(Unit)
            workspace.release()
        }
    }

    @Test
    fun `createArguments keeps a scratch tab scratch`(): Unit = runBlocking {
        val workspace = createWorkspace(
            EditorArguments.Default(initialContent = "draft"),
            createMockGateway(),
        )
        try {
            withTimeout(10_000) {
                workspace.state.first {
                    (it as? EditorWorkspace.State.Ready)?.editor?.contentSource is ContentSource.Memory
                }
            }

            val arguments = workspace.createArguments() as EditorArguments.Default

            arguments.filePath shouldBe null
        } finally {
            workspace.release()
        }
    }

}
