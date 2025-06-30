package eu.darken.butler.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.editor.core.TextPosition
import kotlinx.coroutines.launch

@Composable
fun LazyTextEditor(
    content: String,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    visibleRange: IntRange,
    showLineNumbers: Boolean = true,
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Update visible range when scroll position changes
    LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.size, lines.size) {
        if (lines.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
            val startIndex = listState.firstVisibleItemIndex.coerceAtLeast(0)
            val visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
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
        if (lines.isNotEmpty() && cursorPosition.line >= 0 && listState.layoutInfo.totalItemsCount > 0) {
            val targetLine = cursorPosition.line.coerceIn(0, lines.size - 1)
            if (targetLine < listState.firstVisibleItemIndex || 
                targetLine >= listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size) {
                try {
                    listState.animateScrollToItem(targetLine)
                } catch (e: Exception) {
                    // Ignore scroll errors - layout might not be ready yet
                }
            }
        }
    }

    // Combined content with line numbers and text
    CombinedEditorContent(
        lines = lines,
        cursorPosition = cursorPosition,
        selection = selection,
        listState = listState,
        focusRequester = focusRequester,
        showLineNumbers = showLineNumbers,
        fontSize = fontSize,
        tabSize = tabSize,
        onTextChange = onTextChange,
        onCursorPositionChange = onCursorPositionChange,
        onSelectionChange = onSelectionChange,
        modifier = modifier
    )
}

@Composable
private fun CombinedEditorContent(
    lines: List<String>,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    listState: LazyListState,
    focusRequester: FocusRequester,
    showLineNumbers: Boolean,
    fontSize: Int,
    tabSize: Int,
    onTextChange: (String) -> Unit,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    modifier: Modifier = Modifier
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    
    // Sync textFieldValue with content
    LaunchedEffect(lines) {
        val currentContent = lines.joinToString("\n")
        if (textFieldValue.text != currentContent) {
            textFieldValue = TextFieldValue(
                text = currentContent,
                selection = androidx.compose.ui.text.TextRange(currentContent.length)
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
        
        // LazyColumn with both line numbers and text
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
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
                key = { index, _ -> "line_$index" }
            ) { lineIndex, lineContent ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    // Line number
                    if (showLineNumbers) {
                        Box(
                            modifier = Modifier
                                .width(lineNumberWidth)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
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
                    
                    // Text content
                    TextLineItem(
                        lineIndex = lineIndex,
                        lineContent = lineContent,
                        cursorPosition = cursorPosition,
                        selection = selection,
                        isCurrentLine = lineIndex == cursorPosition.line,
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
                        modifier = Modifier.weight(1f)
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
                selectionColor = androidx.compose.ui.graphics.Color.Blue.copy(alpha = 0.3f)
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
            
            // Text content
            Text(
                text = if (text.isEmpty()) " " else text, // Show at least a space for empty lines
                style = TextStyle(
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                    color = textColor
                ),
                softWrap = true,
                overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
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
            color = androidx.compose.ui.graphics.Color.Black,
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
    normalColor: androidx.compose.ui.graphics.Color,
    selectionColor: androidx.compose.ui.graphics.Color
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
                    size = androidx.compose.ui.geometry.Size(endX - startX, size.height)
                )
            }
        }
    }

    // Draw text using native canvas
    val paint = android.graphics.Paint().apply {
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