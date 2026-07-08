package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * copySelection with a size cap: the refusal happens under the engine lock BEFORE the selection
 * is materialized, so oversized copies never allocate the selection text.
 */
class EditorEngineCopySelectionTest : DocumentBufferTestBase() {

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

    private suspend fun createEngine(content: String): EditorEngine {
        val inMemoryDataSourceFactory = object : InMemoryDataSource.Factory {
            override fun create(workspaceId: Workspace.Id, initialContent: String) =
                InMemoryDataSource(workspaceId, initialContent)
        }
        val documentBufferFactory = object : DocumentBuffer.Factory {
            override fun create(
                workspaceId: Workspace.Id,
                dataSource: eu.darken.butler.editor.core.sources.EditorDataSource,
                maxUndoStackSize: Int,
                maxUndoMemoryBytes: Long,
                blockSize: Int,
                assertions: Boolean,
                staleSampleRandom: Random,
                timeSource: kotlin.time.TimeSource,
                maxDisplayLineChars: Int,
            ) = DocumentBuffer(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, blockSize, true, staleSampleRandom)
        }
        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = null,
            initialContent = content,
            gatewaySwitch = mockk(),
            editorSettings = createMockSettings(),
            fileDataSourceFactory = mockk(),
            inMemoryDataSourceFactory = inMemoryDataSourceFactory,
            documentBufferFactory = documentBufferFactory,
        )
        engine.initialize().getOrThrow()
        return engine
    }

    @Test
    fun `selection at the cap copies successfully`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 5, line = 0, column = 5),
        )

        engine.copySelection(maxChars = 5).getOrThrow() shouldBe "Hello"
    }

    @Test
    fun `selection over the cap fails with ClipboardCapacityException`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        val result = engine.copySelection(maxChars = 10)

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<ClipboardCapacityException>()
        // The refusal must not surface as an engine error banner - it has its own dialog path
        engine.error.value shouldBe null
    }

    @Test
    fun `no cap copies the whole selection`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        engine.copySelection().getOrThrow() shouldBe "Hello World"
    }

    @Test
    fun `reversed selection is normalized for both cap and copy`() = runTest {
        val engine = createEngine("Hello World")
        // setSelection stores the given order; a reversed range must still measure and copy
        engine.setSelection(
            start = TextPosition(offset = 11, line = 0, column = 11),
            end = TextPosition(offset = 6, line = 0, column = 6),
        )

        engine.copySelection(maxChars = 4).exceptionOrNull()
            .shouldBeInstanceOf<ClipboardCapacityException>()
        engine.copySelection(maxChars = 5).getOrThrow() shouldBe "World"
    }

    @Test
    fun `reversed selection deletes correctly (cut parity with copy)`() = runTest {
        // Copy and delete must agree on normalization - otherwise a reversed-selection cut
        // copies the text but silently fails the delete half
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 11, line = 0, column = 11),
            end = TextPosition(offset = 6, line = 0, column = 6),
        )

        engine.deleteSelection().getOrThrow() shouldBe "World"
        val state = engine.state.value as EditorState.Loaded
        state.resources.textBuffer.getText(0, state.resources.textBuffer.totalLength.value)
            .getOrThrow() shouldBe "Hello "
    }
}
