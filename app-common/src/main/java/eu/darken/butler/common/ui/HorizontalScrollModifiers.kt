package eu.darken.butler.common.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Modifier that blocks horizontal scroll propagation to parent scrollables (like HorizontalPager)
 * EXCEPT when the scroll reaches its boundary - then further scrolling propagates to the parent.
 *
 * Use this on horizontally scrollable content inside a HorizontalPager to prevent accidental
 * page changes while still allowing natural page swiping when the content is fully scrolled.
 *
 * Note: Fling velocity is always consumed (not propagated) to prevent "intensified" page flings.
 * When scrolling this content to its boundary and continuing to scroll, the fling velocity
 * includes the entire gesture history. Propagating this to the pager causes violent flings
 * and can skip pages when multiple pages have this modifier (intermediate pages compound velocity).
 *
 * @param scrollState The ScrollState used by the horizontalScroll modifier on the same element
 * @param enabled When false, this modifier is a no-op (useful for non-focused pages in a pager)
 */
fun Modifier.propagateScrollAtBoundary(
    scrollState: ScrollState,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this

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
            // available.x > 0 means scrolling left (trying to go to start)
            // available.x < 0 means scrolling right (trying to go to end)
            val atStart = scrollState.value == 0
            val atEnd = scrollState.value >= scrollState.maxValue

            val shouldPropagate = when {
                // At start and trying to scroll further left -> propagate to pager
                atStart && available.x > 0 -> true
                // At end and trying to scroll further right -> propagate to pager
                atEnd && available.x < 0 -> true
                // Otherwise block propagation
                else -> false
            }

            return if (shouldPropagate) {
                Offset.Zero // Let it propagate to parent (pager)
            } else {
                Offset(available.x, 0f) // Consume it to block propagation
            }
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // Always consume fling velocity to prevent "intensified" pager flings.
            // The pager calculates its own fling from its gesture tracking.
            // Propagating velocity here adds this content's scrolling velocity to the pager,
            // causing violent page flings and page skipping with multiple pages using this modifier.
            return Velocity(available.x, 0f)
        }
    }

    return this.nestedScroll(connection)
}
