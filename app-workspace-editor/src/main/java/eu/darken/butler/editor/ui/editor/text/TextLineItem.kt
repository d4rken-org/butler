package eu.darken.butler.editor.ui.editor.text

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition

private data class SelectionBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

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
    searchHighlights: List<Pair<Int, SearchResult>> = emptyList(),
    currentSearchResultIndex: Int = 0,
    modifier: Modifier = Modifier,
    onHeightMeasured: ((Int) -> Unit)? = null,
    onTextLayoutResult: ((TextLayoutResult) -> Unit)? = null,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val cursorColor = MaterialTheme.colorScheme.primary
    // Computed once per (content, tabSize); the draw lambda below re-runs every cursor-blink
    // frame and must not re-expand the line each time
    val displayText = remember(lineContent, tabSize) { lineContent.toDisplayText(tabSize) }

    // Blinking animation when focused
    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isFocused) 0f else 1f,
        animationSpec = if (isFocused) {
            infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1060
                    1f at 0
                    1f at 530
                    0f at 531
                    0f at 1060
                },
                repeatMode = RepeatMode.Restart
            )
        } else {
            infiniteRepeatable(
                animation = tween(1),
                repeatMode = RepeatMode.Restart
            )
        },
        label = "cursor_alpha"
    )

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
            // Engine columns are RAW char indices; the layout/text here is tab-EXPANDED.
            val position = rawToExpandedColumn(lineContent, cursorPosition.column, tabSize)
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
                    layoutResult.getBoundingBox(position - 1).right
                }
                layoutResult != null && position < expandedText.length -> {
                    layoutResult.getBoundingBox(position).left
                }
                layoutResult != null && position == expandedText.length && expandedText.isNotEmpty() -> {
                    layoutResult.getBoundingBox(expandedText.length - 1).right
                }
                else -> {
                    val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                    position * charWidth
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
                            with(density) { (fontSize * 0.6f).sp.toPx() }
                        }
                    }
                    else -> {
                        with(density) { (fontSize * 0.6f).sp.toPx() }
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
            rawLineContent = lineContent,
            lineIndex = lineIndex,
            cursorPosition = cursorPosition,
            selection = selection,
            searchHighlights = searchHighlights,
            currentSearchResultIndex = currentSearchResultIndex,
            wordWrap = wordWrap,
            fontSize = fontSize,
            tabSize = tabSize,
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
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    searchHighlights: List<Pair<Int, SearchResult>> = emptyList(),
    currentSearchResultIndex: Int = 0,
    wordWrap: Boolean,
    fontSize: Int,
    tabSize: Int = 4,
    onTextLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurface
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val searchHighlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val currentSearchHighlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)

    Box(
        modifier = modifier
    ) {
        // Search highlights (rendered first, so selection appears on top)
        searchHighlights.forEach { (resultIndex, result) ->
            val isCurrentResult = resultIndex == currentSearchResultIndex
            val highlightColor = if (isCurrentResult) currentSearchHighlightColor else searchHighlightColor

            // result.position.column is a RAW char index; the rendered text is tab-EXPANDED.
            val rawColumn = result.position.column
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
                // start/end columns are RAW char indices; convert to EXPANDED for the rendered text.
                val selectionStart =
                    if (lineIndex == start.line) rawToExpandedColumn(rawLineContent, start.column, tabSize) else 0
                val selectionEnd =
                    if (lineIndex == end.line) rawToExpandedColumn(rawLineContent, end.column, tabSize) else text.length

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
                            // Fallback to character width estimation
                            val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                            Box(
                                modifier = Modifier
                                    .offset(x = with(density) { (selectionStart * charWidth).toDp() })
                                    .width(with(density) { ((selectionEnd - selectionStart) * charWidth).toDp() })
                                    .height(with(density) { (fontSize * 1.5f).sp.toDp() })
                                    .background(selectionColor)
                            )
                        }
                    } else {
                        // Fallback to character width estimation
                        val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
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

        Text(
            text = if (text.isEmpty()) " " else text,
            style = TextStyle(
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Monospace,
                color = textColor
            ),
            softWrap = wordWrap,
            overflow = TextOverflow.Visible,
            onTextLayout = { result ->
                layoutResult = result
                onTextLayout(result)
            },
            modifier = if (wordWrap) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.wrapContentWidth()
            }
        )
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
        onTextLayout = {},
        modifier = Modifier.fillMaxWidth()
    )
}
