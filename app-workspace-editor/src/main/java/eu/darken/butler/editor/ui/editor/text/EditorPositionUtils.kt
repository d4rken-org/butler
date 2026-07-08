package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.TextPosition

private val tag = logTag("Editor", "PositionUtils")

/**
 * Narrows a Long line/count to Int for Compose/framework APIs that force Int (LazyColumn item
 * counts, plural quantities). Saturates instead of wrapping; values past Int.MAX_VALUE are not
 * addressable in the current UI (engine-level line addressing stays exact).
 */
internal fun Long.toIntSaturated(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

/**
 * The single raw→display transform: expands tabs and renders C0 control characters (and DEL) as
 * visible Unicode Control Pictures (U+0000 → ␀ etc.). Every display, measurement, hit-testing,
 * and selection-geometry site must use this same transform. Control substitution is 1 char →
 * 1 char, so the raw↔expanded column mapping is unaffected - only tabs change width.
 */
internal fun String.toDisplayText(tabSize: Int): String {
    if (none { it == '\t' || it.code < 0x20 || it.code == 0x7F }) return this
    val ts = tabSize.coerceAtLeast(1)
    val sb = StringBuilder(length + ts * 4)
    for (ch in this) {
        when {
            ch == '\t' -> repeat(ts) { sb.append(' ') }
            ch.code < 0x20 -> sb.append((CONTROL_PICTURES_BASE + ch.code).toChar())
            ch.code == 0x7F -> sb.append('␡')
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

private const val CONTROL_PICTURES_BASE = 0x2400

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
 * A single contiguous text edit expressed as flat (UTF-16 code unit) offsets into the OLD text:
 * the range [start, end) is replaced by [inserted].
 *
 * - Pure insert: [start] == [end], [inserted] non-empty.
 * - Pure delete: [start] < [end], [inserted] empty.
 * - Replace: [start] < [end], [inserted] non-empty.
 */
internal data class TextEdit(
    val start: Int,
    val end: Int,
    val inserted: String,
)

/**
 * Computes the minimal single-region edit that turns [old] into [new] by stripping the common
 * prefix and suffix. Covers append, prepend, mid-insert, delete, equal-length replace (autocorrect),
 * and full replace. Returns null when the strings are equal.
 *
 * Offsets are UTF-16 code units, consistent with [TextPosition.column], [androidx.compose.ui.text.TextRange],
 * and the document buffer. The prefix/suffix boundaries are nudged off any lone surrogate so [inserted]
 * never contains half of a surrogate pair.
 */
internal fun computeTextEdit(old: String, new: String): TextEdit? {
    if (old == new) return null
    val oldLen = old.length
    val newLen = new.length

    val maxCommon = minOf(oldLen, newLen)

    // Common prefix length.
    var p = 0
    while (p < maxCommon && old[p] == new[p]) p++
    // A prefix that ends on a high surrogate would split a pair; back off so the whole pair is replaced.
    if (p > 0 && old[p - 1].isHighSurrogate()) p--

    // Common suffix length (not overlapping the prefix).
    var s = 0
    val maxSuffix = maxCommon - p
    while (s < maxSuffix && old[oldLen - 1 - s] == new[newLen - 1 - s]) s++
    // A suffix that starts on a low surrogate would split a pair; back off so the whole pair is replaced.
    if (s > 0 && old[oldLen - s].isLowSurrogate()) s--

    return TextEdit(
        start = p,
        end = oldLen - s,
        inserted = new.substring(p, newLen - s),
    )
}

/**
 * Maps a flat offset into the joined visible field text (visible lines joined by '\n', starting at
 * absolute line [visibleRangeStart]) to an engine [TextPosition].
 *
 * The synthetic '\n' between lines occupies the gap: in "abc\ndef" offset 3 → (line0, col3) and
 * offset 4 → (line1, col0). Columns are RAW char indices (the hidden field is never tab-expanded).
 * Offsets past the end clamp to the end of the last visible line.
 */
internal fun flatOffsetToPosition(
    visibleLines: List<String>,
    visibleRangeStart: Long,
    flatOffset: Int,
): TextPosition {
    if (visibleLines.isEmpty()) return createUiTextPosition(line = visibleRangeStart, column = 0)
    var remaining = flatOffset.coerceAtLeast(0)
    for (i in visibleLines.indices) {
        val line = visibleLines[i]
        if (remaining <= line.length) {
            return createUiTextPosition(line = visibleRangeStart + i, column = remaining)
        }
        remaining -= line.length + 1 // +1 for the joining '\n'
    }
    val lastIndex = visibleLines.lastIndex
    return createUiTextPosition(line = visibleRangeStart + lastIndex, column = visibleLines[lastIndex].length)
}

/**
 * Inverse of [flatOffsetToPosition]: maps an engine [position] to a flat offset into the joined visible
 * field text. Returns null when [position] falls outside the visible window so callers can skip syncing
 * rather than emit a bogus offset.
 */
internal fun positionToFlatOffset(
    visibleLines: List<String>,
    visibleRangeStart: Long,
    position: TextPosition,
): Int? {
    val lineOffset = position.line - visibleRangeStart
    if (lineOffset < 0 || lineOffset > visibleLines.lastIndex) return null
    val lineIndex = lineOffset.toInt()
    var offset = 0
    for (i in 0 until lineIndex) {
        offset += visibleLines[i].length + 1 // +1 for the joining '\n'
    }
    return offset + position.column.coerceIn(0, visibleLines[lineIndex].length)
}

/**
 * Decides how the hidden field should be synced FROM authoritative engine state (when the user is not
 * actively typing). Returns the selection to apply to a rebuilt [TextFieldValue], or null to leave the
 * field untouched.
 *
 * The field is rewritten when the engine content differs ([engineContent] != [fieldText]) OR the engine
 * caret/selection moved relative to the field ([mappedSelection] != [fieldSelection]) — e.g. a tap, arrow
 * key, or undo. This must happen even mid-IME-composition: an explicit caret move has to reposition where
 * the next input lands, otherwise typing inserts at the stale offset. When nothing moved (a spurious
 * re-fire, e.g. a viewport scroll that didn't change the caret) it returns null so an in-progress
 * composition is preserved. When the engine caret is outside the visible window ([mappedSelection] null)
 * but the text changed, the previous caret is clamped into the new text.
 */
internal fun computeFieldSelectionSync(
    fieldText: String,
    fieldSelection: TextRange,
    engineContent: String,
    mappedSelection: TextRange?,
): TextRange? {
    val textChanged = fieldText != engineContent
    return when {
        mappedSelection != null && (textChanged || fieldSelection != mappedSelection) -> mappedSelection
        mappedSelection == null && textChanged -> TextRange(fieldSelection.end.coerceIn(0, engineContent.length))
        else -> null
    }
}

/**
 * Creates a TextPosition for UI-initiated position changes.
 * The offset is set to 0L as a placeholder - the engine recalculates the actual offset
 * based on line/column since the UI only has access to visible lines.
 */
internal fun createUiTextPosition(line: Long, column: Int): TextPosition {
    return TextPosition(
        offset = 0L,
        line = line,
        column = column
    )
}

internal data class PositionCalculationResult(
    val position: TextPosition
)

/**
 * Pre-layout fallback for tap hit-testing: maps a tab-expanded X (px, relative to the line's text
 * start) to an expanded column using a fixed monospace advance. Past-center rounding matches the
 * layout branch (advance to the next column only once the tap passes the cell midpoint). Only used
 * for the one frame before a line's [TextLayoutResult] is available; once it is, exact glyph
 * geometry takes over.
 */
internal fun expandedColumnFromX(adjustedX: Float, charWidthPx: Float, maxColumn: Int): Int {
    if (charWidthPx <= 0f || adjustedX <= 0f) return 0
    val cell = (adjustedX / charWidthPx).toInt()
    val column = if (adjustedX > cell * charWidthPx + charWidthPx / 2f) cell + 1 else cell
    return column.coerceIn(0, maxColumn.coerceAtLeast(0))
}

internal fun calculatePositionFromOffset(
    offset: Offset,
    contentListState: LazyListState,
    visibleLineContent: Map<Long, String>,
    density: Density,
    charWidthPx: Float,
    tabSize: Int,
    textLayouts: Map<Long, TextLayoutResult> = emptyMap(),
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

    val lineIndex = clickedItem.index.toLong()
    val contentPaddingPx = with(density) { 8.dp.toPx() }
    val adjustedX = offset.x - contentPaddingPx

    val lineContent = visibleLineContent[lineIndex] ?: ""
    val expandedContent = lineContent.toDisplayText(tabSize)

    // Use the line's real TextLayoutResult for exact glyph geometry, but only when it matches the
    // CURRENT display text (same length). A stale layout from just-changed content — or the " "
    // placeholder rendered for an empty line — would otherwise map a far-right tap to the stale line
    // end; the measured-advance fallback covers that one frame until the layout catches up.
    val layout = textLayouts[lineIndex]
    val clickedColumn = if (layout != null && layout.layoutInput.text.length == expandedContent.length) {
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
        // Pre-layout fallback: map X to a column via the measured monospace advance.
        expandedColumnFromX(adjustedX, charWidthPx, expandedContent.length)
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
    lineIndex: Long,
    column: Int,
    visibleLineContent: Map<Long, String>
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
    lineIndex: Long,
    visibleLineContent: Map<Long, String>
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
