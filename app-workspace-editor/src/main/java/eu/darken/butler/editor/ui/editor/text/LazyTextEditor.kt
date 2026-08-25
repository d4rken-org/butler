package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import eu.darken.butler.common.ui.pagerFriendlyHorizontalScroll
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.core.syntax.Token
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

private val tag = logTag("Editor", "LazyTextEditor")

/** Semantics tag for the hidden input field, used by Compose regression tests. */
const val EDITOR_INPUT_TEST_TAG = "editor.input.field"

/** Semantics tag for the tappable text area, used by Compose regression tests. */
internal const val EDITOR_CONTENT_TEST_TAG = "editor.content.lines"

/**
 * Semantics tags for the two selection drag handles. They are drawn on a Canvas and carry no
 * semantics of their own, so Compose regression tests have no other way to address them.
 */
internal const val EDITOR_SELECTION_HANDLE_START_TEST_TAG = "editor.selection.handle.start"
internal const val EDITOR_SELECTION_HANDLE_END_TEST_TAG = "editor.selection.handle.end"

@Composable
fun LazyTextEditor(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    content: String,
    totalLines: Long,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    visibleRange: LongRange,
    truncatedLines: Map<Long, Long> = emptyMap(),
    startColumns: Map<Long, Long> = emptyMap(),
    /** Identity of the document state [content] was read at; null before the first window load. */
    windowToken: EditorEngine.DocumentToken? = null,
    /** First absolute line of [content], captured together with [windowToken]. */
    windowRangeStart: Long = 0L,
    highlightedLines: Map<Long, List<Token>> = emptyMap(),
    showLineNumbers: Boolean = true,
    wordWrap: Boolean = false,
    readOnly: Boolean = false,
    fontSize: Int = 14,
    tabSize: Int = 4,
    searchResults: List<SearchResult> = emptyList(),
    currentSearchResultIndex: Int = 0,
    scrollTrigger: Int = 0,
    onEnqueueDelta: (SessionDelta) -> Deferred<EditorEngine.MutationResult>,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    onVisibleRangeChange: (LongRange) -> Unit,
    onRevealMoreColumns: (Boolean) -> Unit = {},
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

    // The single measured monospace advance ("M" width). Used by the horizontal auto-scroll and, as
    // the pre-layout fallback, by tap hit-testing / selection handles / geometry. Keyed on density too
    // so a font-scale or density change re-measures.
    val textMeasurer = rememberTextMeasurer()
    val charWidth = remember(fontSize, density) {
        val measured = textMeasurer.measure(
            text = "M",
            style = TextStyle(
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Monospace
            )
        )
        measured.size.width.toFloat()
    }

    // Real per-line TextLayoutResults, shared across the auto-scroll (cursor X), tap hit-testing, and
    // selection handles. Populated for BOTH wrap modes by the line items and bounded to composed lines
    // via their DisposableEffect, so it never grows unbounded on huge files.
    val textLayouts = remember { mutableStateMapOf<Long, TextLayoutResult>() }

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

    // Scroll-driven horizontal reveal (wrap-off only): when the user pans a long line to an edge and
    // there is content hidden beyond it, ask the engine to slide the shared window one page that way -
    // browsing past the cap WITHOUT moving the caret. `revealArmed` fires at most once per edge approach
    // and only re-arms after the scroll leaves the edge. It is HOISTED to remember (not a per-launch
    // local) and the effect is keyed only on the stable ScrollState, so a reveal - which republishes
    // truncatedLines/startColumns - can't restart the effect, re-arm mid-edge, and flip-flop against the
    // programmatic auto-scroll-to-cursor. The window maps are read latest via rememberUpdatedState.
    var revealArmed by remember { mutableStateOf(true) }
    val revealTruncated by rememberUpdatedState(truncatedLines)
    val revealStartColumns by rememberUpdatedState(startColumns)
    @OptIn(FlowPreview::class)
    if (!wordWrap) {
        LaunchedEffect(horizontalScrollState) {
            val edgePx = charWidth * 4f
            snapshotFlow { horizontalScrollState.value to horizontalScrollState.maxValue }
                .debounce(120)
                .collect { (value, max) ->
                    if (max <= 0) return@collect
                    val atRight = value >= max - edgePx
                    val atLeft = value <= edgePx
                    if (!atRight && !atLeft) {
                        revealArmed = true // left the edge -> ready for the next approach
                        return@collect
                    }
                    if (!revealArmed) return@collect
                    when {
                        atRight && revealTruncated.isNotEmpty() -> {
                            revealArmed = false
                            onRevealMoreColumns(true)
                        }
                        atLeft && revealStartColumns.isNotEmpty() -> {
                            revealArmed = false
                            onRevealMoreColumns(false)
                        }
                    }
                }
        }
    }

    // Scroll to cursor position when it changes (line, column, or offset)
    // Triggers on any cursor change to ensure cursor is always visible during typing
    // Also triggers on scrollTrigger to force scroll when navigating search results
    // Also triggers on startColumns: when a long line's window SLIDES to follow the caret, the caret's
    // local pixel position changes even though its (absolute) column may not, so re-run to re-centre.
    LaunchedEffect(cursorPosition, scrollTrigger, startColumns) {
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

            // Cursor X position. Engine column is a RAW char index; expand it for the tab-rendered
            // line, clamped into the line FIRST (a display-truncated line's cursor column can sit
            // far past the visible prefix - expanding it unclamped computes huge scroll targets).
            val cursorLineContent = visibleLineContent[cursorPosition.line]
            // Localize the absolute cursor column to the rendered window (it starts at this anchor).
            val cursorLocalColumn = cursorPosition.column - (startColumns[cursorPosition.line] ?: 0L).toInt()
            val expandedCursorColumn = if (cursorLineContent != null) {
                rawToExpandedColumnClamped(cursorLineContent, cursorLocalColumn, tabSize)
            } else {
                cursorLocalColumn
            }
            // Use the cursor line's real layout when available so the scroll decision uses the SAME
            // glyph geometry as tap hit-testing; the measured advance is only the pre-layout fallback.
            // (Matching metrics is what stops the same-point tap drift on long horizontally-scrolled lines.)
            // Only trust the layout when the cursor column fits within it: a stale/placeholder layout
            // (e.g. the " " rendered for a not-yet-updated line) would otherwise collapse cursorX to ~0
            // right after a jump/search to a long line; the measured fallback stays roughly correct there.
            val cursorLayout = textLayouts[cursorPosition.line]
            val cursorX = textPaddingPx + if (cursorLayout != null && expandedCursorColumn <= cursorLayout.layoutInput.text.length) {
                cursorLayout.getHorizontalPosition(expandedCursorColumn, usePrimaryDirection = true)
            } else {
                expandedCursorColumn * charWidth
            }

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
        truncatedLines = truncatedLines,
        startColumns = startColumns,
        windowToken = windowToken,
        windowRangeStart = windowRangeStart,
        highlightedLines = highlightedLines,
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
        charWidthPx = charWidth,
        textLayouts = textLayouts,
        searchResultsByLine = searchResultsByLine,
        currentSearchResultIndex = currentSearchResultIndex,
        onEnqueueDelta = onEnqueueDelta,
        onCursorPositionChange = onCursorPositionChange,
        onSelectionChange = onSelectionChange,
        onCursorMove = onCursorMove,
        onForwardDelete = onForwardDelete,
        // Disarm the pan-at-edge reveal: after a tap slides the window the scroll can still sit at
        // max, and an armed edge effect would immediately fire a second slide.
        onRevealTap = { forward ->
            revealArmed = false
            onRevealMoreColumns(forward)
        },
        modifier = modifier
    )
}

