package eu.darken.butler.editor.core

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.errors.PathNotFoundException
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
import io.kotest.matchers.types.shouldBeInstanceOf
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
import testhelpers.coroutine.TestDispatcherProvider
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
        every { settings.syntaxHighlighting } returns value(true)
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
            workspace.performInsert("X")

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
    fun `a file-backed tab can be paused`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(file)),
            createMockGateway(),
        )
        try {
            workspace.awaitFile("doc.txt")
            // The content-source collector runs asynchronously, so wait for it to have seen the file
            withTimeout(10_000) { workspace.info.first { it.isPausable } }
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a scratch buffer must not be paused`(): Unit = runBlocking {
        // createArguments() drops initialContent, so releasing this tab would throw the text away
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = null, initialContent = "unsaved scratch text"),
            createMockGateway(),
        )
        try {
            withTimeout(10_000) {
                workspace.state.first {
                    (it as? EditorWorkspace.State.Ready)?.editor?.contentSource is ContentSource.Memory
                }
            }

            workspace.info.value.isPausable shouldBe false
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a file whose backing was lost must not be paused`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(file)),
            createMockGateway(),
        )
        try {
            workspace.awaitFile("doc.txt")
            // The content-source collector runs asynchronously, so wait for it to have seen the file
            withTimeout(10_000) { workspace.info.first { it.isPausable } }

            // Once the open file is deleted, moved or becomes unreadable the in-memory document is
            // the only copy left, so it must not be released.
            file.delete() shouldBe true
            workspace.checkExternalChange()

            withTimeout(10_000) { workspace.info.first { !it.isPausable } }
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
            workspace.performInsert("X")

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
            workspace.performInsert("X")

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
            val errorState = withTimeout(10_000) {
                workspace.state.first { it is EditorWorkspace.State.Error }
            } as EditorWorkspace.State.Error

            // The type is what routes the tab to the "file is gone" screen rather than the
            // generic report-this-error one, so it is part of the contract, not an implementation
            // detail of FileDataSource
            errorState.error.shouldBeInstanceOf<PathNotFoundException>()

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
    fun `auto-save skips scratch buffers`(): Unit = runBlocking {
        val workspace = createWorkspace(
            EditorArguments.Default(initialContent = "draft"),
            createMockGateway(),
            createMockSettings(autoSaveEnabled = true, autoSaveInterval = 100.milliseconds),
        )
        try {
            withTimeout(10_000) {
                workspace.state.first {
                    (it as? EditorWorkspace.State.Ready)?.editor?.contentSource is ContentSource.Memory
                }
            }
            workspace.performInsert("X")
            withTimeout(10_000) {
                workspace.state.first { (it as? EditorWorkspace.State.Ready)?.editor?.isModified == true }
            }

            // An in-memory "save" would persist nothing but clear the modified flag,
            // disabling the Save/Save-As actions - the flag must survive the interval
            delay(500)
            (workspace.state.value as EditorWorkspace.State.Ready).editor.isModified shouldBe true
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

    @Test
    fun `reload from disk shows the external content and drops modifications`(@TempDir tempDir: File): Unit =
        runBlocking {
            val file = File(tempDir, "doc.txt").apply { writeText("original") }
            val workspace = createWorkspace(
                EditorArguments.Default(filePath = LocalPath.build(file)),
                createMockGateway(),
            )
            try {
                workspace.awaitFile("doc.txt")
                workspace.performInsert("X")

                file.writeText("externally changed content")
                workspace.checkExternalChange()
                withTimeout(10_000) {
                    workspace.state.first {
                        (it as? EditorWorkspace.State.Ready)?.editor?.externalChange != null
                    }
                }

                workspace.reloadFromDisk()

                withTimeout(10_000) {
                    workspace.state.first {
                        val editor = (it as? EditorWorkspace.State.Ready)?.editor
                        editor?.currentContent == "externally changed content" &&
                            !editor.isModified &&
                            editor.externalChange == null
                    }
                }
            } finally {
                workspace.release()
            }
        }

    @Test
    fun `auto-save pauses while an external change is flagged`(@TempDir tempDir: File): Unit = runBlocking {
        val file = File(tempDir, "doc.txt").apply { writeText("original") }
        val workspace = createWorkspace(
            EditorArguments.Default(filePath = LocalPath.build(file)),
            createMockGateway(),
            createMockSettings(autoSaveEnabled = true, autoSaveInterval = 100.milliseconds),
        )
        try {
            workspace.awaitFile("doc.txt")

            file.writeText("externally changed content")
            workspace.checkExternalChange()
            withTimeout(10_000) {
                workspace.state.first {
                    (it as? EditorWorkspace.State.Ready)?.editor?.externalChange != null
                }
            }

            workspace.performInsert("X")
            withTimeout(10_000) {
                workspace.state.first { (it as? EditorWorkspace.State.Ready)?.editor?.isModified == true }
            }

            // No auto-save may fire: it would be refused by the staleness guard and surface a
            // failed-save error on every interval. No attempt = no error and untouched file.
            delay(500)
            val editor = (workspace.state.value as EditorWorkspace.State.Ready).editor
            editor.error shouldBe null
            file.readText() shouldBe "externally changed content"
        } finally {
            workspace.release()
        }
    }

}
