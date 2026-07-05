package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * CRLF-across-pieces behavior of the line APIs: edits landing between the CR and LF of a CRLF
 * split it into two breaks, deleting the insert rejoins it, and CRLFs split across original
 * blocks stay one break.
 */
class DocumentBufferSeamTest : DocumentBufferTestBase() {

    @Test
    fun `insert between CR and LF splits the break for all line APIs`() = runTest {
        val buffer = createBuffer("aa\r\nbb\r\ncc")
        buffer.totalLines.value shouldBe 3

        buffer.insertText(TextPosition(offset = 3, line = 0, column = 3), "X").getOrThrow()

        // "aa\rX\nbb\r\ncc" - breaks end at 3 (lone CR), 5 (LF), 9 (CRLF)
        buffer.totalLines.value shouldBe 4
        buffer.getTextForLine(0).getOrThrow() shouldBe "aa"
        buffer.getTextForLine(1).getOrThrow() shouldBe "X"
        buffer.getTextForLine(2).getOrThrow() shouldBe "bb"
        buffer.getTextForLine(3).getOrThrow() shouldBe "cc"

        buffer.findOffset(2, 0) shouldBe 5L
        val position = buffer.findPosition(5L)
        position.line shouldBe 2
        position.column shouldBe 0
        // Between the CR and LF of the remaining CRLF: still on the old line
        buffer.findPosition(8L).line shouldBe 2
    }

    @Test
    fun `deleting the insert between CR and LF rejoins the break`() = runTest {
        val buffer = createBuffer("aa\r\nbb")
        buffer.insertText(TextPosition(3, 0, 3), "X").getOrThrow()
        buffer.totalLines.value shouldBe 3

        buffer.deleteText(TextPosition(3, 0, 3), TextPosition(4, 0, 4)).getOrThrow()

        buffer.totalLines.value shouldBe 2
        buffer.getTextForLine(0).getOrThrow() shouldBe "aa"
        buffer.getTextForLine(1).getOrThrow() shouldBe "bb"
        buffer.findOffset(1, 0) shouldBe 4L
    }

    @Test
    fun `CRLF split across original blocks is one break`() = runTest {
        // blockSize 8 puts the CR at the end of block 0 and the LF at the start of block 1
        val content = "aaaaaaa\r\nbb"
        val buffer = createBuffer(content, blockSize = 8)

        buffer.totalLines.value shouldBe 2
        buffer.getTextForLine(0).getOrThrow() shouldBe "aaaaaaa"
        buffer.getTextForLine(1).getOrThrow() shouldBe "bb"
        buffer.findOffset(1, 0) shouldBe 9L
        buffer.findPosition(9L).line shouldBe 1
        // Between CR and LF: still line 0
        buffer.findPosition(8L).line shouldBe 0
    }

    @Test
    fun `deleting the CR of a block-split CRLF leaves the LF break`() = runTest {
        val buffer = createBuffer("aaaaaaa\r\nbb", blockSize = 8)

        buffer.deleteText(TextPosition(7, 0, 7), TextPosition(8, 0, 8)).getOrThrow()

        buffer.getFullText().getOrThrow() shouldBe "aaaaaaa\nbb"
        buffer.totalLines.value shouldBe 2
        buffer.getTextForLine(1).getOrThrow() shouldBe "bb"
    }

    @Test
    fun `undoing a seam edit restores line structure and clears isModified`() = runTest {
        val buffer = createBuffer("aa\r\nbb")
        buffer.insertText(TextPosition(3, 0, 3), "X").getOrThrow()
        buffer.totalLines.value shouldBe 3
        buffer.isModified.value shouldBe true

        buffer.undo().getOrThrow()

        buffer.getFullText().getOrThrow() shouldBe "aa\r\nbb"
        buffer.totalLines.value shouldBe 2
        // Save-checkpoint semantics: undoing back to the loaded state clears the flag
        buffer.isModified.value shouldBe false

        buffer.redo().getOrThrow()
        buffer.totalLines.value shouldBe 3
        buffer.isModified.value shouldBe true
    }
}
