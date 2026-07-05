package eu.darken.butler.editor.ui.editor.text

import androidx.compose.ui.text.TextRange
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EditorPositionUtilsTest {

    @Nested
    inner class ExpandTabs {
        @Test
        fun `expands single tab with default size`() {
            "hello\tworld".expandTabs(4) shouldBe "hello    world"
        }

        @Test
        fun `expands multiple tabs`() {
            "\t\t".expandTabs(4) shouldBe "        "
        }

        @Test
        fun `handles tab size of 2`() {
            "a\tb".expandTabs(2) shouldBe "a  b"
        }

        @Test
        fun `handles string without tabs`() {
            "no tabs here".expandTabs(4) shouldBe "no tabs here"
        }

        @Test
        fun `handles empty string`() {
            "".expandTabs(4) shouldBe ""
        }

        @Test
        fun `handles tab at start`() {
            "\tindented".expandTabs(4) shouldBe "    indented"
        }

        @Test
        fun `handles tab at end`() {
            "trailing\t".expandTabs(4) shouldBe "trailing    "
        }
    }

    @Nested
    inner class FindWordBoundaries {
        @Test
        fun `finds word in middle of text`() {
            findWordBoundaries("hello world", 6) shouldBe (6 to 11)
        }

        @Test
        fun `finds word at start of text`() {
            findWordBoundaries("hello world", 0) shouldBe (0 to 5)
        }

        @Test
        fun `finds word at end of text`() {
            findWordBoundaries("hello world", 10) shouldBe (6 to 11)
        }

        @Test
        fun `handles cursor on space - selects single char`() {
            findWordBoundaries("hello world", 5) shouldBe (5 to 6)
        }

        @Test
        fun `handles underscore as word char`() {
            findWordBoundaries("hello_world test", 0) shouldBe (0 to 11)
        }

        @Test
        fun `handles numbers as word chars`() {
            findWordBoundaries("var123 = 456", 0) shouldBe (0 to 6)
        }

        @Test
        fun `handles empty string`() {
            findWordBoundaries("", 0) shouldBe (0 to 0)
        }

        @Test
        fun `handles column past end of text`() {
            findWordBoundaries("hello", 10) shouldBe (5 to 5)
        }

        @Test
        fun `handles punctuation - selects single char`() {
            findWordBoundaries("hello, world", 5) shouldBe (5 to 6)
        }

        @Test
        fun `finds word with cursor in middle`() {
            findWordBoundaries("hello world", 2) shouldBe (0 to 5)
        }

        @Test
        fun `handles single character word`() {
            findWordBoundaries("a b c", 2) shouldBe (2 to 3)
        }

        @Test
        fun `handles string of only spaces`() {
            findWordBoundaries("   ", 1) shouldBe (1 to 2)
        }
    }

    @Nested
    inner class SelectWordAt {
        @Test
        fun `selects word and returns positions`() {
            val content = mapOf(0L to "hello world")
            val (start, end) = selectWordAt(0, 6, content)

            start.line shouldBe 0L
            start.column shouldBe 6
            end.line shouldBe 0L
            end.column shouldBe 11
        }

        @Test
        fun `handles missing line content`() {
            val content = emptyMap<Long, String>()
            val (start, end) = selectWordAt(5, 0, content)

            start.line shouldBe 5L
            start.column shouldBe 0
            end.line shouldBe 5L
            end.column shouldBe 0
        }

        @Test
        fun `preserves line index in result`() {
            val content = mapOf(42L to "test")
            val (start, end) = selectWordAt(42, 0, content)

            start.line shouldBe 42L
            end.line shouldBe 42L
        }
    }

    @Nested
    inner class ComputeTextEdit {

        private fun TextEdit.applyTo(old: String): String =
            old.substring(0, start) + inserted + old.substring(end)

        @Test
        fun `no change returns null`() {
            computeTextEdit("abc", "abc").shouldBeNull()
        }

        @Test
        fun `empty to empty returns null`() {
            computeTextEdit("", "").shouldBeNull()
        }

        @Test
        fun `append at end`() {
            val edit = computeTextEdit("abc", "abcd")!!
            edit shouldBe TextEdit(start = 3, end = 3, inserted = "d")
            edit.applyTo("abc") shouldBe "abcd"
        }

        @Test
        fun `prepend at start`() {
            val edit = computeTextEdit("abc", "xabc")!!
            edit shouldBe TextEdit(start = 0, end = 0, inserted = "x")
            edit.applyTo("abc") shouldBe "xabc"
        }

        @Test
        fun `mid insert`() {
            val edit = computeTextEdit("abc", "aXbc")!!
            edit shouldBe TextEdit(start = 1, end = 1, inserted = "X")
            edit.applyTo("abc") shouldBe "aXbc"
        }

        @Test
        fun `single char delete`() {
            val edit = computeTextEdit("abc", "ac")!!
            edit shouldBe TextEdit(start = 1, end = 2, inserted = "")
            edit.applyTo("abc") shouldBe "ac"
        }

        @Test
        fun `multi char delete`() {
            val edit = computeTextEdit("abcde", "ae")!!
            edit shouldBe TextEdit(start = 1, end = 4, inserted = "")
            edit.applyTo("abcde") shouldBe "ae"
        }

        @Test
        fun `equal length replace - autocorrect teh to the`() {
            val edit = computeTextEdit("teh", "the")!!
            edit shouldBe TextEdit(start = 1, end = 3, inserted = "he")
            edit.applyTo("teh") shouldBe "the"
        }

        @Test
        fun `full replace`() {
            val edit = computeTextEdit("abc", "xyz")!!
            edit shouldBe TextEdit(start = 0, end = 3, inserted = "xyz")
            edit.applyTo("abc") shouldBe "xyz"
        }

        @Test
        fun `from empty inserts everything`() {
            val edit = computeTextEdit("", "abc")!!
            edit shouldBe TextEdit(start = 0, end = 0, inserted = "abc")
            edit.applyTo("") shouldBe "abc"
        }

        @Test
        fun `to empty deletes everything`() {
            val edit = computeTextEdit("abc", "")!!
            edit shouldBe TextEdit(start = 0, end = 3, inserted = "")
            edit.applyTo("abc") shouldBe ""
        }

        @Test
        fun `insert newline splits a line`() {
            val edit = computeTextEdit("abc", "ab\nc")!!
            edit shouldBe TextEdit(start = 2, end = 2, inserted = "\n")
            edit.applyTo("abc") shouldBe "ab\nc"
        }

        @Test
        fun `replacing a whole surrogate pair never splits it`() {
            // "A😀B" -> "A😁B": the emoji (a surrogate pair) is swapped wholesale.
            val old = "A😀B"
            val new = "A😁B"
            val edit = computeTextEdit(old, new)!!
            edit shouldBe TextEdit(start = 1, end = 3, inserted = "😁")
            edit.applyTo(old) shouldBe new
        }

        @Test
        fun `appending an emoji keeps the pair intact`() {
            val old = "hi"
            val new = "hi😀"
            val edit = computeTextEdit(old, new)!!
            edit shouldBe TextEdit(start = 2, end = 2, inserted = "😀")
            edit.applyTo(old) shouldBe new
        }
    }

    @Nested
    inner class FlatOffsetMapping {

        private val lines = listOf("abc", "def", "ghi")
        private val start = 5L // non-zero visible window start

        @Test
        fun `offset at line start`() {
            flatOffsetToPosition(lines, start, 0) shouldBe TextPosition(0L, 5, 0)
        }

        @Test
        fun `offset at end of first line maps to that line`() {
            // In "abc\ndef..." offset 3 is the end of line 0 (not the start of line 1).
            flatOffsetToPosition(lines, start, 3) shouldBe TextPosition(0L, 5, 3)
        }

        @Test
        fun `offset just past newline maps to next line start`() {
            flatOffsetToPosition(lines, start, 4) shouldBe TextPosition(0L, 6, 0)
        }

        @Test
        fun `offset on last line`() {
            flatOffsetToPosition(lines, start, 9) shouldBe TextPosition(0L, 7, 1)
        }

        @Test
        fun `offset past end clamps to end of last visible line`() {
            flatOffsetToPosition(lines, start, 999) shouldBe TextPosition(0L, 7, 3)
        }

        @Test
        fun `positionToFlatOffset is the inverse across the window`() {
            val joined = lines.joinToString("\n")
            for (offset in 0..joined.length) {
                val pos = flatOffsetToPosition(lines, start, offset)
                positionToFlatOffset(lines, start, pos) shouldBe offset
            }
        }

        @Test
        fun `positionToFlatOffset returns null above the window`() {
            positionToFlatOffset(lines, start, TextPosition(0L, 4, 0)).shouldBeNull()
        }

        @Test
        fun `positionToFlatOffset returns null below the window`() {
            positionToFlatOffset(lines, start, TextPosition(0L, 8, 0)).shouldBeNull()
        }

        @Test
        fun `positionToFlatOffset clamps column beyond line length`() {
            // Column past the line end clamps to the line end rather than bleeding into the next line.
            positionToFlatOffset(lines, start, TextPosition(0L, 5, 99)) shouldBe 3
        }
    }

    @Nested
    inner class ComputeFieldSelectionSync {

        @Test
        fun `caret moved with unchanged text rewrites the field selection`() {
            // Regression: tapping to reposition mid-text (engine caret moved to col 6) must move the hidden
            // field caret too, even while an IME composition is active. Otherwise the next keystroke lands
            // at the stale offset (the field's old end position).
            val text = "hello world test"
            computeFieldSelectionSync(
                fieldText = text,
                fieldSelection = TextRange(text.length), // field still at end
                engineContent = text,
                mappedSelection = TextRange(6), // engine caret moved before "world"
            ) shouldBe TextRange(6)
        }

        @Test
        fun `no change leaves the field untouched`() {
            // A spurious re-fire (e.g. viewport scroll that didn't move the caret) must not rewrite the
            // field, so an in-progress composition is preserved.
            computeFieldSelectionSync(
                fieldText = "abc",
                fieldSelection = TextRange(3),
                engineContent = "abc",
                mappedSelection = TextRange(3),
            ).shouldBeNull()
        }

        @Test
        fun `external content change applies the mapped selection`() {
            computeFieldSelectionSync(
                fieldText = "old",
                fieldSelection = TextRange(3),
                engineContent = "brand new",
                mappedSelection = TextRange(2),
            ) shouldBe TextRange(2)
        }

        @Test
        fun `content change with caret out of window clamps the previous caret`() {
            computeFieldSelectionSync(
                fieldText = "abcdef",
                fieldSelection = TextRange(6),
                engineContent = "ab",
                mappedSelection = null,
            ) shouldBe TextRange(2)
        }

        @Test
        fun `caret out of window with unchanged text leaves the field untouched`() {
            computeFieldSelectionSync(
                fieldText = "abc",
                fieldSelection = TextRange(1),
                engineContent = "abc",
                mappedSelection = null,
            ).shouldBeNull()
        }

        @Test
        fun `content change rewrites even when mapped selection equals field selection`() {
            computeFieldSelectionSync(
                fieldText = "abX",
                fieldSelection = TextRange(2),
                engineContent = "abY",
                mappedSelection = TextRange(2),
            ) shouldBe TextRange(2)
        }
    }

    @Nested
    inner class SelectLineAt {
        @Test
        fun `selects entire line`() {
            val content = mapOf(0L to "hello world")
            val (start, end) = selectLineAt(0, content)

            start.line shouldBe 0L
            start.column shouldBe 0
            end.line shouldBe 0L
            end.column shouldBe 11
        }

        @Test
        fun `handles empty line`() {
            val content = mapOf(0L to "")
            val (start, end) = selectLineAt(0, content)

            start.column shouldBe 0
            end.column shouldBe 0
        }

        @Test
        fun `handles missing line content`() {
            val content = emptyMap<Long, String>()
            val (start, end) = selectLineAt(5, content)

            start.line shouldBe 5L
            start.column shouldBe 0
            end.line shouldBe 5L
            end.column shouldBe 0
        }

        @Test
        fun `preserves line index in result`() {
            val content = mapOf(99L to "content")
            val (start, end) = selectLineAt(99, content)

            start.line shouldBe 99L
            end.line shouldBe 99L
        }
    }
}
