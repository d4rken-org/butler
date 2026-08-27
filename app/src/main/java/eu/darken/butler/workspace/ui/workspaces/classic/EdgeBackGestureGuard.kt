package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.math.abs

/**
 * Keeps a horizontal drag that starts in a system back-gesture strip away from the pager.
 *
 * A Compose scrollable that starts dragging asks the host window to stop intercepting touches. On
 * ROMs that hand the app the edge touch before deciding the back gesture is theirs, that cancels
 * the gesture mid-flight: the page shifts a few pixels, back never fires, and the drag dies below
 * the fling threshold so the pager snaps back. The press looks like it did nothing. ROMs that
 * reserve the strip up front never deliver the touch at all and are unaffected either way.
 *
 * Only a drag that both starts inside the strip and is predominantly horizontal is swallowed. Taps
 * and vertical scrolls in the strip still reach the content, and a swipe starting anywhere else
 * still turns the page.
 *
 * The claim is made on [PointerEventPass.Initial] and below the pager's own touch slop, so the
 * pager's drag never begins and the window is never asked to stop intercepting. Zeroing the scroll
 * delta afterwards would not do: by then the drag has started and the gesture is already lost.
 */
fun Modifier.ignoreEdgeHorizontalDrags(edgeWidthPx: Int): Modifier {
    if (edgeWidthPx <= 0) return this
    return pointerInput(edgeWidthPx) {
        val claimSlop = viewConfiguration.touchSlop / 2f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val fromEdge = down.position.x <= edgeWidthPx || down.position.x >= size.width - edgeWidthPx
            if (!fromEdge) return@awaitEachGesture

            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!claimed) {
                    val dx = abs(change.position.x - down.position.x)
                    val dy = abs(change.position.y - down.position.y)
                    // Horizontal intent, decided before the pager's slop would have been crossed.
                    if (dx > dy && dx > claimSlop) claimed = true
                }
                if (claimed) change.consume()
            }
        }
    }
}

/** [ignoreEdgeHorizontalDrags] sized from the window's own back-gesture strips. */
@Composable
fun Modifier.ignoreEdgeHorizontalDrags(): Modifier {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val edge = maxOf(gestures.getLeft(density, layoutDirection), gestures.getRight(density, layoutDirection))
    return ignoreEdgeHorizontalDrags(edge)
}
