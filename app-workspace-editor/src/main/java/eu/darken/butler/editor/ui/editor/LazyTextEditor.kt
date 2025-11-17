package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.TextPosition
import kotlinx.coroutines.launch

private val tag = logTag("Editor", "LazyTextEditor")

@Composable
fun LazyTextEditor(
    content: String,
    totalLines: Int,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    visibleRange: IntRange,
    showLineNumbers: Boolean = true,
    wordWrap: Boolean = false,
    fontSize: Int = 14,
    tabSize: Int = 4,
    onTextChange: (String) -> Unit,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    onVisibleRangeChange: (IntRange) -> Unit,
    modifier: Modifier = Modifier
) {
    // Create a map of visible line content indexed by line number
    val visibleLineContent = remember(content, visibleRange) {
        if (content.isEmpty()) {
            mapOf(0 to "")
        } else {
            val contentLines = content.split('\n')
            contentLines.mapIndexed { index, line ->
                (visibleRange.first + index) to line
            }.toMap()
        }
    }
    val focusRequester = remember { FocusRequester() }
    val lineNumbersListState = rememberLazyListState()
    val contentListState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    
    // Update visible range when scroll position changes
    LaunchedEffect(contentListState.firstVisibleItemIndex, contentListState.layoutInfo.visibleItemsInfo.size) {
        if (totalLines > 0 && contentListState.layoutInfo.totalItemsCount > 0) {
            val startIndex = contentListState.firstVisibleItemIndex.coerceAtLeast(0)
            val visibleCount = contentListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val endIndex = minOf(
                startIndex + visibleCount + 10, // Buffer
                totalLines - 1
            ).coerceAtLeast(startIndex)

            onVisibleRangeChange(startIndex..endIndex)
        }
    }

    // Scroll to cursor position when it changes - only if the layout is ready
    // We check layoutInfo.totalItemsCount to ensure the layout has been measured
    LaunchedEffect(cursorPosition.line) {
        if (totalLines > 0 && cursorPosition.line >= 0 && contentListState.layoutInfo.totalItemsCount > 0) {
            val targetLine = cursorPosition.line.coerceIn(0, totalLines - 1)
            if (targetLine < contentListState.firstVisibleItemIndex ||
                targetLine >= contentListState.firstVisibleItemIndex + contentListState.layoutInfo.visibleItemsInfo.size) {
                try {
                    scope.launch {
                        contentListState.animateScrollToItem(targetLine)
                        lineNumbersListState.animateScrollToItem(targetLine)
                    }
                } catch (e: Exception) {
                    // Ignore scroll errors - layout might not be ready yet
                }
            }
        }
    }

    // Synchronized dual-column content
    DualColumnEditorContent(
        totalLines = totalLines,
        visibleLineContent = visibleLineContent,
        visibleRange = visibleRange,
        cursorPosition = cursorPosition,
        selection = selection,
        lineNumbersListState = lineNumbersListState,
        contentListState = contentListState,
        horizontalScrollState = horizontalScrollState,
        focusRequester = focusRequester,
        showLineNumbers = showLineNumbers,
        wordWrap = wordWrap,
        fontSize = fontSize,
        tabSize = tabSize,
        onTextChange = onTextChange,
        onCursorPositionChange = onCursorPositionChange,
        onSelectionChange = onSelectionChange,
        modifier = modifier
    )
}

