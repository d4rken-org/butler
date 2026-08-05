package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A trailing line break is a SEPARATOR, so it bounds a final EMPTY line that must be addressable -
 * otherwise pressing Enter at the end of the document leaves the caret with nowhere to go.
 */
class DocumentBufferTrailingLineTest : DocumentBufferTestBase() {

    @Test
    fun `inserting a newline at EOF adds an addressable empty line`() = runTest {
        val buffer = createBuffer("Hello")
        buffer.totalLines.value shouldBe 1L

        buffer.insertText(TextPosition(5, 0, 5), "\n").getOrThrow()

        buffer.totalLines.value shouldBe 2L
        buffer.getTextForLine(1).getOrThrow() shouldBe ""
        buffer.getLineLength(1).getOrThrow() shouldBe 0L
        buffer.findOffset(1, 0) shouldBe 6L
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `a loaded document ending in a break exposes the trailing empty line`(terminator: String) = runTest {
        assertTrailingEmptyLine(createBuffer("Hello$terminator"), terminator)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `a document edited into a trailing break exposes the trailing empty line`(terminator: String) = runTest {
        val buffer = createBuffer("Hello")
        buffer.insertText(TextPosition(5, 0, 5), terminator).getOrThrow()
        assertTrailingEmptyLine(buffer, terminator)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `undo and redo across the terminator track the line count`(terminator: String) = runTest {
        val buffer = createBuffer("Hello")
        buffer.insertText(TextPosition(5, 0, 5), terminator).getOrThrow()
        buffer.totalLines.value shouldBe 2L

        buffer.undo().getOrThrow()
        buffer.totalLines.value shouldBe 1L
        buffer.getFullText().getOrThrow() shouldBe "Hello"

        buffer.redo().getOrThrow()
        buffer.totalLines.value shouldBe 2L
        buffer.getFullText().getOrThrow() shouldBe "Hello$terminator"
        buffer.getTextForLine(1).getOrThrow() shouldBe ""
    }

    @Test
    fun `a display window on a far trailing empty line resolves`() = runTest {
        val lineCount = 2_000
        val content = (0 until lineCount).joinToString("") { "Line $it\n" }
        val buffer = createBuffer(content, blockSize = 4096)

        val lastLine = buffer.totalLines.value - 1
        lastLine shouldBe lineCount.toLong()

        val window = buffer.getDisplayRange(lastLine - 2, lastLine).getOrThrow()
        window.text shouldBe "Line ${lineCount - 2}\nLine ${lineCount - 1}\n"
        window.truncatedLines shouldBe emptyMap<Long, Long>()
        window.startColumns shouldBe emptyMap<Long, Long>()

        buffer.getLineSlice(lastLine).getOrThrow().text shouldBe ""
        buffer.findOffset(lastLine, 0) shouldBe content.length.toLong()
    }

    private suspend fun assertTrailingEmptyLine(buffer: DocumentBuffer, terminator: String) {
        buffer.totalLines.value shouldBe 2L
        val lastLine = 1L
        val totalLength = (5 + terminator.length).toLong()
        buffer.totalLength.value shouldBe totalLength

        val position = buffer.findPosition(totalLength)
        position.line shouldBe lastLine
        position.column shouldBe 0

        // Exactly ONE logical separator before the empty last line: none doubled, none swallowed
        buffer.getTextForRange(0, lastLine).getOrThrow() shouldBe "Hello\n"
        val window = buffer.getDisplayRange(0, lastLine).getOrThrow()
        window.text shouldBe "Hello\n"
        window.truncatedLines shouldBe emptyMap<Long, Long>()
        window.startColumns shouldBe emptyMap<Long, Long>()

        val slice = buffer.getLineSlice(lastLine).getOrThrow()
        slice.text shouldBe ""
        slice.startColumn shouldBe 0L
        slice.hiddenChars shouldBe 0L

        buffer.getLineLength(lastLine).getOrThrow() shouldBe 0L
        buffer.findOffset(lastLine, 0) shouldBe totalLength
    }
}
