package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Reproducers for the data corruption that motivated the piece-table rewrite. Both scenarios
 * fail on the old chunk engine: after an edit, boundaries shift to post-edit coordinates while
 * clean chunks reload from the UNEDITED file at those shifted offsets.
 */
class DocumentBufferCorruptionTest : DocumentBufferTestBase() {

    private fun blockContent(blocks: Int = 10): String =
        (0 until blocks).joinToString("") { i -> "$i" + "23456789" }

    @Test
    fun `whole document read-back after multi-block insert without save`() = runTest {
        // 100 ASCII chars over 10 blocks; the old engine returned 53 of 103 chars here
        val content = blockContent()
        val buffer = createBuffer(content, blockSize = 10)

        buffer.insertText(TextPosition(offset = 5, line = 0, column = 5), "XYZ").getOrThrow()

        val expected = StringBuilder(content).insert(5, "XYZ").toString()
        buffer.totalLength.value shouldBe expected.length.toLong()
        buffer.getText(0, buffer.totalLength.value).getOrThrow() shouldBe expected
        buffer.getFullText().getOrThrow() shouldBe expected
    }

    @Test
    fun `whole document read-back after multi-block delete without save`() = runTest {
        // The old engine resurrected deleted bytes after boundary shifts
        val content = blockContent()
        val buffer = createBuffer(content, blockSize = 10)

        buffer.deleteText(
            TextPosition(offset = 2, line = 0, column = 2),
            TextPosition(offset = 37, line = 0, column = 37),
        ).getOrThrow()

        val expected = StringBuilder(content).delete(2, 37).toString()
        buffer.totalLength.value shouldBe expected.length.toLong()
        buffer.getText(0, buffer.totalLength.value).getOrThrow() shouldBe expected
        buffer.getFullText().getOrThrow() shouldBe expected
    }

    @Test
    fun `scattered edits across many blocks stay consistent without save`() = runTest {
        val content = blockContent(20)
        val buffer = createBuffer(content, blockSize = 10)
        val reference = StringBuilder(content)

        buffer.insertText(TextPosition(15, 0, 15), "AA").getOrThrow()
        reference.insert(15, "AA")
        buffer.deleteText(TextPosition(50, 0, 50), TextPosition(75, 0, 75)).getOrThrow()
        reference.delete(50, 75)
        buffer.insertText(TextPosition(120, 0, 120), "BBB").getOrThrow()
        reference.insert(120, "BBB")

        buffer.getFullText().getOrThrow() shouldBe reference.toString()
        buffer.getText(0, buffer.totalLength.value).getOrThrow() shouldBe reference.toString()
    }
}
