package eu.darken.butler.common.files.preview

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PdfRenderSizeTest {

    @Test
    fun `a page smaller than the target is not upscaled by default`() {
        resolveRenderSize(
            pageWidth = 200,
            pageHeight = 100,
            targetPx = 1024,
            maxPx = PreviewBudget.MAX_DIM,
            allowUpscale = false,
        ) shouldBe (200 to 100)
    }

    @Test
    fun `upscaling reaches the requested edge`() {
        resolveRenderSize(
            pageWidth = 200,
            pageHeight = 100,
            targetPx = 2048,
            maxPx = 2048,
            allowUpscale = true,
        ) shouldBe (2048 to 1024)
    }

    @Test
    fun `a large page is scaled down to fit the target`() {
        resolveRenderSize(
            pageWidth = 2480,
            pageHeight = 3508,
            targetPx = 1024,
            maxPx = PreviewBudget.MAX_DIM,
            allowUpscale = false,
        ) shouldBe (723 to 1024)
    }

    @Test
    fun `maxPx clamps a target beyond the budget`() {
        val (width, height) = resolveRenderSize(
            pageWidth = 1000,
            pageHeight = 1000,
            targetPx = 999_999,
            maxPx = PreviewBudget.MAX_DIM,
            allowUpscale = true,
        )!!
        width shouldBe PreviewBudget.MAX_DIM
        height shouldBe PreviewBudget.MAX_DIM
    }

    @Test
    fun `an extreme aspect ratio keeps both edges at least one pixel`() {
        val (width, height) = resolveRenderSize(
            pageWidth = 1,
            pageHeight = 10_000,
            targetPx = 1024,
            maxPx = PreviewBudget.MAX_DIM,
            allowUpscale = false,
        )!!
        // The narrow edge rounds to 0 before clamping - a bitmap of width 0 cannot be allocated.
        width shouldBe 1
        height shouldBe PreviewBudget.MAX_DIM
    }

    @Test
    fun `a page without dimensions has no render size`() {
        resolveRenderSize(0, 100, 1024, PreviewBudget.MAX_DIM, allowUpscale = false) shouldBe null
        resolveRenderSize(100, 0, 1024, PreviewBudget.MAX_DIM, allowUpscale = false) shouldBe null
        resolveRenderSize(-1, -1, 1024, PreviewBudget.MAX_DIM, allowUpscale = false) shouldBe null
    }
}
