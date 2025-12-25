package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
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
    tabSize: Int,
    wordWrap: Boolean = false,
    textLayouts: Map<Int, TextLayoutResult> = emptyMap(),
    contentPaddingTop: Float = 0f,
): PositionCalculationResult? {
    val layoutInfo = contentListState.layoutInfo
    // Adjust Y for content padding - tap offset includes padding, item.offset doesn't
    val adjustedY = offset.y - contentPaddingTop
    val clickedItem = layoutInfo.visibleItemsInfo.find { item ->
        adjustedY >= item.offset && adjustedY < (item.offset + item.size)
    }

    if (clickedItem == null) {
        return null
    }

    val lineIndex = clickedItem.index
    val contentPaddingPx = with(density) { 8.dp.toPx() }
    val adjustedX = offset.x - contentPaddingPx

    val lineContent = visibleLineContent[lineIndex] ?: ""
    val expandedContent = lineContent.expandTabs(tabSize)

    // When word wrap is enabled and we have TextLayoutResult, use it for accurate position
    val clickedColumn = if (wordWrap && textLayouts.containsKey(lineIndex)) {
        val layout = textLayouts[lineIndex]!!
        // Calculate Y position relative to the item (not the list)
        val relativeY = adjustedY - clickedItem.offset
        // Use TextLayoutResult to get character offset at tap position
        val charOffset = layout.getOffsetForPosition(Offset(adjustedX.coerceAtLeast(0f), relativeY.coerceAtLeast(0f)))

        // Get the visual line containing this character
        val visualLine = if (expandedContent.isNotEmpty()) {
            layout.getLineForOffset(charOffset.coerceIn(0, expandedContent.length - 1))
        } else {
            0
        }
        val visualLineEnd = layout.getLineEnd(visualLine)

        // Check if tap is past the right edge of the last char on this visual line
        val lastCharOnVisualLine = (visualLineEnd - 1).coerceAtLeast(0)
        val adjustedOffset = if (charOffset >= lastCharOnVisualLine && expandedContent.isNotEmpty()) {
            try {
                val charBounds = layout.getBoundingBox(lastCharOnVisualLine.coerceIn(0, expandedContent.length - 1))
                if (adjustedX > charBounds.right) {
                    visualLineEnd  // Place cursor at end of visual line
                } else if (adjustedX > (charBounds.left + charBounds.right) / 2) {
                    charOffset + 1  // Past center, move one position right
                } else {
                    charOffset
                }
            } catch (e: Exception) {
                charOffset
            }
        } else if (charOffset < expandedContent.length && expandedContent.isNotEmpty()) {
            // Normal case: check if past center of current char
            try {
                val charBounds = layout.getBoundingBox(charOffset)
                if (adjustedX > (charBounds.left + charBounds.right) / 2) charOffset + 1 else charOffset
            } catch (e: Exception) {
                charOffset
            }
        } else {
            charOffset
        }

        adjustedOffset.coerceIn(0, expandedContent.length)
    } else {
        // Fallback: estimate column from X position
        val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
        if (adjustedX < 0) {
            0
        } else {
            val calculatedColumn = (adjustedX / charWidth).toInt()
            calculatedColumn.coerceIn(0, expandedContent.length)
        }
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
