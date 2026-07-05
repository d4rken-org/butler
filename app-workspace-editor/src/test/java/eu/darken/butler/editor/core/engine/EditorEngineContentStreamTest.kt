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
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import java.nio.charset.Charset

/**
 * Tests for [EditorEngine.getContentStream], which backs "Save As".
 *
 * Regression guard: the stream must reflect the CURRENT (edited) buffer content, not the stale
 * on-disk original, and must preserve the detected charset/BOM for file-backed documents.
 */
class EditorEngineContentStreamTest : DocumentBufferTestBase() {

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

    private val inMemoryDataSourceFactory = object : InMemoryDataSource.Factory {
        override fun create(workspaceId: Workspace.Id, initialContent: String) =
            InMemoryDataSource(workspaceId, initialContent)
    }
    private val fileDataSourceFactory = object : FileDataSource.Factory {
        override fun create(
            workspaceId: Workspace.Id,
            filePath: APath<*>,
            gatewaySwitch: GatewaySwitch,
            charsetOverride: Charset?,
        ) = FileDataSource(workspaceId, filePath, gatewaySwitch, charsetOverride)
    }
    private val documentBufferFactory = object : DocumentBuffer.Factory {
        override fun create(
            workspaceId: Workspace.Id,
            dataSource: EditorDataSource,
            maxUndoStackSize: Int,
            maxUndoMemoryBytes: Long,
            blockSize: Int,
            assertions: Boolean,
            staleSampleRandom: Random,
        ) = DocumentBuffer(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, blockSize, true, staleSampleRandom)
    }

    private fun createReadOnlyGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { canWrite(any()) } returns true
        coEvery { exists(any()) } coAnswers { fileSystemOps.exists(firstArg<APath<*>>() as LocalPath) }
        @Suppress("UNCHECKED_CAST")
        coEvery { lookup(any(), any()) } coAnswers {
            fileSystemOps.lookup(firstArg<APath<*>>() as LocalPath, secondArg<LookupOptions>()) as APathLookup<APath<*>>
        }
        coEvery { file(any(), any()) } coAnswers {
            fileSystemOps.file(firstArg<APath<*>>() as LocalPath, secondArg<Boolean>())
        }
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
        fileDataSourceFactory = fileDataSourceFactory,
        inMemoryDataSourceFactory = inMemoryDataSourceFactory,
        documentBufferFactory = documentBufferFactory,
    ).apply { initialize().getOrThrow() }

    @Test
    fun `getContentStream returns the edited buffer content, not the stale original`() = runTest {
        val engine = createEngine(content = "Hello")
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))
        engine.insertText(" World")

        val streamed = engine.getContentStream().buffer().use { it.readUtf8() }

        streamed shouldBe "Hello World"
    }

    @Test
    fun `getContentStream preserves the BOM for a file-backed document`(@TempDir tempDir: File) = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = File(tempDir, "bom.txt").apply { writeBytes(bom + "Hello".toByteArray()) }
        val engine = createEngine(filePath = LocalPath.build(file), gateway = createReadOnlyGateway())

        val streamed = engine.getContentStream().buffer().use { it.readByteArray() }

        streamed.toList() shouldBe (bom + "Hello".toByteArray()).toList()
    }
}
