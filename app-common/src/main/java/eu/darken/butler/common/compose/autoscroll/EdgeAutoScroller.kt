package eu.darken.butler.common.compose.autoscroll

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Scrolls the content while the pointer rests near a viewport edge, advancing by frame delta so the
 * speed is refresh-rate independent. Stops as soon as the content bound is reached and is re-armed
 * by the next drag event.
 *
 * [onScrolled] reports whether the session is still live: a pointer resting at the edge produces no
 * events, so the frame loop is the only place that would notice the session ending. It receives the
 * same container-relative pointer that was handed to [update].
 */
class EdgeAutoScroller(
    private val scope: CoroutineScope,
    private val target: AutoScrollTarget,
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

    companion object {
        /** How close to a viewport edge the pointer has to get before the content starts scrolling. */
        val DefaultEdge = 56.dp

        /** Auto-scroll speed at the very edge of the viewport, scaled down with the distance to it. */
        val DefaultMaxSpeed = 900.dp
    }
}

/** The lazy container a session drives: hit-testing, viewport extent and scrolling. */
interface AutoScrollTarget {
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
class LazyListAutoScrollTarget(private val state: LazyListState) : AutoScrollTarget {

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

class LazyGridAutoScrollTarget(
    private val state: LazyGridState,
    private val startPaddingPx: Int = 0,
) : AutoScrollTarget {

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
