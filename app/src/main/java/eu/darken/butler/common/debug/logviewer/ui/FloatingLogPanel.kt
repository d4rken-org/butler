package eu.darken.butler.common.debug.logviewer.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Article
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logviewer.core.LogLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

@Composable
internal fun FloatingLogPanel(
    modifier: Modifier = Modifier,
    stateSource: Flow<FloatingLogPanelViewModel.State> = flowOf(FloatingLogPanelViewModel.State()),
    onSetQuery: (String) -> Unit = {},
    onNextMatch: () -> Unit = {},
    onPrevMatch: () -> Unit = {},
    onTogglePause: () -> Unit = {},
    onSetLevel: (Logging.Priority) -> Unit = {},
    onClear: () -> Unit = {},
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val state by stateSource.collectAsState(initial = FloatingLogPanelViewModel.State())
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    // Editing state is owned here (not inside the field) so it survives the field leaving
    // composition on collapse, and rotation via the saver. Driving the field synchronously from
    // local state — instead of the ViewModel's async, Default-dispatched state — is what keeps the
    // cursor from jumping: Compose requires onValueChange to be reflected on the next frame, which a
    // StateFlow round-trip can't guarantee.
    var searchQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }

    // Inset into the safe-drawing area: the activity is edge-to-edge, so without this the header
    // (drag handle) would sit under the status bar where the system steals the drag gesture.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }

        val bubblePx = with(density) { BUBBLE_SIZE.toPx() }
        val minWpx = with(density) { MIN_WIDTH.toPx() }
        val minHpx = with(density) { MIN_HEIGHT.toPx() }
        // Modest default so the panel starts out of the way; the user resizes up from here.
        val defaultWpx = with(density) { minOf(maxWidth - 24.dp, DEFAULT_WIDTH).toPx() }
        val defaultHpx = with(density) { (maxHeight * 0.4f).coerceIn(MIN_HEIGHT, DEFAULT_MAX_HEIGHT).toPx() }

        // Resizable panel size (px). NaN until first laid out / first resize.
        var widthState by rememberSaveable { mutableStateOf(Float.NaN) }
        var heightState by rememberSaveable { mutableStateOf(Float.NaN) }

        val effWpx = PanelGeometry.clampSize(if (widthState.isNaN()) defaultWpx else widthState, minWpx, containerW)
        val effHpx = PanelGeometry.clampSize(if (heightState.isNaN()) defaultHpx else heightState, minHpx, containerH)

        val activeW = if (collapsed) bubblePx else effWpx
        val activeH = if (collapsed) bubblePx else effHpx

        // Drag position relative to the top-start corner. Re-clamped whenever the container or the
        // active size changes (rotation / window resize / collapse / resize).
        var offsetX by rememberSaveable { mutableStateOf(Float.NaN) }
        var offsetY by rememberSaveable { mutableStateOf(Float.NaN) }

        LaunchedEffect(containerW, containerH, activeW, activeH) {
            if (offsetX.isNaN() || offsetY.isNaN()) {
                offsetX = (containerW - activeW).coerceAtLeast(0f)
                offsetY = with(density) { 24.dp.toPx() }
            }
            offsetX = PanelGeometry.clampOffset(offsetX, activeW, containerW)
            offsetY = PanelGeometry.clampOffset(offsetY, activeH, containerH)
        }

        // Move: only from the title/header. activeW/activeH are captured plain floats, so they must
        // be keys — otherwise a resize leaves an old gesture coroutine clamping to stale bounds.
        val dragModifier = Modifier.pointerInput(containerW, containerH, activeW, activeH) {
            detectDragGestures { change, drag ->
                change.consume()
                offsetX = PanelGeometry.clampOffset(offsetX + drag.x, activeW, containerW)
                offsetY = PanelGeometry.clampOffset(offsetY + drag.y, activeH, containerH)
            }
        }

        // Resize from the bottom-end grip: right & bottom edges follow the pointer, top-left pinned.
        val resizeRightModifier = Modifier.pointerInput(containerW, containerH) {
            detectDragGestures { change, drag ->
                change.consume()
                val curW = if (widthState.isNaN()) defaultWpx else widthState
                val curH = if (heightState.isNaN()) defaultHpx else heightState
                val ox = if (offsetX.isNaN()) 0f else offsetX
                val oy = if (offsetY.isNaN()) 0f else offsetY
                widthState = PanelGeometry.clampSize(curW + drag.x, minWpx, (containerW - ox).coerceAtLeast(0f))
                heightState = PanelGeometry.clampSize(curH + drag.y, minHpx, (containerH - oy).coerceAtLeast(0f))
            }
        }

        // Resize from the bottom-start grip: left edge follows the pointer (right edge pinned),
        // bottom edge follows as usual.
        val resizeLeftModifier = Modifier.pointerInput(containerW, containerH) {
            detectDragGestures { change, drag ->
                change.consume()
                val curW = if (widthState.isNaN()) defaultWpx else widthState
                val curH = if (heightState.isNaN()) defaultHpx else heightState
                val ox = if (offsetX.isNaN()) 0f else offsetX
                val oy = if (offsetY.isNaN()) 0f else offsetY
                // Clamp the horizontal delta so the left edge stays on-screen and width >= min.
                val effDx = drag.x.coerceIn(-ox, (curW - minWpx).coerceAtLeast(0f))
                offsetX = ox + effDx
                widthState = curW - effDx
                heightState = PanelGeometry.clampSize(curH + drag.y, minHpx, (containerH - oy).coerceAtLeast(0f))
            }
        }

        val positionModifier = Modifier
            .align(Alignment.TopStart)
            .offset {
                IntOffset(
                    x = if (offsetX.isNaN()) 0 else offsetX.roundToInt(),
                    y = if (offsetY.isNaN()) 0 else offsetY.roundToInt(),
                )
            }

        if (collapsed) {
            CollapsedBubble(
                modifier = positionModifier,
                dragModifier = dragModifier,
                size = BUBBLE_SIZE,
                hasErrors = state.lines.any { it.priority >= Logging.Priority.ERROR },
                onExpand = { collapsed = false },
            )
        } else {
            ExpandedPanel(
                modifier = positionModifier,
                dragModifier = dragModifier,
                resizeLeftModifier = resizeLeftModifier,
                resizeRightModifier = resizeRightModifier,
                width = with(density) { effWpx.toDp() },
                height = with(density) { effHpx.toDp() },
                state = state,
                searchVisible = searchVisible,
                searchQuery = searchQuery,
                onToggleSearch = {
                    searchVisible = !searchVisible
                    if (!searchVisible) {
                        searchQuery = TextFieldValue("")
                        onSetQuery("")
                    }
                },
                onSearchQueryChange = {
                    searchQuery = it
                    onSetQuery(it.text)
                },
                onClearSearch = {
                    searchQuery = TextFieldValue("")
                    onSetQuery("")
                },
                onNextMatch = onNextMatch,
                onPrevMatch = onPrevMatch,
                onTogglePause = onTogglePause,
                onSetLevel = onSetLevel,
                onClear = onClear,
                onCopy = onCopy,
                onShare = onShare,
                onCollapse = { collapsed = true },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun CollapsedBubble(
    modifier: Modifier,
    dragModifier: Modifier,
    size: Dp,
    hasErrors: Boolean,
    onExpand: () -> Unit,
) {
    Surface(
        modifier = modifier.size(size).then(dragModifier),
        shape = CircleShape,
        color = if (hasErrors) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        onClick = onExpand,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.TwoTone.Article,
                contentDescription = stringResource(R.string.debug_logview_screen_title),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ExpandedPanel(
    modifier: Modifier,
    dragModifier: Modifier,
    resizeLeftModifier: Modifier,
    resizeRightModifier: Modifier,
    width: Dp,
    height: Dp,
    state: FloatingLogPanelViewModel.State,
    searchVisible: Boolean,
    searchQuery: TextFieldValue,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    onClearSearch: () -> Unit,
    onNextMatch: () -> Unit,
    onPrevMatch: () -> Unit,
    onTogglePause: () -> Unit,
    onSetLevel: (Logging.Priority) -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    var showLevelDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.width(width).height(height),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header doubles as the (only) drag handle.
                Row(
                    modifier = Modifier.fillMaxWidth().then(dragModifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.TwoTone.Article,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp).size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.debug_logview_screen_title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.TwoTone.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.debug_logview_collapse_action),
                        )
                    }
                    OverflowMenu(
                        state = state,
                        onToggleSearch = onToggleSearch,
                        onTogglePause = onTogglePause,
                        onOpenLevelDialog = { showLevelDialog = true },
                        onClear = onClear,
                        onCopy = onCopy,
                        onShare = onShare,
                        onClose = onClose,
                    )
                }

                // Search row is hidden by default; toggled from the overflow menu.
                if (searchVisible) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompactSearchField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            onClear = onClearSearch,
                            onClose = onToggleSearch,
                            modifier = Modifier.weight(1f),
                        )
                        if (searchQuery.text.isNotBlank()) {
                            Text(
                                text = "${state.currentOrdinal}/${state.matchCount}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            IconButton(
                                onClick = onPrevMatch,
                                enabled = state.matchCount > 0,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.debug_logview_search_prev_action),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                onClick = onNextMatch,
                                enabled = state.matchCount > 0,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.debug_logview_search_next_action),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                if (state.isPaused) {
                    Text(
                        text = if (state.pausedNewCount > 0) {
                            stringResource(R.string.debug_logview_paused_new_msg, state.pausedNewCount)
                        } else {
                            stringResource(R.string.debug_logview_paused_msg)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }

                LogPanelList(
                    modifier = Modifier.fillMaxSize(),
                    lines = state.lines,
                    query = state.query,
                    currentMatchLineId = state.currentMatchLineId,
                )
            }

            // Resize grips in both bottom corners; the jump-to-bottom FAB sits bottom-center.
            ResizeGrip(
                modifier = Modifier.align(Alignment.BottomStart).then(resizeLeftModifier),
            )
            ResizeGrip(
                modifier = Modifier.align(Alignment.BottomEnd).then(resizeRightModifier),
            )
        }
    }

    if (showLevelDialog) {
        LogLevelDialog(
            current = state.displayPriority,
            onSelect = onSetLevel,
            onDismiss = { showLevelDialog = false },
        )
    }
}

