package eu.darken.butler.common.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Horizontal scrolling for content living inside a HorizontalPager.
 *
 * Combines [blockHorizontalScrollPropagation] with [horizontalScroll], and only claims drag
 * gestures while there is actually something to scroll.
 *
 * That last part matters: [horizontalScroll] claims horizontal drags even when the content fits
 * ([ScrollState.maxValue] is 0), then forwards the unusable delta to the pager as *nested* scroll.
 * The pager changes page, but its own drag handler never runs, so no DragInteraction is emitted
 * and the swipe is invisible to anything driven off `pagerState.interactionSource` - which is how
 * swiping across a short breadcrumb, info bar or text buffer silently failed to trigger the
 * trailing-placeholder workspace creation. Leaving the gesture unclaimed lets the pager handle it
 * directly and emit Start/Stop as normal.
 *
 * The [ScrollState.viewportSize] check is not redundant: [ScrollState.maxValue] starts out at
 * [Int.MAX_VALUE] to mean "not measured yet", so testing it alone would claim the gesture for the
 * first frame even on content that turns out to be short. Gating on a measured viewport instead
 * treats "unknown" as "not scrollable"; the layout node keeps measuring either way, so scrolling
 * switches back on as soon as the content really does overflow.
 *
 * @param scrollState backing state for the scroll
 * @param isWorkspaceFocused false for a non-focused page in a pager; blocks propagation
 *                           unconditionally to prevent velocity leakage during pager swipes
 */
fun Modifier.pagerFriendlyHorizontalScroll(
    scrollState: ScrollState,
    isWorkspaceFocused: Boolean,
): Modifier = this
    .blockHorizontalScrollPropagation(scrollState, enabled = isWorkspaceFocused)
    .horizontalScroll(scrollState, enabled = scrollState.viewportSize > 0 && scrollState.maxValue > 0)

/**
 * Modifier that blocks horizontal scroll propagation to parent scrollables (like HorizontalPager).
 *
 * Use this on horizontally scrollable content inside a HorizontalPager to prevent accidental page
 * changes while scrolling the content. Reaching the content's scroll boundary does NOT hand the
 * gesture over to the pager - switching pages requires a new gesture somewhere else.
 *
 * Content that can't scroll at all ([ScrollState.maxValue] is 0) is left alone, so a short
 * breadcrumb trail or info bar doesn't become a dead zone for page swipes.
 *
 * Reporting the leftover delta as consumed also hides it from the content's own overscroll effect,
 * so there is no edge stretch at the boundary - the scroll just stops.
 *
 * @param scrollState The ScrollState used by the horizontalScroll modifier on the same element
 * @param enabled When false (non-focused page in a pager), blocks propagation unconditionally to
 *                prevent velocity leakage during pager swipes
 */
fun Modifier.blockHorizontalScrollPropagation(
    scrollState: ScrollState,
    enabled: Boolean = true,
): Modifier {
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Don't consume - let horizontalScroll handle it first
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            // When disabled, consume ALL horizontal scroll to block propagation to pager.
            // This prevents unfocused pages' horizontal scrolls from leaking velocity
            // during pager swipe gestures.
            if (!enabled) return Offset(available.x, 0f)

            // If no horizontal scrolling is possible, don't interfere with pager at all
            if (scrollState.maxValue <= 0) return Offset.Zero

            return Offset(available.x, 0f)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // When disabled (unfocused page), consume all fling to block propagation to pager.
            // This prevents velocity leakage from unfocused pages during pager swipes.
            if (!enabled) return Velocity(available.x, 0f)

            // If no horizontal scrolling is possible, don't interfere with pager at all
            if (scrollState.maxValue <= 0) return Velocity.Zero

            return Velocity(available.x, 0f)
        }
    }

    return this.nestedScroll(connection)
}
