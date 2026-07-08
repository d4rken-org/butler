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
 * Tests for [EditorEngine.replaceText], the single-region edit that all soft-keyboard input flows through.
 *
 * Positions use placeholder offset=0; the engine re-resolves offsets from line/column via the buffer, so
 * tests only need to supply line/column (matching what the UI sends with virtual scrolling).
 */
class EditorEngineReplaceTextTest : DocumentBufferTestBase() {

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
        val settings = createMockSettings()

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
            editorSettings = settings,
            fileDataSourceFactory = mockk(),
            inMemoryDataSourceFactory = inMemoryDataSourceFactory,
            documentBufferFactory = documentBufferFactory,
        )

        engine.initialize().getOrThrow()
        return engine
    }

    private fun pos(line: Long, column: Int) = TextPosition(offset = 0, line = line, column = column)

    private suspend fun EditorEngine.fullContent(): String {
        val loaded = this.state.value as EditorState.Loaded
        return loaded.resources.textBuffer.getText(0, loaded.resources.textBuffer.totalLength.value).getOrThrow()
    }

    @Test
    fun `replace range updates content cursor and clears selection`() = runTest {
        val engine = createEngine("Hello World")

        engine.replaceText(start = pos(0, 6), end = pos(0, 11), text = "Kotlin", caret = pos(0, 12))

        engine.fullContent() shouldBe "Hello Kotlin"
        engine.cursorPosition.value.offset shouldBe 12L
        engine.cursorPosition.value.line shouldBe 0L
        engine.cursorPosition.value.column shouldBe 12
        engine.selectionRange.value shouldBe null
        (engine.state.value as EditorState.Loaded).isModified shouldBe true
    }

    @Test
    fun `pure insert via empty range`() = runTest {
        val engine = createEngine("abcd")

        engine.replaceText(start = pos(0, 2), end = pos(0, 2), text = "X", caret = pos(0, 3))

        engine.fullContent() shouldBe "abXcd"
        engine.cursorPosition.value.column shouldBe 3
    }

    @Test
    fun `pure delete via empty inserted text`() = runTest {
        val engine = createEngine("abcde")

        engine.replaceText(start = pos(0, 1), end = pos(0, 4), text = "", caret = pos(0, 1))

        engine.fullContent() shouldBe "ae"
        engine.cursorPosition.value.column shouldBe 1
    }

    @Test
    fun `replace with newline adds a line`() = runTest {
        val engine = createEngine("abc")

        engine.replaceText(start = pos(0, 1), end = pos(0, 2), text = "X\nY", caret = pos(1, 1))

        engine.fullContent() shouldBe "aX\nYc"
        engine.totalLines.value shouldBe 2L
        engine.cursorPosition.value.line shouldBe 1L
        engine.cursorPosition.value.column shouldBe 1
    }

    @Test
    fun `cross-line delete joins lines`() = runTest {
        val engine = createEngine("abc\ndef")

        // Delete the range covering the newline: end of line 0 to start of line 1.
        engine.replaceText(start = pos(0, 3), end = pos(1, 0), text = "", caret = pos(0, 3))

        engine.fullContent() shouldBe "abcdef"
        engine.totalLines.value shouldBe 1L
    }

    @Test
    fun `replace spanning the synthetic newline`() = runTest {
        val engine = createEngine("abc\ndef")

        // Replace "c\nd" (cols (0,2)..(1,1)) with "Z" -> "abZef".
        engine.replaceText(start = pos(0, 2), end = pos(1, 1), text = "Z", caret = pos(0, 3))

        engine.fullContent() shouldBe "abZef"
        engine.totalLines.value shouldBe 1L
    }

    @Test
    fun `replace tolerates reversed start and end`() = runTest {
        val engine = createEngine("Hello World")

        // start/end given in reverse order should still replace [6,11).
        engine.replaceText(start = pos(0, 11), end = pos(0, 6), text = "Kotlin", caret = pos(0, 12))

        engine.fullContent() shouldBe "Hello Kotlin"
    }

    @Test
    fun `replace clears a pre-existing engine selection`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(start = TextPosition(0, 0, 0), end = TextPosition(5, 0, 5))
        engine.selectionRange.value shouldBe (TextPosition(0, 0, 0) to TextPosition(5, 0, 5))

        engine.replaceText(start = pos(0, 6), end = pos(0, 11), text = "Kotlin", caret = pos(0, 12))

        engine.fullContent() shouldBe "Hello Kotlin"
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `undo and redo round-trip after equal-length replace`() = runTest {
        val engine = createEngine("teh quick")
        val original = engine.fullContent()

        // Autocorrect-style equal-length replace "eh" -> "he".
        engine.replaceText(start = pos(0, 1), end = pos(0, 3), text = "he", caret = pos(0, 3))
        val afterReplace = engine.fullContent()
        afterReplace shouldBe "the quick"

        // Undo all the way back (a genuine replace is delete+insert, so this may take more than one step).
        while (engine.canUndo()) engine.undo()
        engine.fullContent() shouldBe original

        // Redo all the way forward.
        while (engine.canRedo()) engine.redo()
        engine.fullContent() shouldBe afterReplace
    }

    @Test
    fun `splitting a line in a short doc loads the new line into visible content`() = runTest {
        // Regression: in a 1-line document the visible window is 0..0. Inserting a newline mid-line must
        // grow the loaded window to include the new line, otherwise the new line renders blank (its content
        // is never read into currentContent).
        val engine = createEngine("abcdef")
        engine.visibleContent.value.text shouldBe "abcdef"

        // Split after "abc": insert "\n" at (0,3), caret lands at start of the new line.
        engine.replaceText(start = pos(0, 3), end = pos(0, 3), text = "\n", caret = pos(1, 0))

        engine.fullContent() shouldBe "abc\ndef"
        engine.totalLines.value shouldBe 2L
        // Both lines must be present in the visible content that drives rendering.
        engine.visibleContent.value.text shouldBe "abc\ndef"
    }

    @Test
    fun `undo after pure insert restores in a single step`() = runTest {
        val engine = createEngine("abcd")

        engine.replaceText(start = pos(0, 2), end = pos(0, 2), text = "X", caret = pos(0, 3))
        engine.fullContent() shouldBe "abXcd"

        engine.canUndo() shouldBe true
        engine.undo()
        engine.fullContent() shouldBe "abcd"
        engine.canUndo() shouldBe false
    }
}
