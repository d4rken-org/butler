package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import testhelpers.coroutine.TestDispatcherProvider

class EditorEngineGoToLineTest : DocumentBufferTestBase() {

    private fun createMockSettings(): EditorSettings {
        val settings = mockk<EditorSettings>()
        val undoStackSize = mockk<DataStoreValue<Int>>()
        val undoMaxMemory = mockk<DataStoreValue<Long>>()
        every { undoStackSize.flow } returns flowOf(100)
        every { undoMaxMemory.flow } returns flowOf(10 * 1_048_576L)
        every { settings.undoStackSize } returns undoStackSize
        every { settings.undoMaxMemory } returns undoMaxMemory
        val syntaxHighlighting = mockk<DataStoreValue<Boolean>>()
        every { syntaxHighlighting.flow } returns flowOf(true)
        every { settings.syntaxHighlighting } returns syntaxHighlighting
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
            dispatcherProvider = TestDispatcherProvider(),
            fileDataSourceFactory = mockk(),
            inMemoryDataSourceFactory = inMemoryDataSourceFactory,
            documentBufferFactory = documentBufferFactory,
        )
        engine.initialize().getOrThrow()
        return engine
    }

    @Test
    fun `goToLine moves the cursor to the line start and scrolls the window there`() = runTest {
        val engine = createEngine((0..199).joinToString("\n") { "Line $it" })

        engine.goToLine(100).getOrThrow()

        engine.cursorPosition.value.line shouldBe 100L
        engine.cursorPosition.value.column shouldBe 0
        engine.cursorPosition.value.offset shouldBeGreaterThan 0L
        // Window centers on the target line (25 lines of context each side)
        engine.visibleRange.value.first shouldBe 75L
        engine.visibleRange.value.last shouldBe 125L
    }

    @Test
    fun `goToLine to the last line clamps the window to the document end`() = runTest {
        val engine = createEngine((0..99).joinToString("\n") { "Line $it" })

        engine.goToLine(99).getOrThrow()

        engine.cursorPosition.value.line shouldBe 99L
        engine.visibleRange.value.last shouldBe 99L
    }

    @Test
    fun `goToLine out of range fails without moving the cursor`() = runTest {
        val engine = createEngine("Line 0\nLine 1")
        val before = engine.cursorPosition.value

        val result = engine.goToLine(5)

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        engine.cursorPosition.value shouldBe before
    }
}
