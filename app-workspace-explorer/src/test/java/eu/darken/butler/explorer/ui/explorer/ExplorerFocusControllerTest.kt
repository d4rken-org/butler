package eu.darken.butler.explorer.ui.explorer

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerFocusControllerTest : BaseTest() {

    @Test
    fun `moves delegate to the wrap-around math`() = runTest {
        val controller = ExplorerFocusController()
        controller.updateItemCount(5)

        controller.moveDown()
        controller.focusedIndex.first() shouldBe 0

        controller.moveUp()
        controller.focusedIndex.first() shouldBe 4

        controller.moveUp()
        controller.focusedIndex.first() shouldBe 3

        controller.moveToFirst()
        controller.focusedIndex.first() shouldBe 0

        controller.moveToLast()
        controller.focusedIndex.first() shouldBe 4
    }

    @Test
    fun `grid moves use column width`() = runTest {
        val controller = ExplorerFocusController()
        controller.updateItemCount(9)

        controller.moveToLast()
        controller.moveLeft(gridColumns = 3)
        controller.focusedIndex.first() shouldBe 5

        controller.moveRight(gridColumns = 3)
        controller.focusedIndex.first() shouldBe 8
    }

    @Test
    fun `moves are no-ops without items`() = runTest {
        val controller = ExplorerFocusController()

        controller.moveDown()
        controller.moveUp()
        controller.focusedIndex.first() shouldBe null
    }

    @Test
    fun `item count shrink clamps the focused index`() = runTest {
        val controller = ExplorerFocusController()
        controller.updateItemCount(5)
        controller.moveToLast()
        controller.focusedIndex.first() shouldBe 4

        controller.updateItemCount(2)
        controller.focusedIndex.first() shouldBe 1

        controller.updateItemCount(0)
        controller.focusedIndex.first() shouldBe null
    }

    @Test
    fun `clear drops focus but keeps item count`() = runTest {
        val controller = ExplorerFocusController()
        controller.updateItemCount(3)
        controller.moveDown()

        controller.clear()
        controller.focusedIndex.first() shouldBe null

        // Count survives the clear: next move works against the same list size.
        controller.moveUp()
        controller.focusedIndex.first() shouldBe 2
    }
}
