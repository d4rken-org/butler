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

    private fun createMockSettings(): EditorSettings {
        val settings = mockk<EditorSettings>()
        fun <T> value(v: T): DataStoreValue<T> = mockk<DataStoreValue<T>>().apply {
            every { flow } returns flowOf(v)
        }
        every { settings.showLineNumbers } returns value(true)
        every { settings.wordWrap } returns value(false)
        every { settings.autoSaveEnabled } returns value(false)
        every { settings.autoSaveInterval } returns value(30.seconds)
        every { settings.undoStackSize } returns value(100)
        every { settings.undoMaxMemory } returns value(10 * 1_048_576L)
        return settings
    }

    private fun createWorkspace(filePath: APath<*>, gateway: GatewaySwitch): EditorWorkspace {
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
            creationArguments = EditorArguments.Default(filePath = filePath),
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
            workspace.insertText("X")
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
            workspace.insertText("X")
            workspace.state.first { (it as? EditorWorkspace.State.Ready)?.editor?.isModified == true }

            workspace.saveFileAs(LocalPath.build(target)).isSuccess shouldBe true
            target.readText() shouldBe "X$content"
            // Releasing the previous engine flushes its pending edits to the original (flush-on-close)
            file.readText() shouldBe "X$content"
        } finally {
            workspace.release()
        }
    }
}
