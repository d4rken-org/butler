package eu.darken.butler.editor.ui.editor

import android.graphics.Paint
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
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

    // Calculate line number width
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
            // Line numbers column
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
            
            // Content column with horizontal scrolling when not wrapping
            val contentModifier = if (wordWrap) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            }

            // Add focus border when editor is focused
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
                        detectTapGestures { offset ->
                            log(tag, INFO) { "LazyColumn click detected at offset: $offset" }

                            // Request focus first
                            try {
                                focusRequester.requestFocus()
                            } catch (e: Exception) {
                                log(tag, WARN) { "Failed to request focus: ${e.message}" }
                            }

                            // Find which line was clicked based on Y coordinate
                            val layoutInfo = contentListState.layoutInfo
                            val clickedItem = layoutInfo.visibleItemsInfo.find { item ->
                                offset.y >= item.offset && offset.y < (item.offset + item.size)
                            }

                            if (clickedItem != null) {
                                val lineIndex = clickedItem.index
                                log(tag, INFO) { "Clicked on line $lineIndex at Y offset ${offset.y}" }

                                // Calculate column based on X position
                                // offset.x is already relative to the content LazyColumn,
                                // so line number width is already excluded from the coordinate system
                                val contentPaddingPx = with(density) { 8.dp.toPx() }
                                val adjustedX = offset.x - contentPaddingPx

                                // Use consistent character width calculation (same as cursor drawing)
                                val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }

                                // Get the line content to calculate max column
                                val lineContent = visibleLineContent[lineIndex] ?: ""
                                val expandedContent = lineContent.expandTabs(tabSize)

                                val clickedColumn = if (adjustedX < 0) {
                                    0 // Clicked in padding
                                } else {
                                    // Calculate column position
                                    val calculatedColumn = (adjustedX / charWidth).toInt()
                                    calculatedColumn.coerceIn(0, expandedContent.length)
                                }

                                log(tag, INFO) { "Calculated column $clickedColumn for line $lineIndex (adjustedX: $adjustedX, lineLength: ${expandedContent.length})" }

                                // Create cursor position and notify
                                val newPosition = TextPosition(
                                    offset = calculateOffsetForLine(visibleLineContent, lineIndex, clickedColumn),
                                    line = lineIndex,
                                    column = clickedColumn
                                )
                                onCursorPositionChange(newPosition)
                            } else {
                                log(tag, WARN) { "No line found at Y offset ${offset.y}" }
                            }
                        }
                    }
            ) {
                items(
                    count = totalLines,
                    key = { index -> "line_content_$index" }
                ) { lineIndex ->
                    // Get line content from map, or show loading placeholder if not available
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
                            log(tag, INFO) { "onLineClick called - Line: $lineIndex, Column: $clickPosition" }
                            val newPosition = TextPosition(
                                offset = calculateOffsetForLine(visibleLineContent, lineIndex, clickPosition),
                                line = lineIndex,
                                column = clickPosition
                            )
                            log(tag, INFO) { "Calling onCursorPositionChange with position: $newPosition" }
                            onCursorPositionChange(newPosition)
                        },
                        focusRequester = focusRequester,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
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

    // Draw cursor using drawWithContent to ensure it's on top
    val cursorModifier = if (isCurrentLine && selection == null) {
        Modifier.drawWithContent {
            // Draw content first (text and background)
            drawContent()

            // Calculate cursor position
            val expandedText = lineContent.expandTabs(tabSize)
            val position = cursorPosition.column

            val layoutResult = textLayoutResult
            val cursorX = when {
                layoutResult != null && position < expandedText.length -> {
                    // Position is within text bounds, get exact position
                    val boundingBox = layoutResult.getBoundingBox(position)
                    boundingBox.left
                }
                layoutResult != null && position == expandedText.length && expandedText.isNotEmpty() -> {
                    // Position is at end of non-empty line, get right edge of last character
                    val boundingBox = layoutResult.getBoundingBox(expandedText.length - 1)
                    boundingBox.right
                }
                else -> {
                    // Fallback: calculate based on character width
                    val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                    position * charWidth
                }
            }

            // Draw cursor on top
            if (isFocused) {
                // Focused: Blinking line cursor
                drawLine(
                    color = cursorColor.copy(alpha = cursorAlpha),
                    start = Offset(cursorX, 0f),
                    end = Offset(cursorX, size.height),
                    strokeWidth = 3.dp.toPx()
                )
            } else {
                // Unfocused: Block cursor
                val layoutResultForWidth = textLayoutResult
                val charWidth = when {
                    layoutResultForWidth != null && position < expandedText.length - 1 -> {
                        // Get width from current to next character
                        val currentBox = layoutResultForWidth.getBoundingBox(position)
                        val nextBox = layoutResultForWidth.getBoundingBox(position + 1)
                        (nextBox.left - currentBox.left).coerceAtLeast(0f)
                    }
                    layoutResultForWidth != null && position == expandedText.length - 1 && expandedText.isNotEmpty() -> {
                        // Last character: get its width
                        val box = layoutResultForWidth.getBoundingBox(position)
                        if (position > 0) {
                            val prevBox = layoutResultForWidth.getBoundingBox(position - 1)
                            (box.right - box.left).coerceAtLeast(0f)
                        } else {
                            with(density) { (fontSize * 0.6f).sp.toPx() }
                        }
                    }
                    else -> {
                        // Fallback or end of line
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
        // Text content with selection highlighting
        SelectableText(
            text = lineContent.expandTabs(tabSize).ifEmpty { " " }, // Ensure empty lines have height
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
    val useCanvasRendering = false // Switch to compose text rendering for now
    
    if (useCanvasRendering) {
        Canvas(
            modifier = modifier
                .height(with(density) { (fontSize + 4).sp.toDp() })
        ) {
            drawTextLine(
                text = text,
                lineIndex = lineIndex,
                selection = selection,
                fontSize = fontSize.sp.toPx(),
                normalColor = textColor,
                selectionColor = Color.Blue.copy(alpha = 0.3f)
            )
        }
    } else {
        // Use Compose Text rendering (more reliable)
        Box(
            modifier = modifier
        ) {
            // Selection background
            selection?.let { (start, end) ->
                if (lineIndex >= start.line && lineIndex <= end.line) {
                    val selectionStart = if (lineIndex == start.line) start.column else 0
                    val selectionEnd = if (lineIndex == end.line) end.column else text.length
                    
                    if (selectionStart < selectionEnd) {
                        val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                        val startX = selectionStart * charWidth
                        val width = (selectionEnd - selectionStart) * charWidth
                        
                        Box(
                            modifier = Modifier
                                .offset(x = with(density) { startX.toDp() })
                                .width(with(density) { width.toDp() })
                                .fillMaxHeight()
                                .background(Color.Blue.copy(alpha = 0.3f))
                        )
                    }
                }
            }
            
            Text(
                text = if (text.isEmpty()) " " else text, // Show at least a space for empty lines
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
}

private fun DrawScope.drawTextLine(
    text: String,
    lineIndex: Int,
    selection: Pair<TextPosition, TextPosition>?,
    fontSize: Float,
    normalColor: Color,
    selectionColor: Color
) {
    // Draw selection background if this line is selected
    selection?.let { (start, end) ->
        if (lineIndex >= start.line && lineIndex <= end.line) {
            val selectionStart = if (lineIndex == start.line) start.column else 0
            val selectionEnd = if (lineIndex == end.line) end.column else text.length
            
            if (selectionStart < selectionEnd) {
                val charWidth = fontSize * 0.6f
                val startX = selectionStart * charWidth
                val endX = selectionEnd * charWidth
                
                drawRect(
                    color = selectionColor,
                    topLeft = Offset(startX, 0f),
                    size = Size(endX - startX, size.height)
                )
            }
        }
    }

    // Draw text using native canvas
    val paint = Paint().apply {
        color = normalColor.toArgb()
        textSize = fontSize
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }
    
    drawContext.canvas.nativeCanvas.drawText(
        text,
        0f,
        fontSize * 0.8f, // Baseline offset
        paint
    )
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