package eu.darken.butler.viewer.ui.viewer

import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.viewer.core.ViewerContent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ViewerToolbarCollapseTest : BaseTest() {

    @Test
    fun `the zoom is only read once telephoto has laid out`() {
        isZoomedIn(transformationSpecified = false, userZoom = 4f) shouldBe false
    }

    @Test
    fun `resting at fit does not count as zoomed in`() {
        isZoomedIn(transformationSpecified = true, userZoom = 1f) shouldBe false
        isZoomedIn(transformationSpecified = true, userZoom = 1.005f) shouldBe false
    }

    @Test
    fun `zooming past the threshold counts as zoomed in`() {
        isZoomedIn(transformationSpecified = true, userZoom = 1.5f) shouldBe true
    }

    @Test
    fun `a zoomed image collapses the toolbar`() {
        shouldCollapseToolbar(ViewerContent.Image(MimeInfo("image/jpeg")), isZoomedIn = true) shouldBe true
        shouldCollapseToolbar(ViewerContent.Image(MimeInfo("image/jpeg")), isZoomedIn = false) shouldBe false
    }

    @Test
    fun `a zoomed pdf page collapses the toolbar`() {
        val pdf = ViewerContent.PdfPreview(MimeInfo("application/pdf"), pageCount = 3)
        shouldCollapseToolbar(pdf, isZoomedIn = true) shouldBe true
        shouldCollapseToolbar(pdf, isZoomedIn = false) shouldBe false
    }

    @Test
    fun `losing the image expands the toolbar again`() {
        // Telephoto keeps its transformation after the image leaves - without the content gate the
        // toolbar would stay collapsed with no gesture surface left to expand it.
        shouldCollapseToolbar(ViewerContent.Failed(IllegalStateException("gone")), isZoomedIn = true) shouldBe false
        shouldCollapseToolbar(ViewerContent.Unsupported(MimeInfo("application/pdf")), isZoomedIn = true) shouldBe false
        shouldCollapseToolbar(ViewerContent.Loading, isZoomedIn = true) shouldBe false
    }
}
