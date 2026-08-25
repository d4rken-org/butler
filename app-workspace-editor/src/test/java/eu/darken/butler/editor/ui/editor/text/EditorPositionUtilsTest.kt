package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntSize
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EditorPositionUtilsTest {

    @Nested
    inner class ToDisplayText {
        @Test
        fun `expands single tab with default size`() {
            "hello\tworld".toDisplayText(4) shouldBe "hello    world"
        }

        @Test
        fun `expands multiple tabs`() {
            "\t\t".toDisplayText(4) shouldBe "        "
        }

        @Test
        fun `handles tab size of 2`() {
            "a\tb".toDisplayText(2) shouldBe "a  b"
        }

        @Test
        fun `handles string without tabs`() {
            "no tabs here".toDisplayText(4) shouldBe "no tabs here"
        }

        @Test
        fun `handles empty string`() {
            "".toDisplayText(4) shouldBe ""
        }

        @Test
        fun `handles tab at start`() {
            "\tindented".toDisplayText(4) shouldBe "    indented"
        }

        @Test
        fun `handles tab at end`() {
            "trailing\t".toDisplayText(4) shouldBe "trailing    "
        }

        @Test
        fun `renders NUL as a visible control picture`() {
            "a\u0000b".toDisplayText(4) shouldBe "a␀b"
        }

        @Test
        fun `renders the whole C0 block and DEL as control pictures`() {
            "\u0001\u001F\u007F".toDisplayText(4) shouldBe "␁␟␡"
        }

        @Test
        fun `control substitution keeps length 1 to 1`() {
            val raw = "x\u0000y\u0007z"
            raw.toDisplayText(4).length shouldBe raw.length
        }

        @Test
        fun `mixed tabs and controls transform together`() {
            "\ta\u0000".toDisplayText(2) shouldBe "  a␀"
        }

        @Test
        fun `plain text returns the same instance`() {
            val raw = "plain text"
            (raw.toDisplayText(4) === raw) shouldBe true
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

    @Nested
    inner class ExpandedColumnFromX {
        @Test
        fun `negative X returns zero`() {
            expandedColumnFromX(adjustedX = -5f, charWidthPx = 10f, maxColumn = 100) shouldBe 0
        }

        @Test
        fun `zero X returns zero`() {
            expandedColumnFromX(adjustedX = 0f, charWidthPx = 10f, maxColumn = 100) shouldBe 0
        }

        @Test
        fun `non-positive charWidth is guarded`() {
            expandedColumnFromX(adjustedX = 500f, charWidthPx = 0f, maxColumn = 100) shouldBe 0
            expandedColumnFromX(adjustedX = 500f, charWidthPx = -3f, maxColumn = 100) shouldBe 0
        }

        @Test
        fun `before the cell midpoint stays on the cell`() {
            // cell 0 spans [0,10); midpoint 5 - 4 is before center
            expandedColumnFromX(adjustedX = 4f, charWidthPx = 10f, maxColumn = 100) shouldBe 0
        }

        @Test
        fun `exactly at the midpoint stays on the cell`() {
            // strict > matches the layout branch: an exact half does not advance
            expandedColumnFromX(adjustedX = 5f, charWidthPx = 10f, maxColumn = 100) shouldBe 0
        }

        @Test
        fun `past the cell midpoint advances`() {
            expandedColumnFromX(adjustedX = 6f, charWidthPx = 10f, maxColumn = 100) shouldBe 1
        }

        @Test
        fun `past-center rounding on a later cell`() {
            // cell 1 spans [10,20); midpoint 15
            expandedColumnFromX(adjustedX = 14f, charWidthPx = 10f, maxColumn = 100) shouldBe 1
            expandedColumnFromX(adjustedX = 16f, charWidthPx = 10f, maxColumn = 100) shouldBe 2
        }

        @Test
        fun `clamps past end of line to maxColumn`() {
            expandedColumnFromX(adjustedX = 100_000f, charWidthPx = 10f, maxColumn = 9500) shouldBe 9500
        }

        @Test
        fun `resolves a large column exactly - the drift core`() {
            // 9500 cells * 10px, just at the left edge of cell 9500 -> exactly 9500, no drift
            expandedColumnFromX(adjustedX = 95_000f, charWidthPx = 10f, maxColumn = 20_000) shouldBe 9500
        }

        @Test
        fun `empty line clamps to zero`() {
            expandedColumnFromX(adjustedX = 500f, charWidthPx = 10f, maxColumn = 0) shouldBe 0
        }
    }

    @Nested
    inner class LazyListCoordinates {

        private fun layoutInfo(topContentPadding: Int) = object : LazyListLayoutInfo {
            override val visibleItemsInfo: List<LazyListItemInfo> = emptyList()
            override val viewportStartOffset: Int = -topContentPadding
            override val viewportEndOffset: Int = 0
            override val totalItemsCount: Int = 0
            override val viewportSize: IntSize = IntSize.Zero
            override val orientation: Orientation = Orientation.Vertical
            override val reverseLayout: Boolean = false
            override val beforeContentPadding: Int = topContentPadding
            override val afterContentPadding: Int = 0
            override val mainAxisItemSpacing: Int = 0
        }

        @Test
        fun `without top padding both directions are the identity`() {
            val info = layoutInfo(topContentPadding = 0)

            info.containerToItemY(0f) shouldBe 0f
            info.containerToItemY(37f) shouldBe 37f
            info.itemToContainerY(37f) shouldBe 37f
        }

        @Test
        fun `top padding shifts the two spaces against each other`() {
            val info = layoutInfo(topContentPadding = 64)

            // The item-space origin, the first item's top, sits 64px down the container.
            info.containerToItemY(64f) shouldBe 0f
            info.itemToContainerY(0f) shouldBe 64f
            // A tap inside the padding band is above the content region.
            info.containerToItemY(10f) shouldBe -54f
        }

        @Test
        fun `the two directions round-trip at non-zero padding`() {
            val info = layoutInfo(topContentPadding = 64)

            for (y in listOf(-100f, 0f, 12.5f, 64f, 1000f)) {
                info.itemToContainerY(info.containerToItemY(y)) shouldBe y
                info.containerToItemY(info.itemToContainerY(y)) shouldBe y
            }
        }
    }
}
