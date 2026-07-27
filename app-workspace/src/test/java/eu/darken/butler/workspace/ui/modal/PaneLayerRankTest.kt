package eu.darken.butler.workspace.ui.modal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The stack order of a pane is entirely decided by these numbers, so the tier arithmetic is what
 * keeps a modal above the workspace it was launched from instead of behind it.
 */
class PaneLayerRankTest : BaseTest() {

    @Test
    fun `depth one matches the original fixed child ranks`() {
        PaneLayerRank.contentAt(1) shouldBe PaneLayerRank.CHILD_CONTENT
        PaneLayerRank.overlayAt(1) shouldBe PaneLayerRank.CHILD_OVERLAY
        PaneLayerRank.managerAt(1) shouldBe PaneLayerRank.CHILD_MANAGER
    }

    @Test
    fun `depth zero is the pane's own workspace`() {
        PaneLayerRank.contentAt(0) shouldBe PaneLayerRank.CONTENT
        PaneLayerRank.overlayAt(0) shouldBe PaneLayerRank.OVERLAY
        PaneLayerRank.managerAt(0) shouldBe PaneLayerRank.MANAGER
    }

    @Test
    fun `every tier stays strictly above the one below it`() {
        (0..3).forEach { depth ->
            val content = PaneLayerRank.contentAt(depth)
            val overlay = PaneLayerRank.overlayAt(depth)
            val manager = PaneLayerRank.managerAt(depth)

            (content < overlay) shouldBe true
            (overlay < manager) shouldBe true
            (manager < PaneLayerRank.contentAt(depth + 1)) shouldBe true
        }
    }

    @Test
    fun `a negative depth is rejected instead of stacking below the pane`() {
        shouldThrow<IllegalArgumentException> { PaneLayerRank.contentAt(-1) }
        shouldThrow<IllegalArgumentException> { PaneLayerRank.overlayAt(-1) }
        shouldThrow<IllegalArgumentException> { PaneLayerRank.managerAt(-1) }
    }
}
