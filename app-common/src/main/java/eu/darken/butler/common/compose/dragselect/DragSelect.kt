package eu.darken.butler.common.compose.dragselect

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How close to a viewport edge the pointer has to get before the content starts scrolling. */
private val AutoScrollEdge = 56.dp

/** Auto-scroll speed at the very edge of the viewport, scaled down with the distance to it. */
private val AutoScrollMaxSpeed = 900.dp

/**
 * Long-press-then-drag multi selection for a vertical [LazyListState] container.
 *
 * Apply to the lazy container itself, next to its `state`. The long press claims the gesture only
 * when it lands on a key from [orderedKeys] that [enabled] accepts - any other press is left
 * completely untouched, so item long clicks, platform drags and long-press-then-scroll keep
 * working as before.
 *
 * @param orderedKeys the selectable keys in display order; re-read on every update, so the data may
 *        re-sort mid-drag.
 * @param currentSelection the selection at the moment the drag starts; it is never deselected,
 *        the drag only adds the range between the anchor and the pointer.
 * @param onSelectionChange invoked with the full new selection, only when it actually changes.
 * @param enabled decides per pressed key whether drag-select owns this long press.
 */
@Composable
fun <K : Any> Modifier.listDragSelect(
    state: LazyListState,
    orderedKeys: () -> List<K>,
    currentSelection: () -> Set<K>,
    onSelectionChange: (Set<K>) -> Unit,
    enabled: (K) -> Boolean = { true },
): Modifier {
    val target = remember(state) { LazyListDragSelectTarget(state) }
    return dragSelect(target, orderedKeys, currentSelection, onSelectionChange, enabled)
}

/**
 * [listDragSelect] for a vertical [LazyGridState] container; the range follows the display order.
 *
 * @param contentPadding the grid's own content padding; its leading (start) inset is subtracted from
 *        the pointer x before hit-testing, because grid item offsets are content-relative on the
 *        cross axis while LazyGridLayoutInfo never exposes the horizontal padding.
 */
@Composable
fun <K : Any> Modifier.gridDragSelect(
    state: LazyGridState,
    orderedKeys: () -> List<K>,
    currentSelection: () -> Set<K>,
    onSelectionChange: (Set<K>) -> Unit,
    enabled: (K) -> Boolean = { true },
    contentPadding: PaddingValues = PaddingValues(0.dp),
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val startPaddingPx = with(LocalDensity.current) {
        contentPadding.calculateStartPadding(layoutDirection).roundToPx()
    }
    val target = remember(state, startPaddingPx) { LazyGridDragSelectTarget(state, startPaddingPx) }
    return dragSelect(target, orderedKeys, currentSelection, onSelectionChange, enabled)
}

/**
 * Claim-before-consume gesture: nothing is consumed until the long press has resolved to a key the
 * caller accepts. `detectDragGesturesAfterLongPress` would consume every post-long-press movement
 * even when the session declines, which silently breaks scrolling and competing gestures.
 *
 * Once claimed, the drag events are consumed on the initial pass - the lazy container's own
 * scrollable and the item's press sit below this modifier and see the consumption, so neither the
 * list scrolls nor the item click fires while the range is being dragged.
 */
@Composable
private fun <K : Any> Modifier.dragSelect(
    target: DragSelectTarget,
    orderedKeys: () -> List<K>,
    currentSelection: () -> Set<K>,
    onSelectionChange: (Set<K>) -> Unit,
    enabled: (K) -> Boolean,
): Modifier {
    val currentOrderedKeys by rememberUpdatedState(orderedKeys)
    val currentSelectionProvider by rememberUpdatedState(currentSelection)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentEnabled by rememberUpdatedState(enabled)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    return this.pointerInput(target) {
        val edgePx = AutoScrollEdge.toPx()
        val maxSpeedPx = AutoScrollMaxSpeed.toPx()
        awaitEachGesture {
            // The pane focus handler consumes the down on the initial pass, so an unconsumed down
            // would never arrive here.
            val down = awaitFirstDown(requireUnconsumed = false)
            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val pressedKey = target.keyAt(longPress.position)
            val anchor = currentOrderedKeys()
                .firstOrNull { it == pressedKey }
                ?.takeIf { currentEnabled(it) }
                ?: return@awaitEachGesture

            val session = DragSelectSession(
                anchor = anchor,
                base = currentSelectionProvider(),
                orderedKeys = { currentOrderedKeys() },
                onSelectionChange = { currentOnSelectionChange(it) },
                onEndpointChanged = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
            )
            val autoScroller = DragSelectAutoScroller(
                scope = scope,
                target = target,
                edgePx = edgePx,
                maxSpeedPx = maxSpeedPx,
                // A finger resting at the edge keeps extending the range while the content moves.
                onScrolled = { position ->
                    session.moveTo(target.keyAt(position))
                    session.isActive
                },
            )
            session.start()
            try {
                while (session.isActive) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    session.moveTo(target.keyAt(change.position))
                    autoScroller.update(change.position)
                }
            } finally {
                autoScroller.stop()
                session.end()
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            }
        }
    }
}

/**
 * Applied selection = selection at drag start ∪ the contiguous range between anchor and endpoint.
 * Both ends are held as keys and re-resolved against a fresh key list on every update, so the data
 * may re-sort or shrink while the finger is down.
 */
