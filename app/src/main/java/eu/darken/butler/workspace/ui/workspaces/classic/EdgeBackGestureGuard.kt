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
import androidx.compose.ui.unit.LayoutDirection
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges
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
fun Modifier.ignoreEdgeHorizontalDrags(leftPx: Int, rightPx: Int): Modifier {
    if (leftPx <= 0 && rightPx <= 0) return this
    return pointerInput(leftPx, rightPx) {
        val claimSlop = viewConfiguration.touchSlop / 2f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val fromLeft = leftPx > 0 && down.position.x < leftPx
            val fromRight = rightPx > 0 && down.position.x >= size.width - rightPx
            if (!fromLeft && !fromRight) return@awaitEachGesture

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

/**
 * The strip widths for a pane, as (left, right) in pixels. Each strip the pane reaches is as wide
 * as the wider of the two gesture insets; a side the pane does not reach has none, because with the
 * rail at the start the pane's start edge sits next to the rail, where no system back gesture begins.
 *
 * ```
 * insets (left=40, right=60)
 * LTR, no rail:     all edges                          -> (60, 60)
 * LTR, start rail:  touchesStart=false, touchesEnd=true -> (0, 60)
 * RTL, start rail:  touchesStart=false, touchesEnd=true -> (60, 0)
 * ```
 */
internal fun edgeStripWidths(
    paneEdges: PaneEdges,
    layoutDirection: LayoutDirection,
    leftInsetPx: Int,
    rightInsetPx: Int,
): Pair<Int, Int> {
    val width = maxOf(leftInsetPx, rightInsetPx)
    val touchesLeft = if (layoutDirection == LayoutDirection.Ltr) paneEdges.touchesStart else paneEdges.touchesEnd
    val touchesRight = if (layoutDirection == LayoutDirection.Ltr) paneEdges.touchesEnd else paneEdges.touchesStart
    return (if (touchesLeft) width else 0) to (if (touchesRight) width else 0)
}

/** [ignoreEdgeHorizontalDrags] sized from the window's own back-gesture strips, on the edges [paneEdges] reaches. */
@Composable
fun Modifier.ignoreEdgeHorizontalDrags(paneEdges: PaneEdges): Modifier {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val (left, right) = edgeStripWidths(
        paneEdges = paneEdges,
        layoutDirection = layoutDirection,
        leftInsetPx = gestures.getLeft(density, layoutDirection),
        rightInsetPx = gestures.getRight(density, layoutDirection),
    )
    return ignoreEdgeHorizontalDrags(left, right)
}
