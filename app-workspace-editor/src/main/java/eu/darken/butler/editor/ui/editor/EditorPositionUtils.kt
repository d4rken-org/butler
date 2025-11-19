package eu.darken.butler.editor.ui.editor

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.editor.core.engine.TextPosition

internal fun String.expandTabs(tabSize: Int): String {
    return this.replace("\t", " ".repeat(tabSize))
}

/**
 * Creates a TextPosition for UI-initiated position changes.
 * The offset is set to 0L as a placeholder - the engine recalculates the actual offset
 * based on line/column since the UI only has access to visible lines.
 */
internal fun createUiTextPosition(line: Int, column: Int): TextPosition {
    return TextPosition(
        offset = 0L,
        line = line,
        column = column
    )
}

internal data class PositionCalculationResult(
    val position: TextPosition
)

internal fun calculatePositionFromOffset(
    offset: Offset,
    contentListState: LazyListState,
    visibleLineContent: Map<Int, String>,
    density: Density,
    fontSize: Int,
    tabSize: Int
): PositionCalculationResult? {
    val layoutInfo = contentListState.layoutInfo
    val clickedItem = layoutInfo.visibleItemsInfo.find { item ->
        offset.y >= item.offset && offset.y < (item.offset + item.size)
    }

    if (clickedItem == null) {
        return null
    }

    val lineIndex = clickedItem.index
    val contentPaddingPx = with(density) { 8.dp.toPx() }
    val adjustedX = offset.x - contentPaddingPx

    val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
    val lineContent = visibleLineContent[lineIndex] ?: ""
    val expandedContent = lineContent.expandTabs(tabSize)

    val clickedColumn = if (adjustedX < 0) {
        0
    } else {
        val calculatedColumn = (adjustedX / charWidth).toInt()
        calculatedColumn.coerceIn(0, expandedContent.length)
    }

    val position = createUiTextPosition(
        line = lineIndex,
        column = clickedColumn
    )

    return PositionCalculationResult(position)
}

internal fun findWordBoundaries(text: String, column: Int): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0
    if (column >= text.length) return text.length to text.length

    val wordChars = text.toCharArray()

    fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    var start = column
    var end = column

    if (column < text.length && isWordChar(wordChars[column])) {
        while (start > 0 && isWordChar(wordChars[start - 1])) {
            start--
        }

        while (end < text.length && isWordChar(wordChars[end])) {
            end++
        }
    } else {
        end = (column + 1).coerceAtMost(text.length)
    }

    return start to end
}

internal fun selectWordAt(
    lineIndex: Int,
    column: Int,
    visibleLineContent: Map<Int, String>
): Pair<TextPosition, TextPosition> {
    val lineContent = visibleLineContent[lineIndex] ?: ""
    val (start, end) = findWordBoundaries(lineContent, column)

    return createUiTextPosition(
        line = lineIndex,
        column = start
    ) to createUiTextPosition(
        line = lineIndex,
        column = end
    )
}

internal fun selectLineAt(
    lineIndex: Int,
    visibleLineContent: Map<Int, String>
): Pair<TextPosition, TextPosition> {
    val lineContent = visibleLineContent[lineIndex] ?: ""

    return createUiTextPosition(
        line = lineIndex,
        column = 0
    ) to createUiTextPosition(
        line = lineIndex,
        column = lineContent.length
    )
}
