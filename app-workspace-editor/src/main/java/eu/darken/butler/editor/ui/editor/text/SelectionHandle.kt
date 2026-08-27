package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.TextPosition

@Composable
internal fun SelectionHandle(
    position: TextPosition,
    contentListState: LazyListState,
    lineNumberWidth: Dp,
    horizontalScrollState: ScrollState,
    actualCharWidth: Float,
    onDrag: (Offset) -> Unit,
    /** Gesture boundaries, so callers can capture and release per-drag state around [onDrag]. */
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
    wordWrap: Boolean = false,
    textLayouts: Map<Long, TextLayoutResult> = emptyMap(),
    visibleLineContent: Map<Long, String> = emptyMap(),
    tabSize: Int = 4,
    lineStartColumn: Long = 0L,
) {
    // [position.column] is an absolute engine column; the rendered line starts at its window anchor.
    val localColumn = (position.column - lineStartColumn.toInt())
    val density = LocalDensity.current
    val handleColor = MaterialTheme.colorScheme.primary

    // Pre-calculate constant values that don't change during composition
    val charWidth = actualCharWidth
    val contentPaddingPx = with(density) { 8.dp.toPx() }
    val lineNumberWidthPx = with(density) { lineNumberWidth.toPx() }
    val handleHalfWidth = with(density) { 12.dp.toPx() }  // Half of 24.dp handle width

    // Read the line's layout and content during COMPOSITION: the textLayouts map is a stable
    // instance, so as an effect key it never re-fires — only the entry read here subscribes to
    // a layout landing or being replaced (wrap toggle, content edit, window slide).
    val lineLayout = textLayouts[position.line]
    val rawLine = visibleLineContent[position.line] ?: ""

    // Engine columns are RAW char indices; the rendered line is tab-EXPANDED, so convert for all
    // pixel math (column * charWidth and layout indexing below). Clamped into the line FIRST:
    // display-truncated lines can carry columns far past the visible prefix, and multiplying an
    // unclamped expanded column by charWidth translates the handle kilometers off-screen.
    val currentPositionColumn by rememberUpdatedState(
        rawToExpandedColumnClamped(rawLine, localColumn, tabSize)
    )

    // The drag coroutine below outlives recompositions and must never invoke a stale capture.
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    // Use simple state to store Y position, updated via LaunchedEffect observing layout
    var yPosition by remember { mutableStateOf<Float?>(null) }
    var visualLineOffsetY by remember { mutableStateOf(0f) }
    var visualCharOffsetX by remember { mutableStateOf(-1f) }  // -1 = use column * charWidth

    // Observe layout changes and update Y position
    // This isolates layout reading from composition/offset lambda
    LaunchedEffect(position.line) {
        snapshotFlow {
            contentListState.layoutInfo.visibleItemsInfo.find { it.index.toLong() == position.line }?.offset
        }.collect { offset ->
            yPosition = offset?.toFloat()
        }
    }

    // Calculate visual line offset and X position from the line's layout (both wrap modes: the
    // measured-M advance is wrong for CJK/wide glyphs, only real glyph geometry is exact). Keyed on
    // lineStartColumn too: a horizontal window slide changes the caret's LOCAL column even if
    // position.column doesn't.
    LaunchedEffect(position.line, position.column, wordWrap, lineLayout, rawLine, lineStartColumn, tabSize) {
        // The layout is built from the tab-EXPANDED line, so work in expanded columns/length.
        val textLength = rawLine.toDisplayText(tabSize).length
        // A layout shorter than the current content is stale (edit landed, relayout pending) and
        // indexing it below would throw — fall back until the fresh layout arrives.
        if (lineLayout != null && textLength in 1..lineLayout.layoutInput.text.length) {
            val columnForCalc = rawToExpandedColumnClamped(rawLine, localColumn, tabSize).coerceIn(0, textLength)

            // Calculate visual line, handling boundary case
            // getLineForOffset(N) returns the line where char N starts, but if N equals
            // getLineStart of that line (i.e., it's at a line boundary), cursor should
            // appear at the END of the previous visual line, not start of next
            val rawVisualLine = if (columnForCalc < textLength) {
                lineLayout.getLineForOffset(columnForCalc)
            } else {
                lineLayout.lineCount - 1
            }
            val isAtBoundary = rawVisualLine > 0 && columnForCalc == lineLayout.getLineStart(rawVisualLine)
            val visualLine = if (isAtBoundary) rawVisualLine - 1 else rawVisualLine
            visualLineOffsetY = lineLayout.getLineTop(visualLine)

            // Calculate X position from TextLayoutResult
            // At boundary: use right edge of previous char (end of visual line)
            // Otherwise: use left edge of current char
            visualCharOffsetX = if (isAtBoundary && columnForCalc > 0) {
                lineLayout.getBoundingBox(columnForCalc - 1).right
            } else if (columnForCalc < textLength) {
                lineLayout.getBoundingBox(columnForCalc).left
            } else {
                lineLayout.getBoundingBox(textLength - 1).right
            }
        } else if (textLength == 0) {
            visualLineOffsetY = 0f
            visualCharOffsetX = 0f
        } else {
            visualLineOffsetY = 0f
            visualCharOffsetX = -1f  // No usable layout yet: use column * charWidth fallback
        }
    }

    if (yPosition != null) {
        // Position the handle using graphicsLayer for hardware-accelerated smooth dragging
        Box(
            modifier = modifier
                .size(24.dp, 24.dp)
                .graphicsLayer {
                    // Safely capture yPosition at start of lambda to prevent race conditions
                    val currentYPos = yPosition ?: return@graphicsLayer

                    // Layout-based glyph X when available, measured-advance fallback before the
                    // first layout lands. Wrapped lines never scroll horizontally.
                    val horizontalScrollOffset = if (wordWrap) 0f else horizontalScrollState.value.toFloat()
                    val contentX = if (visualCharOffsetX >= 0f) {
                        visualCharOffsetX
                    } else {
                        currentPositionColumn * charWidth
                    }
                    val xPosition =
                        lineNumberWidthPx + contentPaddingPx + contentX - horizontalScrollOffset - handleHalfWidth

                    // Use translation for GPU-accelerated positioning
                    translationX = xPosition
                    // The anchor is an item offset, the handle is placed over the whole list; the
                    // visual line offset then picks the wrapped line the caret sits on.
                    val anchorY = contentListState.layoutInfo.itemToContainerY(currentYPos)
                    translationY = anchorY + visualLineOffsetY
                }
                .pointerInput(lineNumberWidthPx, wordWrap, charWidth) {
                    detectDragGestures(
                        onDragStart = { currentOnDragStart() },
                        onDragEnd = { currentOnDragEnd() },
                        // A cancelled gesture ends just as much as a lifted finger: whatever the
                        // caller captured on start has to be released either way.
                        onDragCancel = { currentOnDragEnd() },
                    ) { change, _ ->
                        // Recompute the handle's anchor the same way the graphicsLayer block does
                        // so drag deltas convert back into content coordinates.
                        val horizontalScrollOffset = if (wordWrap) 0f else horizontalScrollState.value.toFloat()
                        val contentX = if (visualCharOffsetX >= 0f) {
                            visualCharOffsetX
                        } else {
                            currentPositionColumn * charWidth
                        }
                        val xPosition =
                            lineNumberWidthPx + contentPaddingPx + contentX - horizontalScrollOffset - handleHalfWidth
                        val currentYPosition = yPosition ?: 0f

                        // Convert handle-relative position to LazyColumn coordinates
                        val lazyColumnX = (change.position.x + xPosition) - lineNumberWidthPx + horizontalScrollOffset
                        val anchorY = contentListState.layoutInfo.itemToContainerY(currentYPosition)
                        val lazyColumnY = change.position.y + anchorY + visualLineOffsetY

                        currentOnDrag(Offset(lazyColumnX, lazyColumnY))
                        change.consume()
                    }
                }
        ) {
            // Draw the handle (circle on top of vertical line - lollipop style)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val circleRadius = 12.dp.toPx()
                val lineWidth = 3.dp.toPx()

                // Both handles point upward from the text for consistency
                // Line at top, circle at bottom
                drawLine(
                    color = handleColor,
                    start = Offset(size.width / 2, 0f),
                    end = Offset(size.width / 2, size.height - circleRadius),
                    strokeWidth = lineWidth
                )
                drawCircle(
                    color = handleColor,
                    radius = circleRadius / 2,
                    center = Offset(size.width / 2, size.height - circleRadius / 2)
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SelectionHandlePreview() {
    val textMeasurer = rememberTextMeasurer()
    val fontSize = 14
    val actualCharWidth = remember(fontSize) {
        val measured = textMeasurer.measure(
            text = "M",
            style = TextStyle(
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Monospace
            )
        )
        measured.size.width.toFloat()
    }

    val contentListState = rememberLazyListState()
    val lines = listOf(
        "fun calculateSum(a: Int, b: Int): Int {",
        "    return a + b",
        "}",
        "",
        "fun main() {",
        "    val result = calculateSum(5, 3)",
        "    println(\"Result: \$result\")",
        "}"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Background content (LazyColumn) so SelectionHandle can position itself
        LazyColumn(
            state = contentListState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(count = lines.size) { index ->
                Text(
                    text = lines[index].ifEmpty { " " },
                    style = TextStyle(
                        fontSize = fontSize.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Overlay selection handles on line 1, column 10
        SelectionHandle(
            position = TextPosition(offset = 0, line = 1, column = 10),
            contentListState = contentListState,
            lineNumberWidth = 0.dp,
            horizontalScrollState = rememberScrollState(),
            actualCharWidth = actualCharWidth,
            onDrag = {}
        )

        // End handle
        SelectionHandle(
            position = TextPosition(offset = 0, line = 1, column = 16),
            contentListState = contentListState,
            lineNumberWidth = 0.dp,
            horizontalScrollState = rememberScrollState(),
            actualCharWidth = actualCharWidth,
            onDrag = {}
        )
    }
}
