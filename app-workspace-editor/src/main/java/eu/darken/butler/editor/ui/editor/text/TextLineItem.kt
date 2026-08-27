package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.core.syntax.Token
import eu.darken.butler.editor.core.syntax.TokenType
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Half a blink period: the caret is opaque for this long, then hidden for the same. */
private val CURSOR_BLINK_INTERVAL = 530.milliseconds

private data class SelectionBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** Semantics tag for the trailing "⋯ +N" truncation marker chip, used by Compose regression tests. */
internal const val EDITOR_TRUNCATION_MARKER_TEST_TAG = "editor.line.truncationMarker"

/** Semantics tag for the leading "+N ⋯" marker shown when a line's window is anchored past column 0. */
internal const val EDITOR_LEADING_TRUNCATION_MARKER_TEST_TAG = "editor.line.leadingTruncationMarker"

@Composable
internal fun TextLineItem(
    lineIndex: Long,
    lineContent: String,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    isCurrentLine: Boolean,
    isFocused: Boolean,
    wordWrap: Boolean,
    fontSize: Int,
    tabSize: Int,
    charWidthPx: Float,
    hiddenChars: Long = 0L,
    lineStartColumn: Long = 0L,
    tokens: List<Token> = emptyList(),
    searchHighlights: List<Pair<Int, SearchResult>> = emptyList(),
    currentSearchResultIndex: Int = 0,
    modifier: Modifier = Modifier,
    onHeightMeasured: ((Int) -> Unit)? = null,
    onTextLayoutResult: ((TextLayoutResult) -> Unit)? = null,
    onRevealTap: ((forward: Boolean) -> Unit)? = null,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val cursorColor = colorScheme.primary
    // Computed once per (content, tabSize); the draw lambda below re-runs every cursor-blink
    // frame and must not re-expand the line each time
    val displayText = remember(lineContent, tabSize) { lineContent.toDisplayText(tabSize) }
    // Same blink-loop discipline as displayText; keyed on colorScheme so theme switches restyle
    val annotatedText = remember(displayText, tokens, tabSize, colorScheme) {
        if (tokens.isEmpty()) {
            null
        } else {
            buildHighlightedText(displayText, lineContent, tokens, tabSize, colorScheme)
        }
    }

    // Blink only on the line that actually draws a caret; every other line holds a constant alpha
    // and never schedules a frame.
    val shouldBlink = isFocused && isCurrentLine && selection == null
    var cursorAlpha by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(shouldBlink) {
        if (!shouldBlink) {
            cursorAlpha = 1f
            return@LaunchedEffect
        }
        while (true) {
            cursorAlpha = 1f
            delay(CURSOR_BLINK_INTERVAL)
            cursorAlpha = 0f
            delay(CURSOR_BLINK_INTERVAL)
        }
    }

    // When word wrap is OFF, highlight entire line. When ON, we'll draw only the cursor's visual line.
    val backgroundColor = if (isCurrentLine && !wordWrap) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    val lineHighlightColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

    val cursorModifier = if (isCurrentLine && selection == null) {
        Modifier.drawWithContent {
            val expandedText = displayText
            // Engine columns are RAW char indices absolute to the real line; the layout/text here is
            // the tab-EXPANDED window starting at [lineStartColumn]. Localize then clamp: on windowed
            // lines the column can sit far past the visible prefix, and unclamped expansion would draw
            // the cursor kilometers off-screen.
            val position = rawToExpandedColumnClamped(lineContent, cursorPosition.column - lineStartColumn.toInt(), tabSize)
            val layoutResult = textLayoutResult

            // When word wrap is enabled, draw highlight only for the visual line containing cursor
            if (wordWrap && layoutResult != null && layoutResult.lineCount > 0) {
                val cursorOffset = position.coerceIn(0, expandedText.length)
                // Handle boundary case: if cursor is at start of a visual line, it's actually
                // at the end of the previous visual line
                val rawVisualLine = if (cursorOffset < expandedText.length) {
                    layoutResult.getLineForOffset(cursorOffset)
                } else {
                    layoutResult.lineCount - 1
                }
                val visualLine = if (rawVisualLine > 0 && cursorOffset == layoutResult.getLineStart(rawVisualLine)) {
                    rawVisualLine - 1
                } else {
                    rawVisualLine
                }
                val lineTop = layoutResult.getLineTop(visualLine)
                val lineBottom = layoutResult.getLineBottom(visualLine)

                drawRect(
                    color = lineHighlightColor,
                    topLeft = Offset(0f, lineTop),
                    size = Size(size.width, lineBottom - lineTop)
                )
            }

            drawContent()

            // Calculate boundary state for wrapped text - used for both X and Y positioning
            val isAtBoundary = if (wordWrap && layoutResult != null && layoutResult.lineCount > 1) {
                val cursorOffset = position.coerceIn(0, expandedText.length)
                val rawVisualLine = if (cursorOffset < expandedText.length) {
                    layoutResult.getLineForOffset(cursorOffset)
                } else {
                    layoutResult.lineCount - 1
                }
                rawVisualLine > 0 && cursorOffset == layoutResult.getLineStart(rawVisualLine)
            } else {
                false
            }

            // Calculate cursor X - at boundary, use right edge of previous char
            val cursorX = when {
                isAtBoundary && position > 0 && layoutResult != null -> {
                    layoutResult.getBoundingBox((position - 1).coerceAtMost((expandedText.length - 1).coerceAtLeast(0))).right
                }
                layoutResult != null && position < expandedText.length -> {
                    layoutResult.getBoundingBox(position).left
                }
                layoutResult != null && position == expandedText.length && expandedText.isNotEmpty() -> {
                    layoutResult.getBoundingBox(expandedText.length - 1).right
                }
                else -> {
                    // Pre-layout fallback only (real glyph geometry used above once laid out).
                    position * charWidthPx
                }
            }

            // Calculate cursor Y position - for wrapped text, draw on correct visual line
            val (cursorTop, cursorBottom) = if (wordWrap && layoutResult != null && layoutResult.lineCount > 1) {
                val cursorOffset = position.coerceIn(0, expandedText.length)
                val rawVisualLine = if (cursorOffset < expandedText.length) {
                    layoutResult.getLineForOffset(cursorOffset)
                } else {
                    layoutResult.lineCount - 1
                }
                val visualLine = if (isAtBoundary) rawVisualLine - 1 else rawVisualLine
                layoutResult.getLineTop(visualLine) to layoutResult.getLineBottom(visualLine)
            } else {
                0f to size.height
            }

            if (isFocused) {
                drawLine(
                    color = cursorColor.copy(alpha = cursorAlpha),
                    start = Offset(cursorX, cursorTop),
                    end = Offset(cursorX, cursorBottom),
                    strokeWidth = 3.dp.toPx()
                )
            } else {
                val layoutResultForWidth = textLayoutResult
                val charWidth = when {
                    layoutResultForWidth != null && position < expandedText.length - 1 -> {
                        val currentBox = layoutResultForWidth.getBoundingBox(position)
                        val nextBox = layoutResultForWidth.getBoundingBox(position + 1)
                        (nextBox.left - currentBox.left).coerceAtLeast(0f)
                    }
                    layoutResultForWidth != null && position == expandedText.length - 1 && expandedText.isNotEmpty() -> {
                        val box = layoutResultForWidth.getBoundingBox(position)
                        if (position > 0) {
                            layoutResultForWidth.getBoundingBox(position - 1)
                            (box.right - box.left).coerceAtLeast(0f)
                        } else {
                            charWidthPx
                        }
                    }
                    else -> {
                        charWidthPx
                    }
                }

                val blockWidth = if (position < expandedText.length) {
                    charWidth
                } else {
                    charWidth * 0.3f
                }

                drawRect(
                    color = cursorColor.copy(alpha = 0.4f),
                    topLeft = Offset(cursorX, cursorTop),
                    size = Size(blockWidth, cursorBottom - cursorTop)
                )
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .then(cursorModifier)
            .onGloballyPositioned { coordinates ->
                onHeightMeasured?.invoke(coordinates.size.height)
            }
    ) {
        SelectableText(
            text = displayText.ifEmpty { " " },
            annotatedText = annotatedText,
            rawLineContent = lineContent,
            lineIndex = lineIndex,
            cursorPosition = cursorPosition,
            selection = selection,
            searchHighlights = searchHighlights,
            currentSearchResultIndex = currentSearchResultIndex,
            wordWrap = wordWrap,
            fontSize = fontSize,
            tabSize = tabSize,
            charWidthPx = charWidthPx,
            hiddenChars = hiddenChars,
            lineStartColumn = lineStartColumn,
            onRevealTap = onRevealTap,
            onTextLayout = { layoutResult ->
                textLayoutResult = layoutResult
                onTextLayoutResult?.invoke(layoutResult)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun SelectableText(
    text: String,
    rawLineContent: String,
    lineIndex: Long,
    annotatedText: AnnotatedString? = null,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    searchHighlights: List<Pair<Int, SearchResult>> = emptyList(),
    currentSearchResultIndex: Int = 0,
    wordWrap: Boolean,
    fontSize: Int,
    tabSize: Int = 4,
    charWidthPx: Float,
    hiddenChars: Long = 0L,
    lineStartColumn: Long = 0L,
    onRevealTap: ((forward: Boolean) -> Unit)? = null,
    onTextLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurface
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val searchHighlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val currentSearchHighlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)

    // Trailing space reserved for the truncation marker: end-PADDING on the measured Text grows
    // its box without shifting TextLayoutResult content coordinates, so the chip (an overlay
    // that never extends measured width) is scroll-reachable and never covers the last glyph.
    val markerText = if (hiddenChars > 0) {
        stringResource(R.string.editor_line_truncated_marker, hiddenChars)
    } else {
        null
    }
    val markerReserve = if (markerText != null) {
        with(density) { ((markerText.length + 3) * fontSize * 0.6f).sp.toDp() }
    } else {
        0.dp
    }

    Box(
        modifier = modifier
    ) {
        // Search highlights (rendered first, so selection appears on top)
        searchHighlights.forEach { (resultIndex, result) ->
            val isCurrentResult = resultIndex == currentSearchResultIndex
            val highlightColor = if (isCurrentResult) currentSearchHighlightColor else searchHighlightColor

            // result.position.column is an absolute RAW char index; localize to the rendered window
            // (may go negative/past-end for a match straddling the window - the coerceIn below clips
            // it to the visible intersection). The rendered text is tab-EXPANDED.
            val rawColumn = result.position.column - lineStartColumn.toInt()
            val matchLength = result.matchText.length
            val highlightStart = rawToExpandedColumn(rawLineContent, rawColumn, tabSize)
            val highlightEnd = rawToExpandedColumn(rawLineContent, rawColumn + matchLength, tabSize)

            if (highlightStart < text.length && highlightEnd > 0) {
                val adjustedStart = highlightStart.coerceIn(0, text.length)
                val adjustedEnd = highlightEnd.coerceIn(0, text.length)

                if (adjustedStart < adjustedEnd) {
                    val layout = layoutResult

                    if (layout != null && wordWrap && layout.lineCount > 1) {
                        // Multi-line wrapped text
                        val startVisualLine =
                            layout.getLineForOffset(adjustedStart.coerceIn(0, text.length.coerceAtLeast(1) - 1))
                        val endVisualLine =
                            layout.getLineForOffset((adjustedEnd - 1).coerceIn(0, text.length.coerceAtLeast(1) - 1))

                        for (visualLine in startVisualLine..endVisualLine) {
                            val lineStartOffset = layout.getLineStart(visualLine)
                            val lineEndOffset = layout.getLineEnd(visualLine)

                            val hlStart = adjustedStart.coerceIn(lineStartOffset, lineEndOffset)
                            val hlEnd = adjustedEnd.coerceIn(lineStartOffset, lineEndOffset)

                            if (hlStart < hlEnd && text.isNotEmpty()) {
                                val bounds = runCatching {
                                    val startBounds = layout.getBoundingBox(hlStart.coerceIn(0, text.length - 1))
                                    val endBounds = layout.getBoundingBox((hlEnd - 1).coerceIn(0, text.length - 1))
                                    SelectionBounds(
                                        startBounds.left,
                                        layout.getLineTop(visualLine),
                                        endBounds.right - startBounds.left,
                                        layout.getLineBottom(visualLine) - layout.getLineTop(visualLine)
                                    )
                                }.getOrNull()

                                bounds?.let { b ->
                                    Box(
                                        modifier = Modifier
                                            .offset(
                                                x = with(density) { b.left.toDp() },
                                                y = with(density) { b.top.toDp() }
                                            )
                                            .width(with(density) { b.width.toDp() })
                                            .height(with(density) { b.height.toDp() })
                                            .background(highlightColor)
                                    )
                                }
                            }
                        }
                    } else if (layout != null && adjustedStart < text.length) {
                        // Single line
                        val bounds = runCatching {
                            val startBounds = layout.getBoundingBox(adjustedStart)
                            val endBounds =
                                layout.getBoundingBox((adjustedEnd - 1).coerceAtLeast(0).coerceAtMost(text.length - 1))
                            SelectionBounds(
                                startBounds.left,
                                0f,
                                endBounds.right - startBounds.left,
                                layout.size.height.toFloat()
                            )
                        }.getOrNull()

                        bounds?.let { b ->
                            Box(
                                modifier = Modifier
                                    .offset(x = with(density) { b.left.toDp() })
                                    .width(with(density) { b.width.toDp() })
                                    .height(with(density) { b.height.toDp() })
                                    .background(highlightColor)
                            )
                        }
                    }
                }
            }
        }

        // Selection highlighting (rendered after search highlights)
        selection?.let { (start, end) ->
            if (lineIndex >= start.line && lineIndex <= end.line) {
                // start/end columns are RAW char indices; convert to EXPANDED for the rendered text,
                // clamped into the line FIRST: on display-truncated lines selection columns can sit
                // far past the visible prefix, and the unguarded fallback below would then build a
                // Box wider than Compose Constraints can represent. Selections end at the marker.
                val selectionStart =
                    if (lineIndex == start.line) {
                        rawToExpandedColumnClamped(rawLineContent, start.column - lineStartColumn.toInt(), tabSize)
                    } else {
                        0
                    }
                val selectionEnd =
                    if (lineIndex == end.line) {
                        rawToExpandedColumnClamped(rawLineContent, end.column - lineStartColumn.toInt(), tabSize)
                    } else {
                        text.length
                    }

                if (selectionStart < selectionEnd) {
                    val layout = layoutResult
                    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)

                    if (layout != null && wordWrap && layout.lineCount > 1) {
                        // Multi-line wrapped text: draw selection for each visual line
                        val startVisualLine =
                            layout.getLineForOffset(selectionStart.coerceIn(0, text.length.coerceAtLeast(1) - 1))
                        val endVisualLine =
                            layout.getLineForOffset((selectionEnd - 1).coerceIn(0, text.length.coerceAtLeast(1) - 1))

                        for (visualLine in startVisualLine..endVisualLine) {
                            val lineStartOffset = layout.getLineStart(visualLine)
                            val lineEndOffset = layout.getLineEnd(visualLine)

                            // Calculate selection bounds for this visual line
                            val selStartInLine = selectionStart.coerceIn(lineStartOffset, lineEndOffset)
                            val selEndInLine = selectionEnd.coerceIn(lineStartOffset, lineEndOffset)

                            if (selStartInLine < selEndInLine && text.isNotEmpty()) {
                                // Calculate bounds outside composable scope
                                val bounds = runCatching {
                                    val startBounds = layout.getBoundingBox(selStartInLine.coerceIn(0, text.length - 1))
                                    val endBounds =
                                        layout.getBoundingBox((selEndInLine - 1).coerceIn(0, text.length - 1))
                                    val left = startBounds.left
                                    val right = endBounds.right
                                    val top = layout.getLineTop(visualLine)
                                    val bottom = layout.getLineBottom(visualLine)
                                    SelectionBounds(left, top, right - left, bottom - top)
                                }.getOrNull()

                                bounds?.let { b ->
                                    Box(
                                        modifier = Modifier
                                            .offset(
                                                x = with(density) { b.left.toDp() },
                                                y = with(density) { b.top.toDp() }
                                            )
                                            .width(with(density) { b.width.toDp() })
                                            .height(with(density) { b.height.toDp() })
                                            .background(selectionColor)
                                    )
                                }
                            }
                        }
                    } else if (layout != null && selectionStart < text.length && selectionEnd <= text.length) {
                        // Single line or no wrap: calculate bounds first
                        val bounds = runCatching {
                            val startBounds = if (selectionStart < text.length) {
                                layout.getBoundingBox(selectionStart)
                            } else {
                                layout.getBoundingBox(text.length - 1).let { box ->
                                    box.copy(left = box.right)
                                }
                            }

                            val endBounds = if (selectionEnd > 0 && selectionEnd <= text.length) {
                                layout.getBoundingBox((selectionEnd - 1).coerceAtLeast(0))
                            } else {
                                startBounds
                            }

                            SelectionBounds(
                                startBounds.left,
                                0f,
                                endBounds.right - startBounds.left,
                                layout.size.height.toFloat()
                            )
                        }.getOrNull()

                        if (bounds != null) {
                            Box(
                                modifier = Modifier
                                    .offset(x = with(density) { bounds.left.toDp() })
                                    .width(with(density) { bounds.width.toDp() })
                                    .height(with(density) { bounds.height.toDp() })
                                    .background(selectionColor)
                            )
                        } else {
                            // Pre-layout fallback only (bounds path above uses real glyph geometry).
                            val charWidth = charWidthPx
                            Box(
                                modifier = Modifier
                                    .offset(x = with(density) { (selectionStart * charWidth).toDp() })
                                    .width(with(density) { ((selectionEnd - selectionStart) * charWidth).toDp() })
                                    .height(with(density) { (fontSize * 1.5f).sp.toDp() })
                                    .background(selectionColor)
                            )
                        }
                    } else {
                        // Pre-layout fallback only (bounds path above uses real glyph geometry).
                        val charWidth = charWidthPx
                        Box(
                            modifier = Modifier
                                .offset(x = with(density) { (selectionStart * charWidth).toDp() })
                                .width(with(density) { ((selectionEnd - selectionStart) * charWidth).toDp() })
                                .height(with(density) {
                                    val height: Float =
                                        layoutResult?.size?.height?.toFloat() ?: (fontSize * 1.5f).sp.toPx()
                                    height.toDp()
                                })
                                .background(selectionColor)
                        )
                    }
                }
            }
        }

        val textStyle = TextStyle(
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor
        )
        val handleTextLayout: (TextLayoutResult) -> Unit = { result ->
            layoutResult = result
            onTextLayout(result)
        }
        val textModifier = (if (wordWrap) Modifier.fillMaxWidth() else Modifier.wrapContentWidth())
            .then(if (markerText != null) Modifier.padding(end = markerReserve) else Modifier)
        // Branch at the terminal call: without tokens this stays the exact pre-highlighting
        // String path. Span colors don't move glyphs, so every TextLayoutResult consumer
        // (cursor, selection, search overlays, hit-testing) is unaffected by the swap.
        if (annotatedText != null && annotatedText.text.isNotEmpty()) {
            Text(
                text = annotatedText,
                style = textStyle,
                softWrap = wordWrap,
                overflow = TextOverflow.Visible,
                onTextLayout = handleTextLayout,
                modifier = textModifier
            )
        } else {
            Text(
                text = if (text.isEmpty()) " " else text,
                style = textStyle,
                softWrap = wordWrap,
                overflow = TextOverflow.Visible,
                onTextLayout = handleTextLayout,
                modifier = textModifier
            )
        }

        // Truncation marker: a sibling OVERLAY - never concatenated into the measured Text (that
        // would shift every getBoundingBox index) and never fed to onTextLayout. Tapping the chip
        // slides the window forward; taps outside it fall through to line hit-testing, which
        // self-clamps (clickable consumes the up-event before the ancestor's detectTapGestures).
        if (markerText != null) {
            val layout = layoutResult
            val markerModifier = if (wordWrap && layout != null && layout.lineCount > 0) {
                val lastVisualLine = layout.lineCount - 1
                Modifier.offset(
                    x = with(density) { layout.getLineRight(lastVisualLine).toDp() } + 4.dp,
                    y = with(density) { layout.getLineTop(lastVisualLine).toDp() },
                )
            } else {
                Modifier.align(Alignment.CenterEnd)
            }
            Text(
                text = markerText,
                style = TextStyle(
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                softWrap = false,
                modifier = markerModifier
                    .testTag(EDITOR_TRUNCATION_MARKER_TEST_TAG)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(4.dp),
                    )
                    .then(
                        if (onRevealTap != null) {
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.editor_line_truncated_marker_next_action),
                                role = Role.Button,
                            ) { onRevealTap(true) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 6.dp),
            )
        }

        // Leading truncation marker: chars hidden BEFORE the window (line anchored past column 0).
        // Like the trailing marker it's an OVERLAY outside the measured Text - it reserves no start
        // padding, so glyph/getBoundingBox indices are never shifted. Pinned to the window's start
        // edge; tapping the chip slides the window backward, taps outside it fall through to line
        // hit-testing (which self-clamps).
        if (lineStartColumn > 0) {
            Text(
                text = stringResource(R.string.editor_line_leading_truncated_marker, lineStartColumn),
                style = TextStyle(
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(if (wordWrap) Alignment.TopStart else Alignment.CenterStart)
                    .testTag(EDITOR_LEADING_TRUNCATION_MARKER_TEST_TAG)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(4.dp),
                    )
                    .then(
                        if (onRevealTap != null) {
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.editor_line_truncated_marker_previous_action),
                                role = Role.Button,
                            ) { onRevealTap(false) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextLineItemPreview() {
    TextLineItem(
        lineIndex = 0,
        lineContent = "fun calculateSum(a: Int, b: Int): Int {",
        cursorPosition = TextPosition(offset = 15, line = 0, column = 15),
        selection = null,
        isCurrentLine = true,
        isFocused = true,
        wordWrap = false,
        fontSize = 14,
        tabSize = 4,
        charWidthPx = 8.4f,
        modifier = Modifier.fillMaxWidth()
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextLineItemHighlightedPreview() {
    TextLineItem(
        lineIndex = 0,
        lineContent = "const sum = a + 42 // total",
        tokens = listOf(
            Token(0, 5, TokenType.KEYWORD),
            Token(16, 18, TokenType.NUMBER),
            Token(19, 27, TokenType.COMMENT),
        ),
        cursorPosition = TextPosition(offset = 0, line = 0, column = 0),
        selection = null,
        isCurrentLine = false,
        isFocused = false,
        wordWrap = false,
        fontSize = 14,
        tabSize = 4,
        charWidthPx = 8.4f,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextLineItemTruncatedPreview() {
    TextLineItem(
        lineIndex = 0,
        lineContent = "val payload = \"" + "x".repeat(40),
        hiddenChars = 1_234_567L,
        cursorPosition = TextPosition(offset = 0, line = 0, column = 0),
        selection = null,
        isCurrentLine = false,
        isFocused = false,
        wordWrap = false,
        fontSize = 14,
        tabSize = 4,
        charWidthPx = 8.4f,
        modifier = Modifier.fillMaxWidth()
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SelectableTextPreview() {
    SelectableText(
        text = "fun calculateSum(a: Int, b: Int): Int {",
        rawLineContent = "fun calculateSum(a: Int, b: Int): Int {",
        lineIndex = 0,
        cursorPosition = TextPosition(offset = 15, line = 0, column = 15),
        selection = TextPosition(offset = 4, line = 0, column = 4) to TextPosition(
            offset = 16,
            line = 0,
            column = 16
        ),
        wordWrap = false,
        fontSize = 14,
        charWidthPx = 8.4f,
        onTextLayout = {},
        modifier = Modifier.fillMaxWidth()
    )
}