private val BUBBLE_SIZE = 48.dp
private val MIN_WIDTH = 200.dp
private val MIN_HEIGHT = 140.dp
private val DEFAULT_WIDTH = 320.dp
private val DEFAULT_MAX_HEIGHT = 280.dp

@Preview2
@Composable
private fun FloatingLogPanelPreview() {
    PreviewWrapper {
        FloatingLogPanel(
            stateSource = flowOf(
                FloatingLogPanelViewModel.State(
                    lines = listOf(
                        LogLine(1, Logging.Priority.DEBUG, "Explorer:Workspace", "Listing /storage/emulated/0"),
                        LogLine(2, Logging.Priority.INFO, "Searcher:Engine", "Found 3 matches"),
                        LogLine(3, Logging.Priority.WARN, "GatewaySwitch", "Escalating to ROOT for /data"),
                        LogLine(4, Logging.Priority.ERROR, "AdbManager", "Shizuku connection failed"),
                    ),
                    query = "found",
                    matchCount = 1,
                    currentOrdinal = 1,
                    currentMatchLineId = 2,
                )
            ),
        )
    }
}

@Preview2
@Composable
private fun FloatingLogPanelPausedPreview() {
    PreviewWrapper {
        FloatingLogPanel(
            stateSource = flowOf(
                FloatingLogPanelViewModel.State(
                    lines = listOf(
                        LogLine(1, Logging.Priority.DEBUG, "IO:GatewaySwitch", "lookup(/sdcard/DCIM)"),
                        LogLine(2, Logging.Priority.ERROR, "RootServiceClient", "Connection lost"),
                    ),
                    isPaused = true,
                    pausedNewCount = 42,
                )
            ),
        )
    }
}
