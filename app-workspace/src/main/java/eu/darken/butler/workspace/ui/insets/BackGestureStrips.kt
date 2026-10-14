package eu.darken.butler.workspace.ui.insets

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges

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
fun edgeStripWidths(
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

/** [edgeStripWidths] sized from the window's own back-gesture strips. */
@Composable
fun PaneEdges.backGestureStripWidths(): Pair<Int, Int> {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    return edgeStripWidths(
        paneEdges = this,
        layoutDirection = layoutDirection,
        leftInsetPx = gestures.getLeft(density, layoutDirection),
        rightInsetPx = gestures.getRight(density, layoutDirection),
    )
}
