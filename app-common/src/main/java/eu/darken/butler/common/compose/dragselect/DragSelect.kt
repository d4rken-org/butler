package eu.darken.butler.common.compose.dragselect

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.autoscroll.AutoScrollTarget
import eu.darken.butler.common.compose.autoscroll.EdgeAutoScroller
import eu.darken.butler.common.compose.autoscroll.LazyGridAutoScrollTarget
import eu.darken.butler.common.compose.autoscroll.LazyListAutoScrollTarget
import kotlin.math.max
import kotlin.math.min

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
    val target = remember(state) { LazyListAutoScrollTarget(state) }
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
    val target = remember(state, startPaddingPx) { LazyGridAutoScrollTarget(state, startPaddingPx) }
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
    target: AutoScrollTarget,
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
        val edgePx = EdgeAutoScroller.DefaultEdge.toPx()
        val maxSpeedPx = EdgeAutoScroller.DefaultMaxSpeed.toPx()
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
            val autoScroller = EdgeAutoScroller(
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
