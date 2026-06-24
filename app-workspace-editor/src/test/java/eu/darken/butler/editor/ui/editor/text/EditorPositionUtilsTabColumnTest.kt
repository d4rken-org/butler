package eu.darken.butler.editor.ui.editor.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for the RAW <-> EXPANDED column conversion that fixes cursor/selection mispositioning on lines
 * containing tabs (#5). Engine columns are RAW char indices; the rendered line is tab-expanded.
 */
class EditorPositionUtilsTabColumnTest {

    @Test
    fun `no tabs is identity in both directions`() {
        val line = "hello"
        for (col in 0..line.length + 2) {
            rawToExpandedColumn(line, col, tabSize = 4) shouldBe col
            expandedToRawColumn(line, col, tabSize = 4) shouldBe col
        }
    }

    @Test
    fun `tab at start`() {
        val line = "\tab"
        // raw -> expanded
        rawToExpandedColumn(line, 0, 4) shouldBe 0   // before tab
        rawToExpandedColumn(line, 1, 4) shouldBe 4   // after tab
        rawToExpandedColumn(line, 2, 4) shouldBe 5
        rawToExpandedColumn(line, 3, 4) shouldBe 6
        // expanded -> raw (boundaries)
        expandedToRawColumn(line, 0, 4) shouldBe 0
        expandedToRawColumn(line, 4, 4) shouldBe 1
        expandedToRawColumn(line, 5, 4) shouldBe 2
    }

    @Test
    fun `tab in the middle`() {
        val line = "a\tb"
        rawToExpandedColumn(line, 1, 4) shouldBe 1   // at the tab
        rawToExpandedColumn(line, 2, 4) shouldBe 5   // after the tab
        rawToExpandedColumn(line, 3, 4) shouldBe 6
        expandedToRawColumn(line, 5, 4) shouldBe 2
        expandedToRawColumn(line, 6, 4) shouldBe 3
    }

    @Test
    fun `a column inside a tab cell snaps to the tab's raw index`() {
        val line = "a\tb"   // expanded: "a" + 4 spaces + "b"; the tab occupies expanded columns [1,5)
        expandedToRawColumn(line, 2, 4) shouldBe 1   // inside the tab cell -> the tab (raw index 1)
        expandedToRawColumn(line, 3, 4) shouldBe 1
        expandedToRawColumn(line, 4, 4) shouldBe 1
    }

    @Test
    fun `columns past end of line map one to one`() {
        val line = "ab"
        rawToExpandedColumn(line, 5, 4) shouldBe 5   // 2 chars + 3 past-end
        expandedToRawColumn(line, 5, 4) shouldBe 5
    }

    @Test
    fun `empty line`() {
        rawToExpandedColumn("", 0, 4) shouldBe 0
        expandedToRawColumn("", 0, 4) shouldBe 0
        expandedToRawColumn("", 3, 4) shouldBe 3
    }

    @Test
    fun `round-trip raw to expanded to raw for all boundaries with various tab sizes`() {
        val line = "\ta\tbb\t\tc"
        for (tabSize in intArrayOf(2, 4, 8)) {
            for (raw in 0..line.length) {
                expandedToRawColumn(line, rawToExpandedColumn(line, raw, tabSize), tabSize) shouldBe raw
            }
        }
    }
}
