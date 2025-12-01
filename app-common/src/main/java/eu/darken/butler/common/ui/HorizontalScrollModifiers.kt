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
 * @param scrollState The ScrollState used by the horizontalScroll modifier on the same element
 */
fun Modifier.propagateScrollAtBoundary(scrollState: ScrollState): Modifier {
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
            val atStart = scrollState.value == 0
            val atEnd = scrollState.value >= scrollState.maxValue

            val shouldPropagate = when {
                atStart && available.x > 0 -> true
                atEnd && available.x < 0 -> true
                else -> false
            }

            return if (shouldPropagate) {
                Velocity.Zero
            } else {
                Velocity(available.x, 0f)
            }
        }
    }

    return this.nestedScroll(connection)
}
