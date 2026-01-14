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
 * Behavior depends on [enabled]:
 * - When `enabled = true` (focused page): Scroll and fling propagate at boundaries only
 * - When `enabled = false` (unfocused page): ALL scroll and fling are blocked to prevent
 *   velocity leakage during pager swipes
 *
 * @param scrollState The ScrollState used by the horizontalScroll modifier on the same element
 * @param enabled When true, allows boundary propagation. When false, blocks ALL propagation
 *                (use false for non-focused pages in a pager)
 */
fun Modifier.propagateScrollAtBoundary(
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
            // When disabled (unfocused page), consume all fling to block propagation to pager.
            // This prevents velocity leakage from unfocused pages during pager swipes.
            if (!enabled) return Velocity(available.x, 0f)

            // If no horizontal scrolling is possible, don't interfere with pager at all
            if (scrollState.maxValue <= 0) return Velocity.Zero

            // When enabled (focused page), propagate fling at boundaries so the pager can
            // receive velocity for smooth page switching. Without this, the pager receives
            // scroll but no fling, making page switches difficult from horizontally
            // scrollable content.
            val atStart = scrollState.value == 0
            val atEnd = scrollState.value >= scrollState.maxValue

            val shouldPropagate = when {
                atStart && available.x > 0 -> true  // At start, flinging left
                atEnd && available.x < 0 -> true    // At end, flinging right
                else -> false
            }

            return if (shouldPropagate) {
                Velocity.Zero // Let fling propagate to pager
            } else {
                Velocity(available.x, 0f) // Consume to block propagation
            }
        }
    }

    return this.nestedScroll(connection)
}
