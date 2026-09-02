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
 * Save-As at the workspace level: streaming into the CURRENT file would truncate the very
 * source the original byte ranges are read from, so save-as-to-self must route through the
 * atomic save path.
 */
class EditorWorkspaceSaveAsTest : BaseTest() {

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

    @Test
    fun `save-as to the current path uses the atomic save and keeps content intact`(@TempDir tempDir: File) = runTest {
        val content = "0123456789".repeat(10)
        val file = File(tempDir, "doc.txt").apply { writeText(content) }
        val path = LocalPath.build(file)
        val workspace = createWorkspace(path, createMockGateway())
        try {
            workspace.state.first {
                (it as? EditorWorkspace.State.Ready)?.editor?.contentSource is ContentSource.File
            }
            workspace.performInsert("X")
            workspace.state.first { (it as? EditorWorkspace.State.Ready)?.editor?.isModified == true }

            workspace.saveFileAs(path).isSuccess shouldBe true
            file.readText() shouldBe "X$content"
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `save-as to a different path streams the edited content`(@TempDir tempDir: File) = runTest {
        val content = "0123456789".repeat(10)
        val file = File(tempDir, "doc.txt").apply { writeText(content) }
        val target = File(tempDir, "copy.txt")
        val workspace = createWorkspace(LocalPath.build(file), createMockGateway())
        try {
            workspace.state.first {
                (it as? EditorWorkspace.State.Ready)?.editor?.contentSource is ContentSource.File
            }
            workspace.performInsert("X")
            workspace.state.first { (it as? EditorWorkspace.State.Ready)?.editor?.isModified == true }

            workspace.saveFileAs(LocalPath.build(target)).isSuccess shouldBe true
            target.readText() shouldBe "X$content"
            // The edits were redirected to the new file; the ORIGINAL must stay untouched
            // (the old engine is released without flushing)
            file.readText() shouldBe content
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `save-as failure leaves no partial target and stays on the original file`(@TempDir tempDir: File) = runTest {
        val content = "0123456789".repeat(10)
        val file = File(tempDir, "doc.txt").apply { writeText(content) }
        val target = File(tempDir, "copy.txt")
        val gateway = createMockGateway()
        // Writing the temp artifact for the target fails mid-stream
        coEvery {
            gateway.file(match { it.name.startsWith("copy.txt.butler-save-tmp-") }, true)
        } throws java.io.IOException("disk full")
        val workspace = createWorkspace(LocalPath.build(file), gateway)
        try {
            workspace.state.first {
                (it as? EditorWorkspace.State.Ready)?.editor?.contentSource is ContentSource.File
            }
            workspace.performInsert("X")
            workspace.state.first { (it as? EditorWorkspace.State.Ready)?.editor?.isModified == true }

            workspace.saveFileAs(LocalPath.build(target)).isFailure shouldBe true

            target.exists() shouldBe false
            file.readText() shouldBe content
            tempDir.listFiles()!!.map { it.name }.filter { it.contains(".butler-save-") } shouldBe emptyList()
            // Still on the original document with the edit intact
            val state = workspace.state.first() as EditorWorkspace.State.Ready
            (state.editor.contentSource as ContentSource.File).path.name shouldBe "doc.txt"
            state.editor.isModified shouldBe true
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `save-as over an existing file replaces its content atomically`(@TempDir tempDir: File) = runTest {
        val content = "source content"
        val file = File(tempDir, "doc.txt").apply { writeText(content) }
        val target = File(tempDir, "existing.txt").apply { writeText("OLD TARGET CONTENT") }
        val workspace = createWorkspace(LocalPath.build(file), createMockGateway())
        try {
            workspace.state.first {
                (it as? EditorWorkspace.State.Ready)?.editor?.contentSource is ContentSource.File
            }
            workspace.performInsert("X")
            workspace.state.first { (it as? EditorWorkspace.State.Ready)?.editor?.isModified == true }

            workspace.saveFileAs(LocalPath.build(target)).isSuccess shouldBe true

            target.readText() shouldBe "X$content"
            file.readText() shouldBe content
            tempDir.listFiles()!!.map { it.name }.filter { it.contains(".butler-save-") } shouldBe emptyList()
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `save-as turns a scratch buffer into a real file`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "untitled.txt")
        val workspace = createWorkspace(null, createMockGateway(), initialContent = "scratch content")
        try {
            workspace.state.first { it is EditorWorkspace.State.Ready }
            workspace.performInsert("X")
            workspace.state.first { (it as? EditorWorkspace.State.Ready)?.editor?.isModified == true }

            workspace.saveFileAs(LocalPath.build(target)).isSuccess shouldBe true

            target.readText() shouldBe "Xscratch content"
            // The workspace is now file-backed
            workspace.state.first {
                ((it as? EditorWorkspace.State.Ready)?.editor?.contentSource as? ContentSource.File)
                    ?.path?.name == "untitled.txt"
            }
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `inspectSaveAsTarget classifies destinations`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeText("content") }
        val existingFile = File(tempDir, "existing.txt").apply { writeText("x") }
        val existingDir = File(tempDir, "subdir").apply { mkdir() }
        val workspace = createWorkspace(LocalPath.build(file), createMockGateway())
        try {
            workspace.state.first {
                (it as? EditorWorkspace.State.Ready)?.editor?.contentSource is ContentSource.File
            }

            workspace.inspectSaveAsTarget(LocalPath.build(File(tempDir, "new.txt"))) shouldBe
                EditorWorkspace.SaveAsTarget.FREE
            workspace.inspectSaveAsTarget(LocalPath.build(existingFile)) shouldBe
                EditorWorkspace.SaveAsTarget.EXISTS_FILE
            workspace.inspectSaveAsTarget(LocalPath.build(existingDir)) shouldBe
                EditorWorkspace.SaveAsTarget.EXISTS_DIRECTORY
        } finally {
            workspace.release()
        }
    }
}
