package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.TextPosition

private val tag = logTag("Editor", "PositionUtils")

internal fun String.expandTabs(tabSize: Int): String {
    return this.replace("\t", " ".repeat(tabSize))
}

/**
 * Converts a RAW column (char index into the engine line) to the EXPANDED visual column used by the
 * tab-expanded rendering layout. Each tab before [rawCol] contributes [tabSize] visual columns instead
 * of 1. Columns at/after the end of [line] map 1:1 (so an end-of-line cursor stays correct).
 */
internal fun rawToExpandedColumn(line: String, rawCol: Int, tabSize: Int): Int {
    val ts = tabSize.coerceAtLeast(1)
    var expanded = 0
    val inLine = minOf(rawCol, line.length)
    for (i in 0 until inLine) {
        expanded += if (line[i] == '\t') ts else 1
    }
    if (rawCol > line.length) expanded += rawCol - line.length
    return expanded
}

/**
 * Inverse of [rawToExpandedColumn]: maps an EXPANDED visual column back to a RAW char index.
 * Tie-break: a column that falls *inside* a tab's expanded cells snaps to that tab's raw index (its
 * left edge). Columns past the expanded line width map 1:1.
 */
internal fun expandedToRawColumn(line: String, expandedCol: Int, tabSize: Int): Int {
    val ts = tabSize.coerceAtLeast(1)
    var expanded = 0
    var raw = 0
    while (raw < line.length) {
        val width = if (line[raw] == '\t') ts else 1
        if (expanded + width > expandedCol) return raw
        expanded += width
        raw++
    }
    // Past the end of the (expanded) line: remaining columns are 1:1.
    return raw + (expandedCol - expanded).coerceAtLeast(0)
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
                log(
                    tag,
                    WARN
                ) { "getBoundingBox failed for offset $lastCharOnVisualLine (text length: ${expandedContent.length}): ${e.message}" }
                charOffset
            }
        } else if (charOffset < expandedContent.length && expandedContent.isNotEmpty()) {
            // Normal case: check if past center of current char
            try {
                val charBounds = layout.getBoundingBox(charOffset)
                if (adjustedX > (charBounds.left + charBounds.right) / 2) charOffset + 1 else charOffset
            } catch (e: Exception) {
                log(
                    tag,
                    WARN
                ) { "getBoundingBox failed for offset $charOffset (text length: ${expandedContent.length}): ${e.message}" }
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

    // clickedColumn is an EXPANDED (tab-expanded) visual column; the engine expects a RAW char index.
    val rawColumn = expandedToRawColumn(lineContent, clickedColumn, tabSize)
    val position = createUiTextPosition(
        line = lineIndex,
        column = rawColumn
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
