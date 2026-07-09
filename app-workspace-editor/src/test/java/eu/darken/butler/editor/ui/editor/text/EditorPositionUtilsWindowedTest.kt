package eu.darken.butler.editor.ui.editor.text

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Field<->engine column mapping for horizontally-windowed lines (non-zero [lineStartColumns] anchors).
 * Every case reduces to the un-windowed behavior when the anchor map is empty (the default), which is
 * what keeps the mapping inert until the engine starts emitting non-zero start columns.
 */
class EditorPositionUtilsWindowedTest {

    @Test
    fun `flatOffsetToPosition adds the line's window anchor to the column`() {
        val pos = flatOffsetToPosition(
            visibleLines = listOf("world"),
            visibleRangeStart = 5,
            flatOffset = 2,
            lineStartColumns = mapOf(5L to 100L),
        )
        pos shouldBe createUiTextPosition(line = 5, column = 102)
    }

    @Test
    fun `flatOffsetToPosition without anchors is unchanged`() {
        val pos = flatOffsetToPosition(listOf("world"), visibleRangeStart = 5, flatOffset = 2)
        pos shouldBe createUiTextPosition(line = 5, column = 2)
    }

    @Test
    fun `positionToFlatOffset subtracts the line's window anchor`() {
        val offset = positionToFlatOffset(
            visibleLines = listOf("world"),
            visibleRangeStart = 5,
            position = createUiTextPosition(line = 5, column = 102),
            lineStartColumns = mapOf(5L to 100L),
        )
        offset shouldBe 2
    }

    @Test
    fun `positionToFlatOffset returns null for a column hidden before the window`() {
        val offset = positionToFlatOffset(
            visibleLines = listOf("world"),
            visibleRangeStart = 5,
            position = createUiTextPosition(line = 5, column = 50),
            lineStartColumns = mapOf(5L to 100L),
        )
        offset.shouldBeNull()
    }

    @Test
    fun `positionToFlatOffset clamps a column past the window end`() {
        val offset = positionToFlatOffset(
            visibleLines = listOf("world"),
            visibleRangeStart = 5,
            position = createUiTextPosition(line = 5, column = 1000),
            lineStartColumns = mapOf(5L to 100L),
        )
        offset shouldBe 5 // clamped to the visible line's end
    }

    @Test
    fun `flat offset round-trips through position with a window anchor`() {
        val lines = listOf("ab", "world")
        val anchors = mapOf(6L to 100L)
        val pos = flatOffsetToPosition(lines, visibleRangeStart = 5, flatOffset = 5, lineStartColumns = anchors)
        pos shouldBe createUiTextPosition(line = 6, column = 102)
        positionToFlatOffset(lines, visibleRangeStart = 5, position = pos, lineStartColumns = anchors) shouldBe 5
    }

    @Test
    fun `selectWordAt maps an absolute column through the window to absolute boundaries`() {
        val selection = selectWordAt(
            lineIndex = 5,
            column = 102, // absolute; local index 2 inside "hello world"
            visibleLineContent = mapOf(5L to "hello world"),
            lineStartColumns = mapOf(5L to 100L),
        )
        selection.first shouldBe createUiTextPosition(line = 5, column = 100)
        selection.second shouldBe createUiTextPosition(line = 5, column = 105)
    }

    @Test
    fun `selectLineAt spans the rendered window in absolute columns`() {
        val selection = selectLineAt(
            lineIndex = 5,
            visibleLineContent = mapOf(5L to "hello"),
            lineStartColumns = mapOf(5L to 100L),
        )
        selection.first shouldBe createUiTextPosition(line = 5, column = 100)
        selection.second shouldBe createUiTextPosition(line = 5, column = 105)
    }

    @Test
    fun `selection helpers without anchors are unchanged`() {
        selectWordAt(0, 2, mapOf(0L to "hello world")).let {
            it.first shouldBe createUiTextPosition(line = 0, column = 0)
            it.second shouldBe createUiTextPosition(line = 0, column = 5)
        }
        selectLineAt(0, mapOf(0L to "hello")).let {
            it.first shouldBe createUiTextPosition(line = 0, column = 0)
            it.second shouldBe createUiTextPosition(line = 0, column = 5)
        }
    }
}
