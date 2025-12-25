package eu.darken.butler.explorer.ui.explorer.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class FocusNavigationStateTest : BaseTest() {

    @Nested
    inner class MoveFocusUp {
        @Test
        fun `from null focuses last item`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 5)
            state.moveFocusUp().focusedIndex shouldBe 4
        }

        @Test
        fun `from first item wraps to last`() {
            val state = FocusNavigationState(focusedIndex = 0, itemCount = 5)
            state.moveFocusUp().focusedIndex shouldBe 4
        }

        @Test
        fun `from middle moves up`() {
            val state = FocusNavigationState(focusedIndex = 3, itemCount = 5)
            state.moveFocusUp().focusedIndex shouldBe 2
        }

        @Test
        fun `with empty items returns same state`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 0)
            state.moveFocusUp() shouldBe state
        }
    }

    @Nested
    inner class MoveFocusDown {
        @Test
        fun `from null focuses first item`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 5)
            state.moveFocusDown().focusedIndex shouldBe 0
        }

        @Test
        fun `from last item wraps to first`() {
            val state = FocusNavigationState(focusedIndex = 4, itemCount = 5)
            state.moveFocusDown().focusedIndex shouldBe 0
        }

        @Test
        fun `from middle moves down`() {
            val state = FocusNavigationState(focusedIndex = 2, itemCount = 5)
            state.moveFocusDown().focusedIndex shouldBe 3
        }

        @Test
        fun `with empty items returns same state`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 0)
            state.moveFocusDown() shouldBe state
        }
    }

    @Nested
    inner class MoveFocusLeft {
        @Test
        fun `from null focuses last item`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 12)
            state.moveFocusLeft(gridColumns = 3).focusedIndex shouldBe 11
        }

        @Test
        fun `from top row wraps to last`() {
            val state = FocusNavigationState(focusedIndex = 1, itemCount = 12)
            state.moveFocusLeft(gridColumns = 3).focusedIndex shouldBe 11
        }

        @Test
        fun `from middle moves up by column count`() {
            val state = FocusNavigationState(focusedIndex = 7, itemCount = 12)
            state.moveFocusLeft(gridColumns = 3).focusedIndex shouldBe 4
        }

        @Test
        fun `with empty items returns same state`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 0)
            state.moveFocusLeft(gridColumns = 3) shouldBe state
        }
    }

    @Nested
    inner class MoveFocusRight {
        @Test
        fun `from null focuses first item`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 12)
            state.moveFocusRight(gridColumns = 3).focusedIndex shouldBe 0
        }

        @Test
        fun `from bottom row wraps to first`() {
            val state = FocusNavigationState(focusedIndex = 10, itemCount = 12)
            state.moveFocusRight(gridColumns = 3).focusedIndex shouldBe 0
        }

        @Test
        fun `from middle moves down by column count`() {
            val state = FocusNavigationState(focusedIndex = 4, itemCount = 12)
            state.moveFocusRight(gridColumns = 3).focusedIndex shouldBe 7
        }

        @Test
        fun `wraps to first when near end of list`() {
            // Index 5 in a 7-item list with 3 columns is in the "last row" (indices 4, 5, 6)
            // Moving right should wrap to 0 for consistency with other wrap behaviors
            val state = FocusNavigationState(focusedIndex = 5, itemCount = 7)
            state.moveFocusRight(gridColumns = 3).focusedIndex shouldBe 0
        }

        @Test
        fun `with empty items returns same state`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 0)
            state.moveFocusRight(gridColumns = 3) shouldBe state
        }
    }

    @Nested
    inner class MoveFocusToFirst {
        @Test
        fun `focuses first item`() {
            val state = FocusNavigationState(focusedIndex = 5, itemCount = 10)
            state.moveFocusToFirst().focusedIndex shouldBe 0
        }

        @Test
        fun `with empty items returns same state`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 0)
            state.moveFocusToFirst() shouldBe state
        }
    }

    @Nested
    inner class MoveFocusToLast {
        @Test
        fun `focuses last item`() {
            val state = FocusNavigationState(focusedIndex = 2, itemCount = 10)
            state.moveFocusToLast().focusedIndex shouldBe 9
        }

        @Test
        fun `with empty items returns same state`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 0)
            state.moveFocusToLast() shouldBe state
        }
    }

    @Nested
    inner class ClearFocus {
        @Test
        fun `clears focused index`() {
            val state = FocusNavigationState(focusedIndex = 5, itemCount = 10)
            state.clearFocus().focusedIndex shouldBe null
        }

        @Test
        fun `preserves item count`() {
            val state = FocusNavigationState(focusedIndex = 5, itemCount = 10)
            state.clearFocus().itemCount shouldBe 10
        }
    }

    @Nested
    inner class UpdateItemCount {
        @Test
        fun `clears focus when items become empty`() {
            val state = FocusNavigationState(focusedIndex = 5, itemCount = 10)
            state.updateItemCount(0).focusedIndex shouldBe null
        }

        @Test
        fun `adjusts focus to last when out of bounds`() {
            val state = FocusNavigationState(focusedIndex = 8, itemCount = 10)
            state.updateItemCount(5).focusedIndex shouldBe 4
        }

        @Test
        fun `preserves focus when still in bounds`() {
            val state = FocusNavigationState(focusedIndex = 3, itemCount = 10)
            state.updateItemCount(8).focusedIndex shouldBe 3
        }

        @Test
        fun `preserves null focus`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 10)
            state.updateItemCount(5).focusedIndex shouldBe null
        }

        @Test
        fun `updates item count`() {
            val state = FocusNavigationState(focusedIndex = 3, itemCount = 10)
            state.updateItemCount(20).itemCount shouldBe 20
        }
    }

    @Nested
    inner class HasFocus {
        @Test
        fun `returns true when focused`() {
            val state = FocusNavigationState(focusedIndex = 5, itemCount = 10)
            state.hasFocus shouldBe true
        }

        @Test
        fun `returns false when not focused`() {
            val state = FocusNavigationState(focusedIndex = null, itemCount = 10)
            state.hasFocus shouldBe false
        }
    }
}