private class DragSelectSession<K : Any>(
    private val anchor: K,
    private val base: Set<K>,
    private val orderedKeys: () -> List<K>,
    private val onSelectionChange: (Set<K>) -> Unit,
    private val onEndpointChanged: () -> Unit,
) {

    private var endpoint: K = anchor
    private var applied: Set<K> = base

    var isActive: Boolean = true
        private set

    fun start() = moveTo(anchor)

    /** [pressedKey] is the raw key under the pointer; anything not selectable keeps the last endpoint. */
    fun moveTo(pressedKey: Any?) {
        if (!isActive) return
        val keys = orderedKeys()
        val anchorIndex = keys.indexOf(anchor)
        // The anchor vanished from the listing - end the session, keep what it applied so far.
        if (anchorIndex == -1) {
            isActive = false
            return
        }
        keys.firstOrNull { it == pressedKey }?.let { next ->
            if (next != endpoint) {
                endpoint = next
                onEndpointChanged()
            }
        }
        val endpointIndex = keys.indexOf(endpoint)
        if (endpointIndex == -1) return
        val range = keys.subList(min(anchorIndex, endpointIndex), max(anchorIndex, endpointIndex) + 1)
        // Union, never subtraction: keys the caller had selected before the drag - including ones
        // hidden by a filter and thus absent from the listing - ride along untouched.
        val next = base + range
        if (next == applied) return
        applied = next
        onSelectionChange(next)
    }

    fun end() {
        isActive = false
    }
}

/**
 * Scrolls the content while the pointer rests near a viewport edge, advancing by frame delta so the
 * speed is refresh-rate independent. Stops as soon as the content bound is reached and is re-armed
 * by the next drag event.
 *
 * [onScrolled] reports whether the session is still live: a finger resting at the edge produces no
 * pointer events, so the frame loop is the only place that would notice the session ending.
 */
private class DragSelectAutoScroller(
    private val scope: CoroutineScope,
    private val target: DragSelectTarget,
    private val edgePx: Float,
    private val maxSpeedPx: Float,
    private val onScrolled: (Offset) -> Boolean,
) {

    private var job: Job? = null
    private var pointer: Offset = Offset.Unspecified

    fun update(pointer: Offset) {
        this.pointer = pointer
        if (speedFor(pointer) == 0f) {
            stop()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            var previousFrame = withFrameNanos { it }
            while (true) {
                val frame = withFrameNanos { it }
                val seconds = (frame - previousFrame) / 1_000_000_000f
                previousFrame = frame
                val speed = speedFor(pointer)
                if (speed == 0f) break
                if (target.scrollableState.scrollBy(speed * seconds) == 0f) break
                if (!onScrolled(pointer)) break
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun speedFor(pointer: Offset): Float {
        if (pointer == Offset.Unspecified) return 0f
        val viewport = target.viewportMainAxisSize.toFloat()
        if (viewport <= 0f) return 0f
        val toStart = pointer.y
        val toEnd = viewport - pointer.y
        return when {
            toStart < edgePx -> -maxSpeedPx * ((edgePx - toStart.coerceAtLeast(0f)) / edgePx)
            toEnd < edgePx -> maxSpeedPx * ((edgePx - toEnd.coerceAtLeast(0f)) / edgePx)
            else -> 0f
        }
    }
}

/** The lazy container the session drives: hit-testing, viewport extent and scrolling. */
internal interface DragSelectTarget {
    val scrollableState: ScrollableState

    /** Main-axis extent of the viewport in pixels, content padding included. */
    val viewportMainAxisSize: Int

    /** The key of the item under [position], which is relative to the container's top left. */
    fun keyAt(position: Offset): Any?
}

/**
 * Item offsets are relative to the scrolled content, the pointer is relative to the container -
 * `viewportStartOffset` is the difference (negative by the amount of leading content padding).
 */
internal class LazyListDragSelectTarget(private val state: LazyListState) : DragSelectTarget {

    override val scrollableState: ScrollableState get() = state

    override val viewportMainAxisSize: Int get() = state.layoutInfo.viewportSize.height

    override fun keyAt(position: Offset): Any? {
        val layoutInfo = state.layoutInfo
        val mainAxis = position.y + layoutInfo.viewportStartOffset
        return layoutInfo.visibleItemsInfo
            .firstOrNull { mainAxis >= it.offset && mainAxis < it.offset + it.size }
            ?.key
    }
}

internal class LazyGridDragSelectTarget(
    private val state: LazyGridState,
    private val startPaddingPx: Int = 0,
) : DragSelectTarget {

    override val scrollableState: ScrollableState get() = state

    override val viewportMainAxisSize: Int get() = state.layoutInfo.viewportSize.height

    override fun keyAt(position: Offset): Any? {
        val layoutInfo = state.layoutInfo
        // Item cross-axis offsets exclude the leading content padding, so undo it on the pointer.
        val point = IntOffset(
            x = (position.x - startPaddingPx).roundToInt(),
            y = (position.y + layoutInfo.viewportStartOffset).roundToInt(),
        )
        return layoutInfo.visibleItemsInfo
            .firstOrNull { IntRect(it.offset, it.size).contains(point) }
            ?.key
    }
}
