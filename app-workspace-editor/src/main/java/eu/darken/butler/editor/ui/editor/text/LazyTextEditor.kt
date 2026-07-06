package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.propagateScrollAtBoundary
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

private val tag = logTag("Editor", "LazyTextEditor")

/** Semantics tag for the hidden input field, used by Compose regression tests. */
const val EDITOR_INPUT_TEST_TAG = "editor.input.field"

@Composable
fun LazyTextEditor(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    content: String,
    totalLines: Long,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    visibleRange: LongRange,
    showLineNumbers: Boolean = true,
    wordWrap: Boolean = false,
    readOnly: Boolean = false,
    fontSize: Int = 14,
    tabSize: Int = 4,
    searchResults: List<SearchResult> = emptyList(),
    currentSearchResultIndex: Int = 0,
    scrollTrigger: Int = 0,
    onTextReplace: (start: TextPosition, end: TextPosition, inserted: String, caret: TextPosition) -> Unit,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    onVisibleRangeChange: (LongRange) -> Unit,
    onCursorMove: (CursorDirection, Boolean) -> Unit,
    onForwardDelete: () -> Unit,
) {
    // Create a map of visible line content indexed by line number
    val visibleLineContent = remember(content, visibleRange) {
        if (content.isEmpty()) {
            mapOf(0L to "")
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

    // Release focus and hide keyboard when workspace loses focus
    LaunchedEffect(isWorkspaceFocused) {
        if (!isWorkspaceFocused) focusManager.clearFocus()
    }
    val horizontalScrollState = rememberScrollState()
    var pendingHorizontalScroll by remember { mutableStateOf<Int?>(null) }
    rememberCoroutineScope()
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

    // Update visible range when scroll position changes (debounced to reduce load frequency)
    @OptIn(FlowPreview::class)
    LaunchedEffect(totalLines) {
        snapshotFlow {
            contentListState.firstVisibleItemIndex to contentListState.layoutInfo.visibleItemsInfo.size
        }.debounce(50).collect { (firstVisibleIndex, visibleItemsSize) ->
            if (totalLines > 0 && contentListState.layoutInfo.totalItemsCount > 0) {
                val startIndex = (firstVisibleIndex - 10).coerceAtLeast(0).toLong()
                val visibleCount = visibleItemsSize.coerceAtLeast(1)
                val endIndex = minOf(
                    startIndex + visibleCount + 10, // Buffer
                    totalLines - 1
                ).coerceAtLeast(startIndex)

                onVisibleRangeChange(startIndex..endIndex)
            }
        }
    }

    // Scroll to cursor position when it changes (line, column, or offset)
    // Triggers on any cursor change to ensure cursor is always visible during typing
    // Also triggers on scrollTrigger to force scroll when navigating search results
    LaunchedEffect(cursorPosition, scrollTrigger) {
        if (totalLines <= 0 || contentListState.layoutInfo.totalItemsCount <= 0) return@LaunchedEffect

        val targetLine = cursorPosition.line.coerceIn(0, totalLines - 1)

        // VERTICAL: Check if cursor line is outside visible viewport
        val firstVisibleLine = contentListState.firstVisibleItemIndex
        val visibleCount = contentListState.layoutInfo.visibleItemsInfo.size
        val lastVisibleLine = firstVisibleLine + visibleCount - 1

        val needsVerticalScroll = targetLine < firstVisibleLine || targetLine > lastVisibleLine
        val forceScroll = scrollTrigger > 0

        if (needsVerticalScroll || forceScroll) {
            try {
                // Center the target line in viewport (not at top edge)
                val centerOffset = (visibleCount / 2).coerceAtLeast(0)
                val scrollTarget = (targetLine - centerOffset).coerceAtLeast(0).toIntSaturated()
                contentListState.scrollToItem(scrollTarget)
                lineNumbersListState.scrollToItem(scrollTarget)
            } catch (_: Exception) {
                // Ignore scroll errors - layout might not be ready yet
            }
        }

        // HORIZONTAL: Only when word wrap is disabled
        if (!wordWrap) {
            val viewportWidth = horizontalScrollState.viewportSize.toFloat()
            if (viewportWidth <= 0) return@LaunchedEffect

            val textPaddingPx = with(density) { 8.dp.toPx() } // Match TextLineItem padding
            val margin = charWidth * 3 // 3 character margin from edge

            // Cursor X position. Engine column is a RAW char index; expand it for the tab-rendered line.
            val cursorLineContent = visibleLineContent[cursorPosition.line]
            val expandedCursorColumn = if (cursorLineContent != null) {
                rawToExpandedColumn(cursorLineContent, cursorPosition.column, tabSize)
            } else {
                cursorPosition.column
            }
            val cursorX = textPaddingPx + (expandedCursorColumn * charWidth)

            val currentScrollX = horizontalScrollState.value.toFloat()
            val visibleRight = currentScrollX + viewportWidth

            // Center cursor horizontally in viewport when scrolling
            val centerOffset = viewportWidth / 2
            val targetScroll: Int? = when {
                cursorX < currentScrollX + margin -> (cursorX - centerOffset).coerceAtLeast(0f).toInt()
                cursorX > visibleRight - margin -> (cursorX - centerOffset).coerceAtLeast(0f).toInt()
                else -> null
            }

            if (targetScroll != null) {
                // If maxValue > 0, we can scroll immediately; otherwise defer
                if (horizontalScrollState.maxValue > 0) {
                    horizontalScrollState.scrollTo(targetScroll)
                } else {
                    // Layout not ready yet - defer scroll until maxValue > 0
                    pendingHorizontalScroll = targetScroll
                }
            }
        }
    }

    // Apply deferred horizontal scroll when layout becomes ready
    LaunchedEffect(pendingHorizontalScroll) {
        val target = pendingHorizontalScroll ?: return@LaunchedEffect
        // Wait for maxValue to become > 0 (layout ready)
        snapshotFlow { horizontalScrollState.maxValue }
            .filter { it > 0 }
            .first()
        horizontalScrollState.scrollTo(target.coerceAtMost(horizontalScrollState.maxValue))
        pendingHorizontalScroll = null
    }

    // Group search results by line for efficient lookup
    val searchResultsByLine = remember(searchResults) {
        searchResults.mapIndexed { index, result -> index to result }
            .groupBy { it.second.position.line }
    }

    // Synchronized dual-column content
    DualColumnEditorContent(
        contentPadding = contentPadding,
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
        readOnly = readOnly,
        fontSize = fontSize,
        tabSize = tabSize,
        searchResultsByLine = searchResultsByLine,
        currentSearchResultIndex = currentSearchResultIndex,
        onTextReplace = onTextReplace,
        onCursorPositionChange = onCursorPositionChange,
        onSelectionChange = onSelectionChange,
        onCursorMove = onCursorMove,
        onForwardDelete = onForwardDelete,
        modifier = modifier
    )
}

@Composable
private fun DualColumnEditorContent(
    contentPadding: PaddingValues,
    totalLines: Long,
    visibleLineContent: Map<Long, String>,
    visibleRange: LongRange,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    lineNumbersListState: LazyListState,
    contentListState: LazyListState,
    horizontalScrollState: ScrollState,
    focusRequester: FocusRequester,
    showLineNumbers: Boolean,
    wordWrap: Boolean,
    readOnly: Boolean,
    fontSize: Int,
    tabSize: Int,
    searchResultsByLine: Map<Long, List<Pair<Int, SearchResult>>>,
    currentSearchResultIndex: Int,
    onTextReplace: (start: TextPosition, end: TextPosition, inserted: String, caret: TextPosition) -> Unit,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    onCursorMove: (CursorDirection, Boolean) -> Unit,
    onForwardDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Ensure drag handlers always see latest values, not captured closures
    val currentVisibleLineContent by rememberUpdatedState(visibleLineContent)
    val isWorkspaceFocused = LocalWorkspaceFocused.current
    val requestWorkspaceFocus = LocalWorkspaceFocusRequest.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Release focus and hide keyboard when workspace loses focus
    LaunchedEffect(isWorkspaceFocused) {
        if (!isWorkspaceFocused) focusManager.clearFocus()
    }

    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var isFocused by remember { mutableStateOf(false) }
    var isUserEditing by remember { mutableStateOf(false) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf<Offset?>(null) }
    var tapCount by remember { mutableIntStateOf(0) }
    rememberCoroutineScope()
    val density = LocalDensity.current
    val contentPaddingTopPx = with(density) { contentPadding.calculateTopPadding().toPx() }

    // Track measured heights for each line when word wrap is enabled
    val lineHeights = remember { mutableStateMapOf<Long, Int>() }

    // Track TextLayoutResults for accurate tap position calculation when word wrap is enabled
    val textLayouts = remember { mutableStateMapOf<Long, TextLayoutResult>() }

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

    // The hidden field text is the visible lines joined by '\n'.
    val currentContent = remember(visibleLineContent) {
        visibleLineContent.entries
            .sortedBy { it.key }
            .joinToString("\n") { it.value }
    }

    // Ownership model arbitrated by isUserEditing:
    //  - While the user is typing (isUserEditing): the hidden field is authoritative. We skip syncing so
    //    we never clobber in-flight input (incl. IME composition). Authority is released only once the
    //    engine echo has caught up with the field (texts match), so fast multi-keystroke bursts don't
    //    drop characters.
    //  - Otherwise (tap, arrows, undo/redo, programmatic): the engine is authoritative. Rebuild the field
    //    text and map the field selection from the engine cursor/selection so the IME composes in the
    //    right place.
    LaunchedEffect(currentContent, visibleRange, cursorPosition, selection) {
        if (isUserEditing) {
            if (textFieldValue.text == currentContent) isUserEditing = false
            return@LaunchedEffect
        }

        val visibleLines = currentContent.split('\n')
        val rangeStart = visibleRange.first

        val mappedSelection: TextRange? = selection?.let { (start, end) ->
            val s = positionToFlatOffset(visibleLines, rangeStart, start)
            val e = positionToFlatOffset(visibleLines, rangeStart, end)
            if (s != null && e != null) TextRange(s, e) else null
        } ?: positionToFlatOffset(visibleLines, rangeStart, cursorPosition)?.let { TextRange(it) }

        val newSelection = computeFieldSelectionSync(
            fieldText = textFieldValue.text,
            fieldSelection = textFieldValue.selection,
            engineContent = currentContent,
            mappedSelection = mappedSelection,
        )
        if (newSelection != null) {
            textFieldValue = TextFieldValue(text = currentContent, selection = newSelection)
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
    LaunchedEffect(Unit) {
        snapshotFlow {
            contentListState.firstVisibleItemIndex to contentListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (lineNumbersListState.firstVisibleItemIndex != index ||
                lineNumbersListState.firstVisibleItemScrollOffset != offset
            ) {
                try {
                    lineNumbersListState.scrollToItem(index, offset)
                } catch (_: Exception) {
                    // Ignore sync errors
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow {
            lineNumbersListState.firstVisibleItemIndex to lineNumbersListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (contentListState.firstVisibleItemIndex != index ||
                contentListState.firstVisibleItemScrollOffset != offset
            ) {
                try {
                    contentListState.scrollToItem(index, offset)
                } catch (_: Exception) {
                    // Ignore sync errors
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
    ) {
        // Hidden text field for keyboard input
        BasicTextField(
            value = textFieldValue,
            readOnly = readOnly,
            onValueChange = { newValue ->
                val oldText = textFieldValue.text
                val newText = newValue.text

                // The field is authoritative while editing: keep the new value verbatim so its selection
                // and IME composition are preserved.
                textFieldValue = newValue

                // Diff the change into a single contiguous region (covers append, prepend, mid-insert,
                // delete, equal-length/autocorrect replace, and predictive rewrites).
                val edit = computeTextEdit(oldText, newText)
                if (edit != null) {
                    isUserEditing = true
                    val rangeStart = visibleRange.first
                    val oldLines = oldText.split('\n')
                    val newLines = newText.split('\n')
                    val start = flatOffsetToPosition(oldLines, rangeStart, edit.start)
                    val end = flatOffsetToPosition(oldLines, rangeStart, edit.end)
                    // Forward the resulting caret (mapped from the field selection in the NEW text) so the
                    // engine cursor lands exactly where the IME caret is and the echo maps back unchanged.
                    val caret = flatOffsetToPosition(newLines, rangeStart, newValue.selection.end)
                    onTextReplace(start, end, edit.inserted, caret)
                }
            },
            modifier = Modifier
                .testTag(EDITOR_INPUT_TEST_TAG)
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
                                if (cursorPosition.line == 0L && !event.isShiftPressed) {
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
                    contentPadding = contentPadding,
                    modifier = Modifier
                        .width(lineNumberWidth)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clipToBounds()
                ) {
                    items(
                        count = totalLines.toIntSaturated(),
                        key = { index -> "line_num_$index" }
                    ) { lineIndex ->
                        val measuredHeight = if (wordWrap) lineHeights[lineIndex.toLong()] else null

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
                    .propagateScrollAtBoundary(horizontalScrollState, enabled = isWorkspaceFocused)
                    .horizontalScroll(horizontalScrollState)
            }

            LazyColumn(
                state = contentListState,
                contentPadding = contentPadding,
                modifier = modifier
                    .then(contentModifier)
                    // fontSize/tabSize are captured by the hit-testing math below: the gesture
                    // scope must restart when they change or taps keep using stale metrics
                    .pointerInput(isWorkspaceFocused, requestWorkspaceFocus, keyboardController, fontSize, tabSize) {
                        detectTapGestures(
                            onTap = { offset ->
                                // Ignore taps during scroll fling to prevent accidental keyboard show
                                if (contentListState.isScrollInProgress) return@detectTapGestures

                                // Request workspace focus so this pane becomes active
                                requestWorkspaceFocus?.invoke()

                                // Request focus first (only if workspace is focused)
                                if (isWorkspaceFocused) {
                                    try {
                                        focusRequester.requestFocus()
                                        keyboardController?.show()
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
                                    contentPaddingTop = contentPaddingTopPx,
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
                                // Ignore long press during scroll
                                if (contentListState.isScrollInProgress) return@detectTapGestures

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
                                    contentPaddingTop = contentPaddingTopPx,
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
                    count = totalLines.toIntSaturated(),
                    key = { index -> "line_content_$index" }
                ) { itemIndex ->
                    val lineIndex = itemIndex.toLong()
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
                        searchHighlights = searchResultsByLine[lineIndex] ?: emptyList(),
                        currentSearchResultIndex = currentSearchResultIndex,
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
                        contentPaddingTop = contentPaddingTopPx,
                    )

                    if (result != null) {
                        // Update selection start, keep end fixed
                        val (newStart, newEnd) = if (result.position.line < end.line ||
                            (result.position.line == end.line && result.position.column < end.column)
                        ) {
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
                tabSize = tabSize,
                contentPaddingTop = contentPaddingTopPx,
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
                        contentPaddingTop = contentPaddingTopPx,
                    )

                    if (result != null) {
                        // Update selection end, keep start fixed
                        val (newStart, newEnd) = if (result.position.line > start.line ||
                            (result.position.line == start.line && result.position.column > start.column)
                        ) {
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
                tabSize = tabSize,
                contentPaddingTop = contentPaddingTopPx,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LazyTextEditorPreview() {
    val sampleContent = $$"""
        fun calculateSum(a: Int, b: Int): Int {
            return a + b
        }

        fun main() {
            val result = calculateSum(5, 3)
            println("Result: $result")
        }
    """.trimIndent()

    LazyTextEditor(
        content = sampleContent,
        totalLines = sampleContent.split('\n').size.toLong(),
        cursorPosition = TextPosition(offset = 50, line = 1, column = 10),
        selection = null,
        visibleRange = 0L..7L,
        showLineNumbers = true,
        wordWrap = false,
        fontSize = 14,
        tabSize = 4,
        onTextReplace = { _, _, _, _ -> },
        onCursorPositionChange = {},
        onSelectionChange = {},
        onVisibleRangeChange = {},
        onCursorMove = { _, _ -> },
        onForwardDelete = {},
        modifier = Modifier.fillMaxSize()
    )
}