@Composable
private fun DualColumnEditorContent(
    contentPadding: PaddingValues,
    totalLines: Long,
    visibleLineContent: Map<Long, String>,
    visibleRange: LongRange,
    truncatedLines: Map<Long, Long>,
    startColumns: Map<Long, Long>,
    windowToken: EditorEngine.DocumentToken?,
    windowRangeStart: Long,
    highlightedLines: Map<Long, List<Token>>,
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
    charWidthPx: Float,
    textLayouts: MutableMap<Long, TextLayoutResult>,
    searchResultsByLine: Map<Long, List<Pair<Int, SearchResult>>>,
    currentSearchResultIndex: Int,
    onEnqueueDelta: (SessionDelta) -> Deferred<EditorEngine.MutationResult>,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    onCursorMove: (CursorDirection, Boolean) -> Unit,
    onForwardDelete: () -> Unit,
    onRevealTap: (forward: Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Ensure drag/tap handlers always see latest values, not closures captured by pointerInput: the
    // gesture coroutine outlives the anchor, so a stale startColumns map would localize a tap with the
    // OLD window and land the caret at the wrong absolute column after a horizontal slide.
    val currentVisibleLineContent by rememberUpdatedState(visibleLineContent)
    val currentStartColumns by rememberUpdatedState(startColumns)
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

    // The field's lineage: it holds the window every dispatched delta was mapped against, evolves
    // that mapping locally per keystroke, and reports back when a delta is acknowledged or
    // conflicted. Callbacks are read latest so the long-lived session never dispatches through a
    // stale one.
    val sessionScope = rememberCoroutineScope()
    val currentEnqueueDelta by rememberUpdatedState(onEnqueueDelta)
    val session = remember(sessionScope) {
        EditorInputSession(sessionScope) { delta -> currentEnqueueDelta(delta) }
    }
    val sessionRevision by session.state.collectAsState()

    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf<Offset?>(null) }
    var tapCount by remember { mutableIntStateOf(0) }

    // Marker-chip taps are consumed by the chip's clickable and never reach the ancestor tap
    // handler: activate the workspace like a normal editor tap would (but without showing the
    // keyboard) and reset the multi-tap tracker so the next nearby text tap can't be misread as
    // a double/triple tap continuing a sequence the chip tap interrupted.
    val onMarkerRevealTap: (Boolean) -> Unit = { forward ->
        requestWorkspaceFocus?.invoke()
        lastTapTime = 0L
        lastTapPosition = null
        tapCount = 0
        onRevealTap(forward)
    }
    rememberCoroutineScope()
    val density = LocalDensity.current

    // Track measured heights for each line when word wrap is enabled
    val lineHeights = remember { mutableStateMapOf<Long, Int>() }

    // The hidden field text is the visible lines joined by '\n'.
    val currentContent = remember(visibleLineContent) {
        visibleLineContent.entries
            .sortedBy { it.key }
            .joinToString("\n") { it.value }
    }

    // Ownership model arbitrated by the input session:
    //  - A conflicted (or failed) generation hands back an authoritative snapshot: rebuild the field
    //    from THAT payload, never from the composed state, which may be older than the rejection.
    //  - While deltas are unacknowledged the field is authoritative and the window stays pinned: any
    //    sync would clobber in-flight input (IME composition included).
    //  - Once idle, an own edit that came back acknowledged is recognised by the rebuilt text being
    //    identical, so the field is never rebuilt for its own echo; anything else (tap, arrows,
    //    undo/redo, scroll, foreign edit) rebases the session and syncs the field from the engine.
    LaunchedEffect(
        currentContent,
        truncatedLines,
        startColumns,
        windowRangeStart,
        windowToken,
        cursorPosition,
        selection,
        sessionRevision,
    ) {
        // A rebuild payload describes the document as it was when the delta was REJECTED. A paste
        // or undo queued behind that delta can move the document on before this effect runs, so the
        // payload is only authoritative while the composed window has not passed it - rebuilding to
        // an older state would strand the field on a version that nothing re-triggers a sync for,
        // and every later keystroke would chain on it and be dropped.
        val rebuild = session.consumePendingRebuild()?.takeIf { candidate ->
            val rebuildToken = candidate.content.token ?: return@takeIf false
            windowToken == null || (
                windowToken.engineEpoch == rebuildToken.engineEpoch &&
                    windowToken.structuralVersion <= rebuildToken.structuralVersion
                )
        }
        if (rebuild != null) {
            val rebuildLines = rebuild.content.text.split('\n')
            val rebuildStart = rebuild.content.rangeStart
            val rebuildAnchors = rebuild.content.startColumns
            val mapped = rebuild.selection?.let { (start, end) ->
                val s = positionToFlatOffset(rebuildLines, rebuildStart, start, rebuildAnchors)
                val e = positionToFlatOffset(rebuildLines, rebuildStart, end, rebuildAnchors)
                if (s != null && e != null) TextRange(s, e) else null
            } ?: positionToFlatOffset(rebuildLines, rebuildStart, rebuild.cursor, rebuildAnchors)
                ?.let { TextRange(it) }
            textFieldValue = TextFieldValue(
                text = rebuild.content.text,
                selection = mapped ?: TextRange(textFieldValue.selection.end.coerceIn(0, rebuild.content.text.length)),
            )
            rebuild.content.token?.let { session.rebase(it, rebuildStart, rebuildLines, rebuildAnchors) }
            return@LaunchedEffect
        }
        if (session.hasUnackedWork) return@LaunchedEffect
        val token = windowToken ?: return@LaunchedEffect

        val visibleLines = currentContent.split('\n')
        val mappedSelection: TextRange? = selection?.let { (start, end) ->
            val s = positionToFlatOffset(visibleLines, windowRangeStart, start, startColumns)
            val e = positionToFlatOffset(visibleLines, windowRangeStart, end, startColumns)
            if (s != null && e != null) TextRange(s, e) else null
        } ?: positionToFlatOffset(visibleLines, windowRangeStart, cursorPosition, startColumns)
            ?.let { TextRange(it) }

        if (session.matchesWindow(token, windowRangeStart, visibleLines, startColumns)) {
            // Same window as the session rebased on, so only the caret/selection can have moved.
            // A field text that differs from it means an acknowledged own edit whose republication
            // is still in flight (the ack reaches the session before the new window is composed);
            // rebuilding from the pre-edit window here would flicker the keystroke away, and the
            // republication that follows carries a new token and rebases anyway.
            if (textFieldValue.text != currentContent) return@LaunchedEffect
            val newSelection = computeFieldSelectionSync(
                fieldText = textFieldValue.text,
                fieldSelection = textFieldValue.selection,
                engineContent = currentContent,
                mappedSelection = mappedSelection,
            )
            // Keep the value (and its composition), move the caret only.
            if (newSelection != null) textFieldValue = textFieldValue.copy(selection = newSelection)
            return@LaunchedEffect
        }

        // A window OLDER than the newest acknowledgement is an INTERMEDIATE republication: within a
        // burst the acks can arrive before their windows are composed, so the composed one can sit
        // between the session's snapshot and the field's text. Rebasing on it would rebuild the
        // field to a state its own applied edits have already passed - erasing the characters still
        // in flight and restarting the IME connection mid-burst. Every applied delta republishes,
        // so the window that matches is on its way.
        val ackedVersion = session.lastAckedVersion
        if (ackedVersion != null &&
            token.engineEpoch == session.token?.engineEpoch &&
            token.structuralVersion < ackedVersion
        ) {
            return@LaunchedEffect
        }

        session.rebase(token, windowRangeStart, visibleLines, startColumns)
        val newSelection = computeFieldSelectionSync(
            fieldText = textFieldValue.text,
            fieldSelection = textFieldValue.selection,
            engineContent = currentContent,
            mappedSelection = mappedSelection,
        )
        if (newSelection != null) {
            textFieldValue = if (textFieldValue.text == currentContent) {
                // An acknowledged own edit: keep the value (and its composition), remap only.
                textFieldValue.copy(selection = newSelection)
            } else {
                TextFieldValue(text = currentContent, selection = newSelection)
            }
        }
    }

    // When the document flips read-only mid-edit (e.g. the backing file vanished), the field's
    // in-flight local text is now unappliable and would otherwise linger on screen while the
    // engine stays behind. Abandon the lineage and rebuild from engine content.
    LaunchedEffect(readOnly) {
        if (readOnly) {
            session.cancelPending()
            val caret = textFieldValue.selection.end.coerceIn(0, currentContent.length)
            textFieldValue = TextFieldValue(text = currentContent, selection = TextRange(caret))
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

                // The field is authoritative while editing: keep the new value verbatim so its selection
                // and IME composition are preserved.
                textFieldValue = newValue

                // Diff the change into a single contiguous region (covers append, prepend, mid-insert,
                // delete, equal-length/autocorrect replace, and predictive rewrites). The session maps
                // it through the window it last rebased on, evolved by every unacknowledged edit since.
                val edit = computeTextEdit(oldText, newValue.text)
                if (edit != null) session.onFieldEdit(oldText, edit, newValue)
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
                    .pagerFriendlyHorizontalScroll(horizontalScrollState, isWorkspaceFocused = isWorkspaceFocused)
            }

            LazyColumn(
                state = contentListState,
                contentPadding = contentPadding,
                modifier = modifier
                    .testTag(EDITOR_CONTENT_TEST_TAG)
                    .then(contentModifier)
                    // charWidthPx/tabSize feed the hit-testing math below: the gesture scope must
                    // restart when they change or taps keep using stale metrics
                    .pointerInput(isWorkspaceFocused, requestWorkspaceFocus, keyboardController, charWidthPx, tabSize) {
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
                                    charWidthPx = charWidthPx,
                                    tabSize = tabSize,
                                    textLayouts = textLayouts,
                                    lineStartColumns = currentStartColumns,
                                )

                                if (result != null) {
                                    when (tapCount) {
                                        1 -> {
                                            // Single tap: Place cursor
                                            // Selection is cleared automatically by setCursorPosition in the engine
                                            onCursorPositionChange(result.position)
                                            // Move the field caret NOW as well: the engine's answer is
                                            // ordered behind any keystroke still unacknowledged, so a
                                            // character typed right after the tap would otherwise land
                                            // where the caret was before it.
                                            session.localOffsetFor(result.position)?.let { offset ->
                                                textFieldValue = textFieldValue.copy(selection = TextRange(offset))
                                            }
                                        }
                                        2 -> {
                                            // Double tap: Select word
                                            val wordSelection = selectWordAt(
                                                result.position.line,
                                                result.position.column,
                                                currentVisibleLineContent,
                                                currentStartColumns,
                                            )
                                            onSelectionChange(wordSelection)
                                        }
                                        else -> {
                                            // Triple tap and beyond: Select line
                                            val lineSelection = selectLineAt(
                                                result.position.line,
                                                currentVisibleLineContent,
                                                currentStartColumns,
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
                                    charWidthPx = charWidthPx,
                                    tabSize = tabSize,
                                    textLayouts = textLayouts,
                                    lineStartColumns = currentStartColumns,
                                )

                                if (result != null) {
                                    // Long press: Select word at cursor
                                    val wordSelection = selectWordAt(
                                        result.position.line,
                                        result.position.column,
                                        currentVisibleLineContent,
                                        currentStartColumns,
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

                    // Bound the textLayouts map to composed (visible + buffer) lines: drop this line's
                    // layout when the item leaves composition so results can't accumulate on huge files.
                    DisposableEffect(lineIndex) {
                        onDispose { textLayouts.remove(lineIndex) }
                    }

                    TextLineItem(
                        lineIndex = lineIndex,
                        lineContent = lineContent,
                        hiddenChars = truncatedLines[lineIndex] ?: 0L,
                        lineStartColumn = startColumns[lineIndex] ?: 0L,
                        tokens = highlightedLines[lineIndex] ?: emptyList(),
                        cursorPosition = cursorPosition,
                        selection = selection,
                        isCurrentLine = lineIndex == cursorPosition.line,
                        isFocused = isFocused,
                        wordWrap = wordWrap,
                        fontSize = fontSize,
                        tabSize = tabSize,
                        charWidthPx = charWidthPx,
                        searchHighlights = searchResultsByLine[lineIndex] ?: emptyList(),
                        currentSearchResultIndex = currentSearchResultIndex,
                        modifier = Modifier.fillMaxWidth(),
                        onHeightMeasured = if (wordWrap) { height ->
                            lineHeights[lineIndex] = height
                        } else null,
                        // Report the layout for BOTH wrap modes so tap/scroll hit-testing has exact
                        // glyph geometry (not just the measured-advance fallback).
                        onTextLayoutResult = { layoutResult ->
                            textLayouts[lineIndex] = layoutResult
                        },
                        onRevealTap = onMarkerRevealTap,
                    )
                }
            }
        }

        // Each handle pivots around the OTHER endpoint, captured when its own gesture starts - see
        // [SelectionDragCoordinator] for what reading it from the live selection does after a
        // crossover. ONE coordinator for both handles: two fingers can hold both at once, and each
        // emitted selection is a complete pair, so a per-handle tracker would have each event
        // restore the peer endpoint as it was when its own gesture began. Hoisted above the branch
        // so a selection that briefly goes null cannot drop a running gesture.
        val handleDrag = remember { SelectionDragCoordinator() }

        // Selection handles
        if (selection != null && isFocused) {
            val (start, end) = selection

            // Start handle
            SelectionHandle(
                modifier = Modifier.testTag(EDITOR_SELECTION_HANDLE_START_TEST_TAG),
                position = start,
                contentListState = contentListState,
                lineNumberWidth = lineNumberWidth,
                horizontalScrollState = horizontalScrollState,
                actualCharWidth = charWidthPx,
                onDragStart = { handleDrag.beginStart(start, end) },
                onDragEnd = { handleDrag.endStart() },
                onDrag = { offset ->
                    val result = calculatePositionFromOffset(
                        offset = offset,
                        contentListState = contentListState,
                        visibleLineContent = currentVisibleLineContent,
                        density = density,
                        charWidthPx = charWidthPx,
                        tabSize = tabSize,
                        textLayouts = textLayouts,
                        lineStartColumns = currentStartColumns,
                    )

                    if (result != null) {
                        handleDrag.updateStart(result.position)?.let(onSelectionChange)
                    }
                },
                wordWrap = wordWrap,
                textLayouts = textLayouts,
                visibleLineContent = currentVisibleLineContent,
                tabSize = tabSize,
                lineStartColumn = startColumns[start.line] ?: 0L,
            )

            // End handle
            SelectionHandle(
                modifier = Modifier.testTag(EDITOR_SELECTION_HANDLE_END_TEST_TAG),
                position = end,
                contentListState = contentListState,
                lineNumberWidth = lineNumberWidth,
                horizontalScrollState = horizontalScrollState,
                actualCharWidth = charWidthPx,
                onDragStart = { handleDrag.beginEnd(start, end) },
                onDragEnd = { handleDrag.endEnd() },
                onDrag = { offset ->
                    val result = calculatePositionFromOffset(
                        offset = offset,
                        contentListState = contentListState,
                        visibleLineContent = currentVisibleLineContent,
                        density = density,
                        charWidthPx = charWidthPx,
                        tabSize = tabSize,
                        textLayouts = textLayouts,
                        lineStartColumns = currentStartColumns,
                    )

                    if (result != null) {
                        handleDrag.updateEnd(result.position)?.let(onSelectionChange)
                    }
                },
                wordWrap = wordWrap,
                textLayouts = textLayouts,
                visibleLineContent = currentVisibleLineContent,
                tabSize = tabSize,
                lineStartColumn = startColumns[end.line] ?: 0L,
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
        onEnqueueDelta = { CompletableDeferred(EditorEngine.MutationResult.Failed(NotImplementedError())) },
        onCursorPositionChange = {},
        onSelectionChange = {},
        onVisibleRangeChange = {},
        onCursorMove = { _, _ -> },
        onForwardDelete = {},
        modifier = Modifier.fillMaxSize()
    )
}