@Preview2
@Composable
private fun LazyTextEditorPreview() {
    PreviewWrapper {
        val sampleContent = """
            fun calculateSum(a: Int, b: Int): Int {
                return a + b
            }

            fun main() {
                val result = calculateSum(5, 3)
                println("Result: ${'$'}result")
            }
        """.trimIndent()

        LazyTextEditor(
            content = sampleContent,
            totalLines = sampleContent.split('\n').size,
            cursorPosition = TextPosition(offset = 50, line = 1, column = 10),
            selection = null,
            visibleRange = 0..7,
            showLineNumbers = true,
            wordWrap = false,
            fontSize = 14,
            tabSize = 4,
            onTextChange = {},
            onCursorPositionChange = {},
            onSelectionChange = {},
            onVisibleRangeChange = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SelectionHandle(
    position: TextPosition,
    isStart: Boolean,
    fontSize: Int,
    visibleLineContent: Map<Int, String>,
    tabSize: Int,
    contentListState: LazyListState,
    lineNumberWidth: Dp,
    horizontalScrollState: ScrollState,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleColor = MaterialTheme.colorScheme.primary

    // Calculate position based on text position
    val lineInfo = contentListState.layoutInfo.visibleItemsInfo.find { it.index == position.line }

    if (lineInfo != null) {
        val lineContent = visibleLineContent[position.line] ?: ""
        val expandedContent = lineContent.expandTabs(tabSize)

        val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
        val contentPaddingPx = with(density) { 8.dp.toPx() }
        val lineNumberWidthPx = with(density) { lineNumberWidth.toPx() }
        val horizontalScrollOffset = horizontalScrollState.value.toFloat()
        val handleHalfWidth = with(density) { 12.dp.toPx() }  // Half of 24.dp handle width

        // Calculate X position based on column
        // Add lineNumberWidth because handles are in outer Box coordinate space
        // Subtract horizontal scroll offset to move with content
        // Subtract half handle width to center the visual handle on the text position
        val baseX = lineNumberWidthPx + contentPaddingPx + (position.column * charWidth) - horizontalScrollOffset
        val xPosition = baseX - handleHalfWidth

        // Calculate Y position based on line offset in the list
        val yPosition = lineInfo.offset.toFloat()
        val lineHeight = lineInfo.size.toFloat()

        // Position the handle
        Box(
            modifier = modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        x = xPosition.toInt(),
                        y = yPosition.toInt()  // Both handles at the same Y position (top of line)
                    )
                }
                .size(24.dp, 24.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        onDrag(change.position)
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

@Composable
private fun DualColumnEditorContent(
    totalLines: Int,
    visibleLineContent: Map<Int, String>,
    visibleRange: IntRange,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    lineNumbersListState: LazyListState,
    contentListState: LazyListState,
    horizontalScrollState: ScrollState,
    focusRequester: FocusRequester,
    showLineNumbers: Boolean,
    wordWrap: Boolean,
    fontSize: Int,
    tabSize: Int,
    onTextChange: (String) -> Unit,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    modifier: Modifier = Modifier
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var isFocused by remember { mutableStateOf(false) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf<Offset?>(null) }
    var tapCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Sync textFieldValue with visible content
    LaunchedEffect(visibleLineContent) {
        val currentContent = visibleLineContent.entries
            .sortedBy { it.key }
            .joinToString("\n") { it.value }
        if (textFieldValue.text != currentContent) {
            textFieldValue = TextFieldValue(
                text = currentContent,
                selection = TextRange(currentContent.length)
            )
        }
    }

    val lineNumberWidth = if (showLineNumbers) {
        remember(totalLines) {
            (totalLines.toString().length * 8 + 16).dp
        }
    } else {
        0.dp
    }

    // Synchronize vertical scrolling between line numbers and content
    LaunchedEffect(contentListState.firstVisibleItemIndex, contentListState.firstVisibleItemScrollOffset) {
        if (lineNumbersListState.firstVisibleItemIndex != contentListState.firstVisibleItemIndex ||
            lineNumbersListState.firstVisibleItemScrollOffset != contentListState.firstVisibleItemScrollOffset) {
            try {
                lineNumbersListState.scrollToItem(
                    contentListState.firstVisibleItemIndex,
                    contentListState.firstVisibleItemScrollOffset
                )
            } catch (e: Exception) {
                // Ignore sync errors
            }
        }
    }

    LaunchedEffect(lineNumbersListState.firstVisibleItemIndex, lineNumbersListState.firstVisibleItemScrollOffset) {
        if (contentListState.firstVisibleItemIndex != lineNumbersListState.firstVisibleItemIndex ||
            contentListState.firstVisibleItemScrollOffset != lineNumbersListState.firstVisibleItemScrollOffset) {
            try {
                contentListState.scrollToItem(
                    lineNumbersListState.firstVisibleItemIndex,
                    lineNumbersListState.firstVisibleItemScrollOffset
                )
            } catch (e: Exception) {
                // Ignore sync errors
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
    ) {
        // Hidden text field for keyboard input
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val oldText = textFieldValue.text
                val newText = newValue.text

                textFieldValue = newValue

                if (newText != oldText) {
                    if (newText.length > oldText.length && newText.startsWith(oldText)) {
                        val addedText = newText.substring(oldText.length)
                        onTextChange(addedText)
                    }
                }
            },
            modifier = Modifier
                .size(1.dp)
                .align(Alignment.TopStart)
                .onFocusChanged { focusState ->
                    isFocused = focusState.isFocused
                },
            textStyle = TextStyle(
                fontSize = 1.sp,
                color = Color.Transparent
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
            keyboardActions = KeyboardActions(),
            decorationBox = { _ -> }
        )
        
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            if (showLineNumbers) {
                LazyColumn(
                    state = lineNumbersListState,
                    modifier = Modifier
                        .width(lineNumberWidth)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clipToBounds()
                ) {
                    items(
                        count = totalLines,
                        key = { index -> "line_num_$index" }
                    ) { lineIndex ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Text(
                                text = (lineIndex + 1).toString(),
                                style = TextStyle(
                                    fontSize = fontSize.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Visible
                            )
                        }
                    }
                }
            }

            val contentModifier = if (wordWrap) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            }

            val focusBorderModifier = if (isFocused) {
                Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            } else {
                Modifier
            }

            LazyColumn(
                state = contentListState,
                modifier = contentModifier
                    .then(focusBorderModifier)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                // Request focus first
                                try {
                                    focusRequester.requestFocus()
                                } catch (e: Exception) {
                                    log(tag, WARN) { "Failed to request focus: ${e.message}" }
                                }

                                val currentTime = System.currentTimeMillis()
                                val timeSinceLastTap = currentTime - lastTapTime
                                val isSameLocation = lastTapPosition?.let { lastPos ->
                                    (offset - lastPos).getDistance() < 20f
                                } ?: false

                                if (timeSinceLastTap < 300 && isSameLocation) {
                                    tapCount++
                                } else {
                                    tapCount = 1
                                }

                                lastTapTime = currentTime
                                lastTapPosition = offset

                                val result = calculatePositionFromOffset(
                                    offset = offset,
                                    contentListState = contentListState,
                                    visibleLineContent = visibleLineContent,
                                    density = density,
                                    fontSize = fontSize,
                                    tabSize = tabSize
                                )

                                if (result != null) {
                                    when (tapCount) {
                                        1 -> {
                                            // Single tap: Place cursor and clear selection
                                            onCursorPositionChange(result.position)
                                            onSelectionChange(null)
                                        }
                                        2 -> {
                                            // Double tap: Select word
                                            val wordSelection = selectWordAt(
                                                result.lineIndex,
                                                result.column,
                                                visibleLineContent
                                            )
                                            onSelectionChange(wordSelection)
                                        }
                                        else -> {
                                            // Triple tap and beyond: Select line
                                            val lineSelection = selectLineAt(
                                                result.lineIndex,
                                                visibleLineContent
                                            )
                                            onSelectionChange(lineSelection)
                                            tapCount = 0 // Reset for next tap sequence
                                        }
                                    }
                                } else {
                                    log(tag, DEBUG) { "No line found at Y offset ${offset.y}" }
                                }
                            },
                            onLongPress = { offset ->
                                // Request focus
                                try {
                                    focusRequester.requestFocus()
                                } catch (e: Exception) {
                                    log(tag, WARN) { "Failed to request focus: ${e.message}" }
                                }

                                val result = calculatePositionFromOffset(
                                    offset = offset,
                                    contentListState = contentListState,
                                    visibleLineContent = visibleLineContent,
                                    density = density,
                                    fontSize = fontSize,
                                    tabSize = tabSize
                                )

                                if (result != null) {
                                    // Long press: Select word at cursor
                                    val wordSelection = selectWordAt(
                                        result.lineIndex,
                                        result.column,
                                        visibleLineContent
                                    )
                                    onSelectionChange(wordSelection)
                                }
                            }
                        )
                    }
            ) {
                items(
                    count = totalLines,
                    key = { index -> "line_content_$index" }
                ) { lineIndex ->
                    val lineContent = visibleLineContent[lineIndex] ?: ""
                    val isInVisibleRange = lineIndex in visibleRange

                    TextLineItem(
                        lineIndex = lineIndex,
                        lineContent = lineContent,
                        cursorPosition = cursorPosition,
                        selection = selection,
                        isCurrentLine = lineIndex == cursorPosition.line,
                        isFocused = isFocused,
                        wordWrap = wordWrap,
                        fontSize = fontSize,
                        tabSize = tabSize,
                        onLineClick = { clickPosition ->
                            val newPosition = TextPosition(
                                offset = calculateOffsetForLine(visibleLineContent, lineIndex, clickPosition),
                                line = lineIndex,
                                column = clickPosition
                            )
                            onCursorPositionChange(newPosition)
                        },
                        focusRequester = focusRequester,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Selection handles
        if (selection != null && isFocused) {
            val (start, end) = selection

            // Start handle
            SelectionHandle(
                position = start,
                isStart = true,
                fontSize = fontSize,
                visibleLineContent = visibleLineContent,
                tabSize = tabSize,
                contentListState = contentListState,
                lineNumberWidth = lineNumberWidth,
                horizontalScrollState = horizontalScrollState,
                onDrag = { offset ->
                    val result = calculatePositionFromOffset(
                        offset = offset,
                        contentListState = contentListState,
                        visibleLineContent = visibleLineContent,
                        density = density,
                        fontSize = fontSize,
                        tabSize = tabSize
                    )

                    if (result != null) {
                        // Update selection start, keep end fixed
                        val (newStart, newEnd) = if (result.position.line < end.line ||
                            (result.position.line == end.line && result.position.column < end.column)) {
                            result.position to end
                        } else {
                            end to result.position
                        }
                        onSelectionChange(newStart to newEnd)
                    }
                }
            )

            // End handle
            SelectionHandle(
                position = end,
                isStart = false,
                fontSize = fontSize,
                visibleLineContent = visibleLineContent,
                tabSize = tabSize,
                contentListState = contentListState,
                lineNumberWidth = lineNumberWidth,
                horizontalScrollState = horizontalScrollState,
                onDrag = { offset ->
                    val result = calculatePositionFromOffset(
                        offset = offset,
                        contentListState = contentListState,
                        visibleLineContent = visibleLineContent,
                        density = density,
                        fontSize = fontSize,
                        tabSize = tabSize
                    )

                    if (result != null) {
                        // Update selection end, keep start fixed
                        val (newStart, newEnd) = if (result.position.line > start.line ||
                            (result.position.line == start.line && result.position.column > start.column)) {
                            start to result.position
                        } else {
                            result.position to start
                        }
                        onSelectionChange(newStart to newEnd)
                    }
                }
            )
        }
    }

    // Request focus when content is loaded
    LaunchedEffect(totalLines) {
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
            // Ignore focus errors
        }
    }
}


@Composable
private fun TextLineItem(
    lineIndex: Int,
    lineContent: String,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    isCurrentLine: Boolean,
    isFocused: Boolean,
    wordWrap: Boolean,
    fontSize: Int,
    tabSize: Int,
    onLineClick: (Int) -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val cursorColor = MaterialTheme.colorScheme.primary
    val expandedText = remember(lineContent, tabSize) { lineContent.expandTabs(tabSize) }

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
                animation = tween(0),
                repeatMode = RepeatMode.Restart
            )
        },
        label = "cursor_alpha"
    )

    val backgroundColor = if (isCurrentLine) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    val cursorModifier = if (isCurrentLine && selection == null) {
        Modifier.drawWithContent {
            drawContent()
            val expandedText = lineContent.expandTabs(tabSize)
            val position = cursorPosition.column

            val layoutResult = textLayoutResult
            val cursorX = when {
                layoutResult != null && position < expandedText.length -> {
                    val boundingBox = layoutResult.getBoundingBox(position)
                    boundingBox.left
                }
                layoutResult != null && position == expandedText.length && expandedText.isNotEmpty() -> {
                    val boundingBox = layoutResult.getBoundingBox(expandedText.length - 1)
                    boundingBox.right
                }
                else -> {
                    val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                    position * charWidth
                }
            }

            if (isFocused) {
                drawLine(
                    color = cursorColor.copy(alpha = cursorAlpha),
                    start = Offset(cursorX, 0f),
                    end = Offset(cursorX, size.height),
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
                            val prevBox = layoutResultForWidth.getBoundingBox(position - 1)
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
                    topLeft = Offset(cursorX, 0f),
                    size = Size(blockWidth, size.height)
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
    ) {
        SelectableText(
            text = lineContent.expandTabs(tabSize).ifEmpty { " " },
            lineIndex = lineIndex,
            cursorPosition = cursorPosition,
            selection = selection,
            wordWrap = wordWrap,
            fontSize = fontSize,
            onTextLayout = { layoutResult ->
                textLayoutResult = layoutResult
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SelectableText(
    text: String,
    lineIndex: Int,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    wordWrap: Boolean,
    fontSize: Int,
    onTextLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurface
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(
            modifier = modifier
        ) {
            selection?.let { (start, end) ->
                if (lineIndex >= start.line && lineIndex <= end.line) {
                    val selectionStart = if (lineIndex == start.line) start.column else 0
                    val selectionEnd = if (lineIndex == end.line) end.column else text.length

                    if (selectionStart < selectionEnd) {
                        // Use TextLayoutResult for accurate positioning if available
                        val layout = layoutResult
                        val (startX, width) = if (layout != null && selectionStart < text.length && selectionEnd <= text.length) {
                            try {
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

                                val startXPos = startBounds.left
                                val endXPos = endBounds.right
                                startXPos to (endXPos - startXPos)
                            } catch (e: Exception) {
                                // Fallback to character width estimation
                                val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                                (selectionStart * charWidth) to ((selectionEnd - selectionStart) * charWidth)
                            }
                        } else {
                            // Fallback to character width estimation
                            val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                            (selectionStart * charWidth) to ((selectionEnd - selectionStart) * charWidth)
                        }

                        Box(
                            modifier = Modifier
                                .offset(x = with(density) { startX.toDp() })
                                .width(with(density) { width.toDp() })
                                .height(with(density) {
                                    val height: Float = layoutResult?.size?.height?.toFloat() ?: (fontSize * 1.5f).sp.toPx()
                                    height.toDp()
                                })
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        )
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
                modifier = Modifier.fillMaxWidth()
            )
    }
}

private fun String.expandTabs(tabSize: Int): String {
    return this.replace("\t", " ".repeat(tabSize))
}

private fun calculateOffsetForLine(visibleLineContent: Map<Int, String>, lineIndex: Int, column: Int): Long {
    // Note: With virtual scrolling, we can't accurately calculate offset without all lines
    // The engine should recalculate the correct offset based on line/column
    // For now, return 0 as a placeholder
    return 0L
}

private data class PositionCalculationResult(
    val lineIndex: Int,
    val column: Int,
    val position: TextPosition
)

private fun calculatePositionFromOffset(
    offset: Offset,
    contentListState: LazyListState,
    visibleLineContent: Map<Int, String>,
    density: androidx.compose.ui.unit.Density,
    fontSize: Int,
    tabSize: Int
): PositionCalculationResult? {
    val layoutInfo = contentListState.layoutInfo
    val clickedItem = layoutInfo.visibleItemsInfo.find { item ->
        offset.y >= item.offset && offset.y < (item.offset + item.size)
    }

    if (clickedItem == null) return null

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

    val position = TextPosition(
        offset = calculateOffsetForLine(visibleLineContent, lineIndex, clickedColumn),
        line = lineIndex,
        column = clickedColumn
    )

    return PositionCalculationResult(lineIndex, clickedColumn, position)
}

private fun findWordBoundaries(text: String, column: Int): Pair<Int, Int> {
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

private fun selectWordAt(
    lineIndex: Int,
    column: Int,
    visibleLineContent: Map<Int, String>
): Pair<TextPosition, TextPosition> {
    val lineContent = visibleLineContent[lineIndex] ?: ""
    val (start, end) = findWordBoundaries(lineContent, column)

    return TextPosition(
        offset = calculateOffsetForLine(visibleLineContent, lineIndex, start),
        line = lineIndex,
        column = start
    ) to TextPosition(
        offset = calculateOffsetForLine(visibleLineContent, lineIndex, end),
        line = lineIndex,
        column = end
    )
}

private fun selectLineAt(
    lineIndex: Int,
    visibleLineContent: Map<Int, String>
): Pair<TextPosition, TextPosition> {
    val lineContent = visibleLineContent[lineIndex] ?: ""

    return TextPosition(
        offset = calculateOffsetForLine(visibleLineContent, lineIndex, 0),
        line = lineIndex,
        column = 0
    ) to TextPosition(
        offset = calculateOffsetForLine(visibleLineContent, lineIndex, lineContent.length),
        line = lineIndex,
        column = lineContent.length
    )
}