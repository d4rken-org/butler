package eu.darken.butler.editor.ui.editor.text

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
            val content = mapOf(0 to "hello world")
            val (start, end) = selectWordAt(0, 6, content)

            start.line shouldBe 0
            start.column shouldBe 6
            end.line shouldBe 0
            end.column shouldBe 11
        }

        @Test
        fun `handles missing line content`() {
            val content = emptyMap<Int, String>()
            val (start, end) = selectWordAt(5, 0, content)

            start.line shouldBe 5
            start.column shouldBe 0
            end.line shouldBe 5
            end.column shouldBe 0
        }

        @Test
        fun `preserves line index in result`() {
            val content = mapOf(42 to "test")
            val (start, end) = selectWordAt(42, 0, content)

            start.line shouldBe 42
            end.line shouldBe 42
        }
    }

    @Nested
    inner class SelectLineAt {
        @Test
        fun `selects entire line`() {
            val content = mapOf(0 to "hello world")
            val (start, end) = selectLineAt(0, content)

            start.line shouldBe 0
            start.column shouldBe 0
            end.line shouldBe 0
            end.column shouldBe 11
        }

        @Test
        fun `handles empty line`() {
            val content = mapOf(0 to "")
            val (start, end) = selectLineAt(0, content)

            start.column shouldBe 0
            end.column shouldBe 0
        }

        @Test
        fun `handles missing line content`() {
            val content = emptyMap<Int, String>()
            val (start, end) = selectLineAt(5, content)

            start.line shouldBe 5
            start.column shouldBe 0
            end.line shouldBe 5
            end.column shouldBe 0
        }

        @Test
        fun `preserves line index in result`() {
            val content = mapOf(99 to "content")
            val (start, end) = selectLineAt(99, content)

            start.line shouldBe 99
            end.line shouldBe 99
        }
    }
}
