package eu.darken.butler.common.files.preview

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PreviewBudgetTest {

    @Test
    fun `unknown request falls back to default`() {
        PreviewBudget.resolveEdge(0) shouldBe PreviewBudget.DEFAULT_TARGET
        PreviewBudget.resolveEdge(-10) shouldBe PreviewBudget.DEFAULT_TARGET
    }

    @Test
    fun `oversized request is capped at max`() {
        PreviewBudget.resolveEdge(999_999) shouldBe PreviewBudget.MAX_DIM
        PreviewBudget.resolveEdge(999_999, max = PreviewBudget.MAX_ICON_DIM) shouldBe PreviewBudget.MAX_ICON_DIM
    }

    @Test
    fun `in-range request passes through`() {
        PreviewBudget.resolveEdge(200) shouldBe 200
    }

    @Test
    fun `default is clamped by a smaller max (icon path)`() {
        // Unknown size on the icon path: default (384) must still be capped to MAX_ICON_DIM (256).
        PreviewBudget.resolveEdge(0, max = PreviewBudget.MAX_ICON_DIM) shouldBe PreviewBudget.MAX_ICON_DIM
    }
}
