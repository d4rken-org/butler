package eu.darken.butler.editor.ui.editor.text

import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SelectionHandleDragTest {

    private fun pos(line: Long, column: Int) = TextPosition(offset = 0L, line = line, column = column)

    @Nested
    inner class Ordering {
        @Test
        fun `same line orders by column`() {
            orderedSelection(pos(0, 20), pos(0, 10)) shouldBe (pos(0, 10) to pos(0, 20))
            orderedSelection(pos(0, 10), pos(0, 20)) shouldBe (pos(0, 10) to pos(0, 20))
        }

        @Test
        fun `different lines order by line, whatever the columns say`() {
            orderedSelection(pos(3, 0), pos(1, 99)) shouldBe (pos(1, 99) to pos(3, 0))
            orderedSelection(pos(1, 99), pos(3, 0)) shouldBe (pos(1, 99) to pos(3, 0))
        }

        @Test
        fun `equal positions keep the given order`() {
            orderedSelection(pos(2, 5), pos(2, 5)) shouldBe (pos(2, 5) to pos(2, 5))
        }
    }

    @Nested
    inner class Lifecycle {
        @Test
        fun `update before begin reports nothing to apply`() {
            SelectionDragCoordinator().updateEnd(pos(0, 5)).shouldBeNull()
            SelectionDragCoordinator().updateStart(pos(0, 5)).shouldBeNull()
        }

        @Test
        fun `end releases the anchor`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginEnd(pos(0, 10), pos(0, 15))
            coordinator.updateEnd(pos(0, 20)).shouldNotBeNull()
            coordinator.endEnd()
            coordinator.updateEnd(pos(0, 20)).shouldBeNull()
        }

        @Test
        fun `ending a gesture that never began is a no-op`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.endStart()
            coordinator.endEnd()

            coordinator.updateStart(pos(0, 5)).shouldBeNull()
            coordinator.updateEnd(pos(0, 5)).shouldBeNull()

            // The other handle keeps working normally afterwards
            coordinator.beginEnd(pos(0, 10), pos(0, 15))
            coordinator.updateEnd(pos(0, 20)) shouldBe (pos(0, 10) to pos(0, 20))
        }

        @Test
        fun `ending an inactive gesture leaves the running one alone`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginEnd(pos(0, 10), pos(0, 15))
            coordinator.updateEnd(pos(0, 20)) shouldBe (pos(0, 10) to pos(0, 20))

            coordinator.endStart()

            coordinator.updateEnd(pos(0, 30)) shouldBe (pos(0, 10) to pos(0, 30))
        }

        @Test
        fun `a finger resting on the anchor yields an empty but ordered pair`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginEnd(pos(1, 7), pos(1, 9))
            coordinator.updateEnd(pos(1, 7)) shouldBe (pos(1, 7) to pos(1, 7))
        }
    }

    @Nested
    inner class Dragging {
        @Test
        fun `without a crossover the anchor stays first and only the finger moves`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginEnd(pos(0, 10), pos(0, 40))

            coordinator.updateEnd(pos(0, 40)) shouldBe (pos(0, 10) to pos(0, 40))
            coordinator.updateEnd(pos(0, 30)) shouldBe (pos(0, 10) to pos(0, 30))
            coordinator.updateEnd(pos(0, 20)) shouldBe (pos(0, 10) to pos(0, 20))
        }

        @Test
        fun `the end handle dragged past the start keeps the same anchor afterwards`() {
            // The anchor is what the buggy version lost: once the pair swaps, it used to re-read
            // the moving endpoint and follow the finger from there.
            val coordinator = SelectionDragCoordinator()
            val anchor = pos(0, 10)
            coordinator.beginEnd(anchor, pos(0, 20))

            coordinator.updateEnd(pos(0, 20)) shouldBe (anchor to pos(0, 20))
            val afterCrossover = listOf(pos(0, 8), pos(0, 5), pos(0, 2)).map { coordinator.updateEnd(it)!! }

            afterCrossover shouldBe listOf(
                pos(0, 8) to anchor,
                pos(0, 5) to anchor,
                pos(0, 2) to anchor,
            )
        }

        @Test
        fun `the start handle dragged past the end keeps the same anchor afterwards`() {
            val coordinator = SelectionDragCoordinator()
            val anchor = pos(0, 60)
            coordinator.beginStart(pos(0, 30), anchor)

            coordinator.updateStart(pos(0, 30)) shouldBe (pos(0, 30) to anchor)
            val afterCrossover = listOf(pos(0, 70), pos(0, 80), pos(0, 90)).map { coordinator.updateStart(it)!! }

            afterCrossover shouldBe listOf(
                anchor to pos(0, 70),
                anchor to pos(0, 80),
                anchor to pos(0, 90),
            )
        }

        @Test
        fun `crossing over onto an earlier line keeps the anchor`() {
            val coordinator = SelectionDragCoordinator()
            val anchor = pos(4, 10)
            coordinator.beginEnd(anchor, pos(4, 30))

            coordinator.updateEnd(pos(4, 30)) shouldBe (anchor to pos(4, 30))
            // Upwards past the anchor line, even though the column is larger
            coordinator.updateEnd(pos(2, 90)) shouldBe (pos(2, 90) to anchor)
            coordinator.updateEnd(pos(1, 95)) shouldBe (pos(1, 95) to anchor)
        }

        @Test
        fun `crossing over onto a later line keeps the anchor`() {
            val coordinator = SelectionDragCoordinator()
            val anchor = pos(4, 30)
            coordinator.beginStart(pos(4, 10), anchor)

            coordinator.updateStart(pos(4, 10)) shouldBe (pos(4, 10) to anchor)
            // Downwards past the anchor line, even though the column is smaller
            coordinator.updateStart(pos(6, 0)) shouldBe (anchor to pos(6, 0))
            coordinator.updateStart(pos(7, 5)) shouldBe (anchor to pos(7, 5))
        }
    }

    @Nested
    inner class TwoFingers {
        @Test
        fun `an update reports the other finger's latest position, not where it started`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginStart(pos(0, 10), pos(0, 20))
            coordinator.beginEnd(pos(0, 10), pos(0, 20))

            coordinator.updateEnd(pos(0, 50)) shouldBe (pos(0, 10) to pos(0, 50))
            // The start finger pivots around 50, where the end finger is now - not around 20
            coordinator.updateStart(pos(0, 5)) shouldBe (pos(0, 5) to pos(0, 50))
        }

        @Test
        fun `alternating fingers accumulate both movements instead of undoing each other`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginStart(pos(0, 10), pos(0, 20))
            coordinator.beginEnd(pos(0, 10), pos(0, 20))

            val emitted = listOf(
                coordinator.updateStart(pos(0, 9))!!,
                coordinator.updateEnd(pos(0, 21))!!,
                coordinator.updateStart(pos(0, 8))!!,
                coordinator.updateEnd(pos(0, 22))!!,
                coordinator.updateStart(pos(0, 7))!!,
            )

            // Both endpoints only ever widen; a per-handle tracker would emit (9, 20) then
            // (10, 21) then (8, 20) - each event resetting whatever the other finger had done.
            emitted shouldBe listOf(
                pos(0, 9) to pos(0, 20),
                pos(0, 9) to pos(0, 21),
                pos(0, 8) to pos(0, 21),
                pos(0, 8) to pos(0, 22),
                pos(0, 7) to pos(0, 22),
            )
        }

        @Test
        fun `two fingers can cross each other over`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginStart(pos(0, 10), pos(0, 20))
            coordinator.beginEnd(pos(0, 10), pos(0, 20))

            coordinator.updateStart(pos(0, 40)) shouldBe (pos(0, 20) to pos(0, 40))
            coordinator.updateEnd(pos(0, 60)) shouldBe (pos(0, 40) to pos(0, 60))
            coordinator.updateStart(pos(0, 80)) shouldBe (pos(0, 60) to pos(0, 80))
        }

        @Test
        fun `the surviving finger pivots around where the lifted one finished`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginStart(pos(0, 10), pos(0, 20))
            coordinator.beginEnd(pos(0, 10), pos(0, 20))

            coordinator.updateEnd(pos(0, 50)) shouldBe (pos(0, 10) to pos(0, 50))
            coordinator.endEnd()

            // Pivots around 50, not around the 20 the end handle was at when the gesture began
            coordinator.updateStart(pos(0, 30)) shouldBe (pos(0, 30) to pos(0, 50))
            coordinator.updateStart(pos(0, 60)) shouldBe (pos(0, 50) to pos(0, 60))
        }

        @Test
        fun `lifting the start finger hands its final position to the end finger`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginStart(pos(0, 10), pos(0, 20))
            coordinator.beginEnd(pos(0, 10), pos(0, 20))

            coordinator.updateStart(pos(0, 2)) shouldBe (pos(0, 2) to pos(0, 20))
            coordinator.endStart()

            coordinator.updateEnd(pos(0, 30)) shouldBe (pos(0, 2) to pos(0, 30))
            coordinator.updateEnd(pos(0, 1)) shouldBe (pos(0, 1) to pos(0, 2))
        }

        @Test
        fun `a lifted finger stops contributing to the other's updates`() {
            val coordinator = SelectionDragCoordinator()
            coordinator.beginStart(pos(0, 10), pos(0, 20))
            coordinator.beginEnd(pos(0, 10), pos(0, 20))

            coordinator.updateEnd(pos(0, 50))
            coordinator.endEnd()

            coordinator.updateEnd(pos(0, 90)).shouldBeNull()
            coordinator.updateStart(pos(0, 30)) shouldBe (pos(0, 30) to pos(0, 50))
        }
    }
}
