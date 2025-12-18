package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.foundation.rememberScrollState
import eu.darken.butler.common.ui.propagateScrollAtBoundary
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    onTextDelete: (Int) -> Unit,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    onVisibleRangeChange: (IntRange) -> Unit,
    onCursorMove: (CursorDirection, Boolean) -> Unit,
    onForwardDelete: () -> Unit,
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
    val focusManager = LocalFocusManager.current
    val isWorkspaceFocused = LocalWorkspaceFocused.current
    val lineNumbersListState = rememberLazyListState()
    val contentListState = rememberLazyListState()

    // Release focus when workspace loses focus (multi-pane adaptive layout support)
    // Use freeFocus() instead of clearFocus() to only release this component's focus,
    // not clear focus globally (which would break focus transfer to other workspaces)
    LaunchedEffect(isWorkspaceFocused) {
        if (!isWorkspaceFocused) {
            try { focusRequester.freeFocus() } catch (_: Exception) {}
        }
    }
    val horizontalScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Measure character width for horizontal scroll calculations
    val textMeasurer = rememberTextMeasurer()
    val charWidth = remember(fontSize) {
        val measured = textMeasurer.measure(
            text = "M",
            style = TextStyle(
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Monospace
            )
        )
        measured.size.width.toFloat()
    }
    
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

    // Scroll to cursor position when it changes (line, column, or offset)
    // Triggers on any cursor change to ensure cursor is always visible during typing
    LaunchedEffect(cursorPosition) {
        if (totalLines <= 0 || contentListState.layoutInfo.totalItemsCount <= 0) return@LaunchedEffect

        val targetLine = cursorPosition.line.coerceIn(0, totalLines - 1)

        // VERTICAL: Check if cursor line is outside visible viewport
        val firstVisibleLine = contentListState.firstVisibleItemIndex
        val visibleCount = contentListState.layoutInfo.visibleItemsInfo.size
        val lastVisibleLine = firstVisibleLine + visibleCount - 1

        val needsVerticalScroll = targetLine < firstVisibleLine || targetLine > lastVisibleLine

        if (needsVerticalScroll) {
            try {
                // Use instant scroll for responsiveness during typing
                contentListState.scrollToItem(targetLine)
                lineNumbersListState.scrollToItem(targetLine)
            } catch (e: Exception) {
                // Ignore scroll errors - layout might not be ready yet
            }
        }

        // HORIZONTAL: Only when word wrap is disabled
        if (!wordWrap) {
            val viewportWidth = contentListState.layoutInfo.viewportSize.width.toFloat()
            if (viewportWidth <= 0) return@LaunchedEffect

            val contentPadding = with(density) { 8.dp.toPx() } // Match TextLineItem padding
            val margin = charWidth * 3 // 3 character margin from edge

            // Cursor X position
            val cursorX = contentPadding + (cursorPosition.column * charWidth)

            val currentScrollX = horizontalScrollState.value.toFloat()
            val visibleLeft = currentScrollX
            val visibleRight = currentScrollX + viewportWidth

            try {
                when {
                    cursorX < visibleLeft + margin -> {
                        // Cursor left of viewport - scroll left
                        val targetScroll = (cursorX - margin).coerceAtLeast(0f)
                        horizontalScrollState.scrollTo(targetScroll.toInt())
                    }
                    cursorX > visibleRight - margin -> {
                        // Cursor right of viewport - scroll right
                        val targetScroll = (cursorX - viewportWidth + margin).coerceAtLeast(0f)
                        horizontalScrollState.scrollTo(targetScroll.toInt())
                    }
                }
            } catch (e: Exception) {
                // Ignore scroll errors
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
        onTextDelete = onTextDelete,
        onCursorPositionChange = onCursorPositionChange,
        onSelectionChange = onSelectionChange,
        onCursorMove = onCursorMove,
        onForwardDelete = onForwardDelete,
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
    onTextDelete: (Int) -> Unit,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    onCursorMove: (CursorDirection, Boolean) -> Unit,
    onForwardDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Ensure drag handlers always see latest values, not captured closures
    val currentVisibleLineContent by rememberUpdatedState(visibleLineContent)
    val currentVisibleRange by rememberUpdatedState(visibleRange)
    val isWorkspaceFocused = LocalWorkspaceFocused.current
    val requestWorkspaceFocus = LocalWorkspaceFocusRequest.current
    val focusManager = LocalFocusManager.current

    // Release focus when workspace loses focus (multi-pane adaptive layout support)
    // Use freeFocus() instead of clearFocus() to only release this component's focus,
    // not clear focus globally (which would break focus transfer to other workspaces)
    LaunchedEffect(isWorkspaceFocused) {
        if (!isWorkspaceFocused) {
            try { focusRequester.freeFocus() } catch (_: Exception) {}
        }
    }

    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var isFocused by remember { mutableStateOf(false) }
    var isUserEditing by remember { mutableStateOf(false) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf<Offset?>(null) }
    var tapCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Track measured heights for each line when word wrap is enabled
    val lineHeights = remember { mutableStateMapOf<Int, Int>() }

    // Track TextLayoutResults for accurate tap position calculation when word wrap is enabled
    val textLayouts = remember { mutableStateMapOf<Int, TextLayoutResult>() }

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

    // Sync textFieldValue with visible content (skip during user edits)
    LaunchedEffect(visibleLineContent) {
        if (isUserEditing) {
            isUserEditing = false
            return@LaunchedEffect // Skip sync - TextField already has correct content from user input
        }
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
                    // Mark as user edit to skip TextField sync when content updates
                    isUserEditing = true
                    if (newText.length > oldText.length && newText.startsWith(oldText)) {
                        // Insertion: text was added at the end
                        val addedText = newText.substring(oldText.length)
                        onTextChange(addedText)
                    } else if (newText.length < oldText.length) {
                        // Deletion: characters were removed (backspace/delete)
                        val deletedCount = oldText.length - newText.length
                        onTextDelete(deletedCount)
                    }
                }
            },
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    // Use onPreviewKeyEvent to intercept BEFORE focus traversal consumes arrow keys
                    if (event.type == KeyEventType.KeyDown) {
                        val handled = when (event.key) {
                            // Ctrl+Arrow must be checked before plain Arrow
                            Key.DirectionLeft -> if (event.isCtrlPressed) {
                                log(tag) { "Key: Ctrl+Left -> WORD_LEFT" }
                                onCursorMove(CursorDirection.WORD_LEFT, event.isShiftPressed); true
                            } else {
                                log(tag) { "Key: Left -> LEFT" }
                                onCursorMove(CursorDirection.LEFT, event.isShiftPressed); true
                            }
                            Key.DirectionRight -> if (event.isCtrlPressed) {
                                log(tag) { "Key: Ctrl+Right -> WORD_RIGHT" }
                                onCursorMove(CursorDirection.WORD_RIGHT, event.isShiftPressed); true
                            } else {
                                log(tag) { "Key: Right -> RIGHT" }
                                onCursorMove(CursorDirection.RIGHT, event.isShiftPressed); true
                            }
                            Key.DirectionUp -> {
                                // At top line without shift - move focus to toolbar
                                if (cursorPosition.line == 0 && !event.isShiftPressed) {
                                    log(tag) { "Key: Up at line 0 -> move focus up" }
                                    focusManager.moveFocus(FocusDirection.Up)
                                    true
                                } else {
                                    log(tag) { "Key: Up -> UP" }
                                    onCursorMove(CursorDirection.UP, event.isShiftPressed)
                                    true
                                }
                            }
                            Key.DirectionDown -> {
                                // At last line without shift - move focus down
                                if (cursorPosition.line >= totalLines - 1 && !event.isShiftPressed) {
                                    log(tag) { "Key: Down at last line -> move focus down" }
                                    focusManager.moveFocus(FocusDirection.Down)
                                    true
                                } else {
                                    log(tag) { "Key: Down -> DOWN" }
                                    onCursorMove(CursorDirection.DOWN, event.isShiftPressed)
                                    true
                                }
                            }
                            // Forward delete
                            Key.Delete -> {
                                log(tag) { "Key: Delete -> ForwardDelete" }
                                onForwardDelete(); true
                            }
                            // Home/End
                            Key.MoveHome -> {
                                log(tag) { "Key: Home -> LINE_START" }
                                onCursorMove(CursorDirection.LINE_START, event.isShiftPressed); true
                            }
                            Key.MoveEnd -> {
                                log(tag) { "Key: End -> LINE_END" }
                                onCursorMove(CursorDirection.LINE_END, event.isShiftPressed); true
                            }
                            // Tab - let it propagate for focus navigation
                            Key.Tab -> {
                                log(tag) { "Key: Tab -> not handled (propagate)" }
                                false
                            }
                            else -> false
                        }
                        handled
                    } else false
                }
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
                    contentPadding = PaddingValues(bottom = 52.dp),
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
                        val measuredHeight = if (wordWrap) lineHeights[lineIndex] else null

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (measuredHeight != null) {
                                        Modifier.height(with(density) { measuredHeight.toDp() })
                                    } else {
                                        Modifier
                                    }
                                )
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
                    .propagateScrollAtBoundary(horizontalScrollState)
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
                contentPadding = PaddingValues(bottom = 52.dp),
                modifier = contentModifier
                    .then(focusBorderModifier)
                    .pointerInput(isWorkspaceFocused, requestWorkspaceFocus) {
                        detectTapGestures(
                            onTap = { offset ->
                                // Request workspace focus so this pane becomes active
                                requestWorkspaceFocus?.invoke()

                                // Request focus first (only if workspace is focused)
                                if (isWorkspaceFocused) {
                                    try {
                                        focusRequester.requestFocus()
                                    } catch (e: Exception) {
                                        log(tag, WARN) { "Failed to request focus: ${e.message}" }
                                    }
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
                                    visibleLineContent = currentVisibleLineContent,
                                    density = density,
                                    fontSize = fontSize,
                                    tabSize = tabSize,
                                    wordWrap = wordWrap,
                                    textLayouts = textLayouts,
                                )

                                if (result != null) {
                                    when (tapCount) {
                                        1 -> {
                                            // Single tap: Place cursor
                                            // Selection is cleared automatically by setCursorPosition in the engine
                                            onCursorPositionChange(result.position)
                                        }
                                        2 -> {
                                            // Double tap: Select word
                                            val wordSelection = selectWordAt(
                                                result.position.line,
                                                result.position.column,
                                                currentVisibleLineContent
                                            )
                                            onSelectionChange(wordSelection)
                                        }
                                        else -> {
                                            // Triple tap and beyond: Select line
                                            val lineSelection = selectLineAt(
                                                result.position.line,
                                                currentVisibleLineContent
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
                                // Request workspace focus so this pane becomes active
                                requestWorkspaceFocus?.invoke()

                                // Request focus (only if workspace is focused)
                                if (isWorkspaceFocused) {
                                    try {
                                        focusRequester.requestFocus()
                                    } catch (e: Exception) {
                                        log(tag, WARN) { "Failed to request focus: ${e.message}" }
                                    }
                                }

                                val result = calculatePositionFromOffset(
                                    offset = offset,
                                    contentListState = contentListState,
                                    visibleLineContent = currentVisibleLineContent,
                                    density = density,
                                    fontSize = fontSize,
                                    tabSize = tabSize,
                                    wordWrap = wordWrap,
                                    textLayouts = textLayouts,
                                )

                                if (result != null) {
                                    // Long press: Select word at cursor
                                    val wordSelection = selectWordAt(
                                        result.position.line,
                                        result.position.column,
                                        currentVisibleLineContent
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
                        modifier = Modifier.fillMaxWidth(),
                        onHeightMeasured = if (wordWrap) { height ->
                            lineHeights[lineIndex] = height
                        } else null,
                        onTextLayoutResult = if (wordWrap) { layoutResult ->
                            textLayouts[lineIndex] = layoutResult
                        } else null,
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
                        visibleLineContent = currentVisibleLineContent,
                        density = density,
                        fontSize = fontSize,
                        tabSize = tabSize,
                        wordWrap = wordWrap,
                        textLayouts = textLayouts,
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
                },
                wordWrap = wordWrap,
                textLayouts = textLayouts,
                visibleLineContent = currentVisibleLineContent,
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
                        visibleLineContent = currentVisibleLineContent,
                        density = density,
                        fontSize = fontSize,
                        tabSize = tabSize,
                        wordWrap = wordWrap,
                        textLayouts = textLayouts,
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
                },
                wordWrap = wordWrap,
                textLayouts = textLayouts,
                visibleLineContent = currentVisibleLineContent,
            )
        }
    }

    // Request focus when content is loaded (only if workspace is focused)
    LaunchedEffect(totalLines, isWorkspaceFocused) {
        if (isWorkspaceFocused) {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // Ignore focus errors
            }
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
            onTextDelete = {},
            onCursorPositionChange = {},
            onSelectionChange = {},
            onVisibleRangeChange = {},
            onCursorMove = { _, _ -> },
            onForwardDelete = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}
