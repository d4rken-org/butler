package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * [PaddingValues] that resolves the floating bar stacks' content padding when it is asked for,
 * i.e. during measure, instead of when it is created.
 *
 * [FloatingBarStackState.contentPaddingPx] is derived from per-frame animated bar heights. Reading
 * it in a page's composable body invalidates that whole scope on every animation frame of a bar
 * collapse. Handing this instance to a lazy layout instead confines the animation to the layout
 * phase: the instance itself never changes, so nothing recomposes.
 */
@Stable
class FloatingBarContentPadding internal constructor(
    private val topStackState: FloatingBarStackState?,
    private val bottomStackState: FloatingBarStackState?,
    private val density: Density,
    private val start: Dp,
    private val end: Dp,
) : PaddingValues {

    override fun calculateTopPadding(): Dp = with(density) {
        (topStackState?.contentPaddingPx ?: 0f).toDp()
    }

    override fun calculateBottomPadding(): Dp = with(density) {
        (bottomStackState?.contentPaddingPx ?: 0f).toDp()
    }

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) start else end

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) end else start

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloatingBarContentPadding) return false
        return topStackState === other.topStackState &&
            bottomStackState === other.bottomStackState &&
            density == other.density &&
            start == other.start &&
            end == other.end
    }

    override fun hashCode(): Int {
        var result = topStackState?.hashCode() ?: 0
        result = 31 * result + (bottomStackState?.hashCode() ?: 0)
        result = 31 * result + density.hashCode()
        result = 31 * result + start.hashCode()
        result = 31 * result + end.hashCode()
        return result
    }
}

/**
 * Remembers a [FloatingBarContentPadding] for the given stacks.
 *
 * @param topStackState Stack supplying the top padding, or null for no top padding.
 * @param bottomStackState Stack supplying the bottom padding, or null for no bottom padding.
 * @param start Fixed horizontal padding at the layout start edge.
 * @param end Fixed horizontal padding at the layout end edge.
 */
@Composable
fun rememberFloatingBarContentPadding(
    topStackState: FloatingBarStackState? = null,
    bottomStackState: FloatingBarStackState? = null,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
): PaddingValues {
    val density = LocalDensity.current
    return remember(topStackState, bottomStackState, density, start, end) {
        FloatingBarContentPadding(topStackState, bottomStackState, density, start, end)
    }
}
