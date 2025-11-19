package eu.darken.butler.editor.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.TextPosition
import kotlinx.coroutines.launch

private val tag = logTag("Editor", "LazyTextEditor")

@Composable
fun LazyTextEditor(
    modifier: Modifier = Modifier,
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

    // Measure actual character width for accurate positioning
    val textMeasurer = rememberTextMeasurer()
    val actualCharWidth = remember(fontSize) {
        val measured = textMeasurer.measure(
            text = "M",  // Measure a typical monospace character
            style = TextStyle(
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Monospace
            )
        )
        measured.size.width.toFloat()
    }

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
                                                result.position.line,
                                                result.position.column,
                                                visibleLineContent
                                            )
                                            onSelectionChange(wordSelection)
                                        }
                                        else -> {
                                            // Triple tap and beyond: Select line
                                            val lineSelection = selectLineAt(
                                                result.position.line,
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
                                        result.position.line,
                                        result.position.column,
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
                contentListState = contentListState,
                lineNumberWidth = lineNumberWidth,
                horizontalScrollState = horizontalScrollState,
                actualCharWidth = actualCharWidth,
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
                contentListState = contentListState,
                lineNumberWidth = lineNumberWidth,
                horizontalScrollState = horizontalScrollState,
                actualCharWidth = actualCharWidth,
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
