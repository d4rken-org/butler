package eu.darken.butler.viewer.ui.viewer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Page stepping as pure arithmetic: null means "nothing to move to", which the ViewModel turns into
 * a no-op instead of re-rendering the page that is already there.
 */
class PdfNavTargetTest : BaseTest() {

    @Test
    fun `stepping forward and backward moves by one page`() {
        resolvePdfNavTarget(displayedIndex = 0, pageCount = 3, delta = 1) shouldBe 1
        resolvePdfNavTarget(displayedIndex = 2, pageCount = 3, delta = -1) shouldBe 1
    }

    @Test
    fun `stepping past either end does not move`() {
        resolvePdfNavTarget(displayedIndex = 0, pageCount = 3, delta = -1) shouldBe null
        resolvePdfNavTarget(displayedIndex = 2, pageCount = 3, delta = 1) shouldBe null
    }

    @Test
    fun `a single page document has nowhere to go`() {
        resolvePdfNavTarget(displayedIndex = 0, pageCount = 1, delta = 1) shouldBe null
        resolvePdfNavTarget(displayedIndex = 0, pageCount = 1, delta = -1) shouldBe null
    }

    @Test
    fun `a document without pages never moves`() {
        resolvePdfNavTarget(displayedIndex = 0, pageCount = 0, delta = 1) shouldBe null
        resolvePdfNavTarget(displayedIndex = 3, pageCount = -1, delta = -1) shouldBe null
    }

    @Test
    fun `an index beyond a shrunken document is clamped before the step`() {
        // The file changed underneath the viewer: the displayed index outlives the pages it named.
        resolvePdfNavTarget(displayedIndex = 99, pageCount = 3, delta = -1) shouldBe 1
        resolvePdfNavTarget(displayedIndex = 99, pageCount = 3, delta = 1) shouldBe 2
    }
}
