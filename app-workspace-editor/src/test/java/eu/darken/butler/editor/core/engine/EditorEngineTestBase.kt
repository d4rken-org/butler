package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlin.random.Random

/** Shared harness for tests running a full [EditorEngine] over an in-memory document. */
abstract class EditorEngineTestBase : DocumentBufferTestBase() {

    protected fun createMockSettings(undoMaxMemoryBytes: Long = 10 * 1_048_576L): EditorSettings {
        val settings = mockk<EditorSettings>()
        val undoStackSize = mockk<DataStoreValue<Int>>()
        val undoMaxMemory = mockk<DataStoreValue<Long>>()
        every { undoStackSize.flow } returns flowOf(100)
        every { undoMaxMemory.flow } returns flowOf(undoMaxMemoryBytes)
        every { settings.undoStackSize } returns undoStackSize
        every { settings.undoMaxMemory } returns undoMaxMemory
        return settings
    }

    protected suspend fun createEngine(
        content: String,
        displayLineCap: Int = DocumentBuffer.MAX_DISPLAY_LINE_CHARS,
        undoMaxMemoryBytes: Long = 10 * 1_048_576L,
    ): EditorEngine {
        val inMemoryDataSourceFactory = object : InMemoryDataSource.Factory {
            override fun create(workspaceId: Workspace.Id, initialContent: String) =
                InMemoryDataSource(workspaceId, initialContent)
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
            ) = DocumentBuffer(
                workspaceId,
                dataSource,
                maxUndoStackSize,
                maxUndoMemoryBytes,
                blockSize,
                true,
                staleSampleRandom,
                maxDisplayLineChars = displayLineCap,
            )
        }
        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = null,
            initialContent = content,
            gatewaySwitch = mockk(),
            editorSettings = createMockSettings(undoMaxMemoryBytes),
            fileDataSourceFactory = mockk(),
            inMemoryDataSourceFactory = inMemoryDataSourceFactory,
            documentBufferFactory = documentBufferFactory,
        )
        engine.initialize().getOrThrow()
        return engine
    }
}
