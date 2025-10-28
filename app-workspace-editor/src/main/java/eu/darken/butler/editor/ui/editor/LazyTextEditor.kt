package eu.darken.butler.editor.ui.editor

import android.graphics.Paint
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.TextPosition
import kotlinx.coroutines.launch

@Composable
fun LazyTextEditor(
    content: String,
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
    val lines = remember(content) { 
        if (content.isEmpty()) listOf("") else content.split('\n')
    }
    val focusRequester = remember { FocusRequester() }
    val lineNumbersListState = rememberLazyListState()
    val contentListState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    
    // Update visible range when scroll position changes
    LaunchedEffect(contentListState.firstVisibleItemIndex, contentListState.layoutInfo.visibleItemsInfo.size, lines.size) {
        if (lines.isNotEmpty() && contentListState.layoutInfo.totalItemsCount > 0) {
            val startIndex = contentListState.firstVisibleItemIndex.coerceAtLeast(0)
            val visibleCount = contentListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val endIndex = minOf(
                startIndex + visibleCount + 10, // Buffer
                lines.size - 1
            ).coerceAtLeast(startIndex)
            
            onVisibleRangeChange(startIndex..endIndex)
        }
    }

    // Scroll to cursor position when it changes - only if the layout is ready
    // We check layoutInfo.totalItemsCount to ensure the layout has been measured
    LaunchedEffect(cursorPosition.line) {
        if (lines.isNotEmpty() && cursorPosition.line >= 0 && contentListState.layoutInfo.totalItemsCount > 0) {
            val targetLine = cursorPosition.line.coerceIn(0, lines.size - 1)
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
        lines = lines,
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
    lines: List<String>,
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
    val scope = rememberCoroutineScope()
    
    // Sync textFieldValue with content
    LaunchedEffect(lines) {
        val currentContent = lines.joinToString("\n")
        if (textFieldValue.text != currentContent) {
            textFieldValue = TextFieldValue(
                text = currentContent,
                selection = TextRange(currentContent.length)
            )
        }
    }

    // Calculate line number width
    val lineNumberWidth = if (showLineNumbers) {
        remember(lines.size) {
            (lines.size.toString().length * 8 + 16).dp
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
                .align(Alignment.TopStart),
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
                    itemsIndexed(
                        items = lines,
                        key = { index, _ -> "line_num_$index" }
                    ) { lineIndex, _ ->
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
                                )
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
            
            LazyColumn(
                state = contentListState,
                modifier = contentModifier
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            try {
                                focusRequester.requestFocus()
                            } catch (e: Exception) {
                                // Ignore focus errors
                            }
                        }
                    }
            ) {
                itemsIndexed(
                    items = lines,
                    key = { index, _ -> "line_content_$index" }
                ) { lineIndex, lineContent ->
                    TextLineItem(
                        lineIndex = lineIndex,
                        lineContent = lineContent,
                        cursorPosition = cursorPosition,
                        selection = selection,
                        isCurrentLine = lineIndex == cursorPosition.line,
                        wordWrap = wordWrap,
                        fontSize = fontSize,
                        tabSize = tabSize,
                        onLineClick = { clickPosition ->
                            val newPosition = TextPosition(
                                offset = calculateOffsetForLine(lines, lineIndex, clickPosition),
                                line = lineIndex,
                                column = clickPosition
                            )
                            onCursorPositionChange(newPosition)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // Request focus when content is loaded
    LaunchedEffect(lines.size) {
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
    wordWrap: Boolean,
    fontSize: Int,
    tabSize: Int,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    
    val backgroundColor = if (isCurrentLine) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        // Text content with selection highlighting
        SelectableText(
            text = lineContent.expandTabs(tabSize).ifEmpty { " " }, // Ensure empty lines have height
            lineIndex = lineIndex,
            cursorPosition = cursorPosition,
            selection = selection,
            wordWrap = wordWrap,
            fontSize = fontSize,
            onTextClick = onLineClick,
            modifier = Modifier.fillMaxWidth()
        )

        // Cursor indicator
        if (isCurrentLine) {
            CursorIndicator(
                position = cursorPosition.column,
                text = lineContent.expandTabs(tabSize),
                fontSize = fontSize
            )
        }
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
    onTextClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val useCanvasRendering = false // Switch to compose text rendering for now
    
    if (useCanvasRendering) {
        Canvas(
            modifier = modifier
                .height(with(density) { (fontSize + 4).sp.toDp() })
                .pointerInput(lineIndex) {
                    detectTapGestures { offset ->
                        // Calculate character position from click
                        val charWidth = fontSize * density.density * 0.6f // Approximate monospace char width
                        val clickedColumn = (offset.x / charWidth).toInt().coerceIn(0, text.length)
                        onTextClick(clickedColumn)
                    }
                }
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
                .pointerInput(lineIndex) {
                    detectTapGestures { offset ->
                        // For wrapped text, we can't easily calculate exact character position
                        // So we'll approximate based on x position only
                        val charWidth = fontSize * density.density * 0.6f // Approximate monospace char width
                        val clickedColumn = (offset.x / charWidth).toInt().coerceIn(0, text.length)
                        onTextClick(clickedColumn)
                    }
                }
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
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CursorIndicator(
    position: Int,
    text: String,
    fontSize: Int,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
    val cursorX = position * charWidth

    Canvas(modifier = modifier.fillMaxSize()) {
        drawLine(
            color = Color.Black,
            start = Offset(cursorX, 0f),
            end = Offset(cursorX, size.height),
            strokeWidth = 2.dp.toPx()
        )
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

private fun calculateOffsetForLine(lines: List<String>, lineIndex: Int, column: Int): Long {
    var offset = 0L
    for (i in 0 until lineIndex) {
        offset += lines[i].length + 1 // +1 for newline
    }
    offset += column.coerceIn(0, lines.getOrNull(lineIndex)?.length ?: 0)
    return offset
}