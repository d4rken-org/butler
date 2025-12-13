package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
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
    modifier: Modifier = Modifier,
    wordWrap: Boolean = false,
    textLayouts: Map<Int, TextLayoutResult> = emptyMap(),
    visibleLineContent: Map<Int, String> = emptyMap(),
) {
    val density = LocalDensity.current
    val handleColor = MaterialTheme.colorScheme.primary

    // Pre-calculate constant values that don't change during composition
    val charWidth = actualCharWidth
    val contentPaddingPx = with(density) { 8.dp.toPx() }
    val lineNumberWidthPx = with(density) { lineNumberWidth.toPx() }
    val handleHalfWidth = with(density) { 12.dp.toPx() }  // Half of 24.dp handle width

    // Use rememberUpdatedState to get current position without restarting gesture
    val currentPositionColumn by rememberUpdatedState(position.column)
    val currentPositionLine by rememberUpdatedState(position.line)

    // Use simple state to store Y position, updated via LaunchedEffect observing layout
    var yPosition by remember { mutableStateOf<Float?>(null) }
    var visualLineOffsetY by remember { mutableStateOf(0f) }
    var visualCharOffsetX by remember { mutableStateOf(-1f) }  // -1 = use column * charWidth

    // Observe layout changes and update Y position
    // This isolates layout reading from composition/offset lambda
    LaunchedEffect(position.line) {
        snapshotFlow {
            contentListState.layoutInfo.visibleItemsInfo.find { it.index == position.line }?.offset
        }.collect { offset ->
            yPosition = offset?.toFloat()
        }
    }

    // Calculate visual line offset and X position when word wrap is enabled
    LaunchedEffect(position.line, position.column, wordWrap, textLayouts) {
        if (wordWrap && textLayouts.containsKey(position.line)) {
            val layout = textLayouts[position.line]!!
            val textLength = visibleLineContent[position.line]?.length ?: 0
            if (textLength > 0) {
                val clampedColumn = position.column.coerceIn(0, textLength - 1)
                val visualLine = layout.getLineForOffset(clampedColumn)
                visualLineOffsetY = layout.getLineTop(visualLine)

                // Calculate X position from TextLayoutResult
                val columnForX = position.column.coerceIn(0, textLength)
                visualCharOffsetX = if (columnForX < textLength) {
                    layout.getBoundingBox(columnForX).left
                } else {
                    layout.getBoundingBox(textLength - 1).right
                }
            } else {
                visualLineOffsetY = 0f
                visualCharOffsetX = 0f
            }
        } else {
            visualLineOffsetY = 0f
            visualCharOffsetX = -1f  // Use column * charWidth fallback
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

                    // Calculate X position
                    val baseX = if (visualCharOffsetX >= 0f) {
                        // Word wrap: use calculated position from TextLayoutResult (no scroll when wrapped)
                        lineNumberWidthPx + contentPaddingPx + visualCharOffsetX
                    } else {
                        // No wrap: use column * charWidth with scroll offset
                        val horizontalScrollOffset = horizontalScrollState.value.toFloat()
                        lineNumberWidthPx + contentPaddingPx + (currentPositionColumn * charWidth) - horizontalScrollOffset
                    }
                    val xPosition = baseX - handleHalfWidth

                    // Use translation for GPU-accelerated positioning
                    translationX = xPosition
                    // Add visual line offset for wrapped text positioning
                    translationY = currentYPos + visualLineOffsetY
                }
                .pointerInput(lineNumberWidthPx) {
                    detectDragGestures { change, _ ->
                        // Calculate current handle position for drag conversion
                        val currentCharOffsetX = visualCharOffsetX
                        val baseX = if (currentCharOffsetX >= 0f) {
                            // Word wrap: use calculated position from TextLayoutResult
                            lineNumberWidthPx + contentPaddingPx + currentCharOffsetX
                        } else {
                            // No wrap: use column * charWidth with scroll offset
                            val horizontalScrollOffset = horizontalScrollState.value.toFloat()
                            lineNumberWidthPx + contentPaddingPx + (currentPositionColumn * charWidth) - horizontalScrollOffset
                        }
                        val xPosition = baseX - handleHalfWidth
                        val currentYPosition = yPosition ?: 0f

                        // Convert handle-relative position to LazyColumn coordinates
                        val horizontalScrollOffset = if (currentCharOffsetX >= 0f) 0f else horizontalScrollState.value.toFloat()
                        val lazyColumnX = (change.position.x + xPosition) - lineNumberWidthPx + horizontalScrollOffset
                        val lazyColumnY = change.position.y + currentYPosition + visualLineOffsetY

                        onDrag(Offset(lazyColumnX, lazyColumnY))
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
@Composable
private fun SelectionHandlePreview() {
    PreviewWrapper {
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
}
