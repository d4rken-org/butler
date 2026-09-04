package eu.darken.butler.workspace.ui.insets

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.LocalAvoidDisplayCutout
import eu.darken.butler.common.compose.LocalSystemBarInsetsOverride
import eu.darken.butler.common.compose.systemBarsWithOptionalCutout
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.PersistBarCollapse
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges

/**
 * Unmasked system bar insets of the window, in physical (left/right) orientation.
 */
@Immutable
data class RawPaneInsets(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val left: Dp = 0.dp,
    val right: Dp = 0.dp,
)

/**
 * System bar insets a single workspace pane has to account for, already masked by the window edges
 * the pane actually touches. Left/right are physical, RTL is resolved.
 */
@Immutable
data class WorkspacePaneInsets(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val left: Dp = 0.dp,
    val right: Dp = 0.dp,
)

/**
 * Masks [raw] down to the edges this pane touches and resolves start/end to physical left/right.
 * Pure on purpose: this is the single place where the edge model turns into padding values.
 */
fun PaneEdges.resolve(raw: RawPaneInsets, layoutDirection: LayoutDirection): WorkspacePaneInsets {
    val startIsLeft = layoutDirection == LayoutDirection.Ltr
    val touchesLeft = if (startIsLeft) touchesStart else touchesEnd
    val touchesRight = if (startIsLeft) touchesEnd else touchesStart
    return WorkspacePaneInsets(
        top = if (touchesTop) raw.top else 0.dp,
        bottom = if (touchesBottom) raw.bottom else 0.dp,
        left = if (touchesLeft) raw.left else 0.dp,
        right = if (touchesRight) raw.right else 0.dp,
    )
}

/**
 * Whether a floating bar stack at [position] has to reserve room for a system bar.
 */
fun PaneEdges.includesSystemBarInset(position: BarPosition): Boolean = when (position) {
    BarPosition.TOP -> touchesTop
    BarPosition.BOTTOM -> touchesBottom
}

/**
 * The window's system bar insets, unmasked.
 *
 * Vertical values come from the status/navigation bar only (matching what pages have always used),
 * horizontal additionally covers display cutouts, which can occupy a screen side in landscape. The
 * cutout part is user-controlled via [LocalAvoidDisplayCutout].
 */
@Composable
private fun rawPaneInsets(): RawPaneInsets {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val horizontal = systemBarsWithOptionalCutout()
    // Null outside the screenshot renders, so production keeps reading the status and navigation
    // bars exactly as before rather than the systemBars union.
    val override = LocalSystemBarInsetsOverride.current
    return with(density) {
        RawPaneInsets(
            top = (override?.getTop(density) ?: WindowInsets.statusBars.getTop(density)).toDp(),
            bottom = (override?.getBottom(density) ?: WindowInsets.navigationBars.getBottom(density)).toDp(),
            left = horizontal.getLeft(density, layoutDirection).toDp(),
            right = horizontal.getRight(density, layoutDirection).toDp(),
        )
    }
}

/**
 * System bar insets for a pane, masked to the window edges it actually touches.
 */
@Composable
fun PaneEdges.paneInsets(): WorkspacePaneInsets = resolve(rawPaneInsets(), LocalLayoutDirection.current)

@Composable
fun WorkspaceDesign.paneInsets(): WorkspacePaneInsets = paneEdges.paneInsets()

/**
 * Window edges the enclosing pane touches, so pane-scoped chrome composed outside a page — dialogs,
 * sheets — can inset itself without every host threading the value through.
 *
 * Defaults to touching nothing, so the same components used outside a pane pad by nothing.
 */
val LocalPaneEdges = compositionLocalOf {
    PaneEdges(touchesTop = false, touchesBottom = false, touchesStart = false, touchesEnd = false)
}

/**
 * How much chrome of the app's own sits below the enclosing pane, e.g. the navigation rail in its
 * bottom placement. Non-zero only for the pane that would touch the bottom window edge if that
 * chrome weren't there — the keyboard reaches no other pane.
 *
 * Defaults to nothing, so a pane with the window edge below it behaves exactly as it did.
 */
val LocalPaneBottomChrome = compositionLocalOf { 0.dp }

/**
 * Pads content away from the horizontal system bars / cutouts its pane touches.
 *
 * Applied to what the user reads — page content, banners, the surface of a dialog or sheet — and
 * deliberately NOT to a pane's scrims and pointer barriers, which have to keep covering the full
 * pane. Inset those and the strip next to a side navigation bar stays undimmed, stays touchable and
 * falls outside the pane's press observer.
 *
 * Vertical insets stay page-controlled so lists keep scrolling under the status/navigation bar.
 *
 * Uses [windowInsetsPadding] with physical left/right values: [androidx.compose.foundation.layout.padding]
 * with start/end would resolve the layout direction a second time and swap the sides under RTL.
 */
@Composable
fun Modifier.paneHorizontalInsetPadding(edges: PaneEdges): Modifier {
    val insets = edges.paneInsets()
    val density = LocalDensity.current
    val horizontalInsets = remember(insets.left, insets.right, density) {
        horizontalWindowInsets(insets, density)
    }
    return windowInsetsPadding(horizontalInsets)
}

private fun horizontalWindowInsets(insets: WorkspacePaneInsets, density: Density): WindowInsets = with(density) {
    WindowInsets(
        left = insets.left.roundToPx(),
        top = 0,
        right = insets.right.roundToPx(),
        bottom = 0,
    )
}

/**
 * [rememberFloatingBarStackState] for a workspace pane: derives the system bar inset from the pane's
 * edges instead of taking a boolean, so a stack can't reserve room for a bar its pane doesn't touch.
 *
 * Pass [workspaceId] to have the stack's scroll-collapse state survive pane moves, swipes and
 * restarts - without it a restored list lands one row off, because a collapsed bar reserves less
 * content padding than an expanded one. Null keeps the stack ephemeral (previews, preview capture).
 */
@Composable
fun rememberPaneFloatingBarStackState(
    position: BarPosition,
    design: WorkspaceDesign,
    workspaceId: Workspace.Id? = null,
    defaultSpacing: Dp = 8.dp,
    edgePadding: Dp = 8.dp,
    contentPadding: Dp = 0.dp,
    includeImeInset: Boolean = false,
    estimatedContentPadding: Dp = Dp.Unspecified,
): FloatingBarStackState {
    val density = LocalDensity.current
    val bottomChrome = LocalPaneBottomChrome.current
    val state = rememberFloatingBarStackState(
        position = position,
        defaultSpacing = defaultSpacing,
        edgePadding = edgePadding,
        contentPadding = contentPadding,
        includeSystemBarInset = design.paneEdges.includesSystemBarInset(position),
        includeImeInset = includeImeInset,
        bottomChromePx = with(density) { bottomChrome.toPx() },
        estimatedContentPadding = estimatedContentPadding,
    )
    PersistBarCollapse(workspaceId = workspaceId, position = position, state = state)
    return state
}
