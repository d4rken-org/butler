package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
/**
 * Tests for selection-aware editing behavior in EditorEngine.
 *
 * Verifies that:
 * - insertText() replaces selection with new text
 * - deleteAtCursor() deletes selection instead of backspacing
 * - deleteForward() deletes selection instead of forward-deleting
 */
class EditorEngineSelectionEditTest : DocumentBufferTestBase() {

    private fun createMockSettings(): EditorSettings {
        val settings = mockk<EditorSettings>()
        val undoStackSize = mockk<DataStoreValue<Int>>()
        val undoMaxMemory = mockk<DataStoreValue<Long>>()

        // Mock the flow property which is used by the value() extension function
        every { undoStackSize.flow } returns flowOf(100)
        every { undoMaxMemory.flow } returns flowOf(10 * 1_048_576L)

        every { settings.undoStackSize } returns undoStackSize
        every { settings.undoMaxMemory } returns undoMaxMemory

        return settings
    }

    private suspend fun createEngine(content: String): EditorEngine {
        val settings = createMockSettings()

        // Create factories that delegate to real constructors
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
            ) = DocumentBuffer(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, blockSize, true, staleSampleRandom)
        }

        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = null,
            initialContent = content,
            gatewaySwitch = mockk(), // Not used for in-memory
            editorSettings = settings,
            fileDataSourceFactory = mockk(), // Not used for in-memory
            inMemoryDataSourceFactory = inMemoryDataSourceFactory,
            documentBufferFactory = documentBufferFactory,
        )

        engine.initialize().getOrThrow()
        return engine
    }

    // ==================== insertText() with Selection ====================

    @Test
    fun `insertText with selection replaces selected text`() = runTest {
        // Given: Content with "World" to be replaced
        val engine = createEngine("Hello World")

        // Select "World" (offset 6-11, line 0, columns 6-11)
        engine.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        // When: Type "Kotlin"
        engine.insertText("Kotlin")

        // Then: "World" is replaced with "Kotlin"
        engine.getFullContent() shouldBe "Hello Kotlin"
        // And: Selection is cleared
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `insertText with multi-line selection replaces all selected lines`() = runTest {
        // Given: Multi-line content
        val engine = createEngine("Line 1\nLine 2\nLine 3")

        // Select from "1" on line 0 to "2" on line 1 (including newline)
        engine.setSelection(
            start = TextPosition(offset = 5, line = 0, column = 5),
            end = TextPosition(offset = 13, line = 1, column = 6),
        )

        // When: Type "X"
        engine.insertText("X")

        // Then: Selected text is replaced
        engine.getFullContent() shouldBe "Line X\nLine 3"
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `insertText without selection inserts at cursor`() = runTest {
        // Given: Content with cursor at position 5
        val engine = createEngine("Hello World")
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))

        // When: Type " there"
        engine.insertText(" there")

        // Then: Text is inserted at cursor position
        engine.getFullContent() shouldBe "Hello there World"
        engine.selectionRange.value shouldBe null
    }

    // ==================== deleteAtCursor() with Selection ====================

    @Test
    fun `deleteAtCursor with selection deletes selection instead of backspace`() = runTest {
        // Given: Content with selection
        val engine = createEngine("Hello World")

        // Select "World"
        engine.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        // When: Press backspace (count is ignored when selection exists)
        engine.deleteAtCursor(1)

        // Then: Selection is deleted, not the character before cursor
        engine.getFullContent() shouldBe "Hello "
        // And: Cursor is at selection start
        engine.cursorPosition.value.offset shouldBe 6L
        // And: Selection is cleared
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `deleteAtCursor with multi-line selection deletes all selected text`() = runTest {
        // Given: Multi-line content with selection across lines
        val engine = createEngine("Line 1\nLine 2\nLine 3")

        // Select "Line 2\n"
        engine.setSelection(
            start = TextPosition(offset = 7, line = 1, column = 0),
            end = TextPosition(offset = 14, line = 2, column = 0),
        )

        // When: Press backspace
        engine.deleteAtCursor(1)

        // Then: Selected line is deleted
        engine.getFullContent() shouldBe "Line 1\nLine 3"
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `deleteAtCursor without selection performs normal backspace`() = runTest {
        // Given: Content with cursor at position 5 (after "Hello")
        val engine = createEngine("Hello World")
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))

        // When: Press backspace
        engine.deleteAtCursor(1)

        // Then: Character before cursor is deleted
        engine.getFullContent() shouldBe "Hell World"
    }

    // ==================== deleteForward() with Selection ====================

    @Test
    fun `deleteForward with selection deletes selection instead of forward-delete`() = runTest {
        // Given: Content with selection
        val engine = createEngine("Hello World")

        // Select "Hello"
        engine.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 5, line = 0, column = 5),
        )

        // When: Press Delete key
        engine.deleteForward()

        // Then: Selection is deleted
        engine.getFullContent() shouldBe " World"
        // And: Cursor is at selection start
        engine.cursorPosition.value.offset shouldBe 0L
        // And: Selection is cleared
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `deleteForward without selection deletes character after cursor`() = runTest {
        // Given: Content with cursor at position 5 (after "Hello")
        val engine = createEngine("Hello World")
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))

        // When: Press Delete key
        engine.deleteForward()

        // Then: Character after cursor (space) is deleted
        engine.getFullContent() shouldBe "HelloWorld"
    }

    // ==================== Edge Cases ====================

    @Test
    fun `selection at document start is handled correctly`() = runTest {
        // Given: Content with selection at start
        val engine = createEngine("Hello World")

        // Select "Hello"
        engine.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 5, line = 0, column = 5),
        )

        // When: Type replacement
        engine.insertText("Hi")

        // Then: Text is replaced correctly
        engine.getFullContent() shouldBe "Hi World"
        engine.cursorPosition.value.offset shouldBe 2L
    }

    @Test
    fun `selection at document end is handled correctly`() = runTest {
        // Given: Content with selection at end
        val engine = createEngine("Hello World")

        // Select "World"
        engine.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        // When: Delete selection
        engine.deleteAtCursor(1)

        // Then: Text at end is deleted
        engine.getFullContent() shouldBe "Hello "
    }

    // Helper to get full content from engine
    private suspend fun EditorEngine.getFullContent(): String {
        val state = this.state.value as EditorState.Loaded
        return state.resources.textBuffer.getText(0, state.resources.textBuffer.totalLength.value).getOrThrow()
    }
}
