package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.MimeInfo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Pins [ViewerSupport] to what the viewer actually renders. Content arriving from another app is
 * copied under a MIME-derived name when this claims its type, so a type claimed here that the
 * viewer cannot classify would produce a renamed copy no renderer ever picks up.
 *
 * Archives stay out of it on purpose: the viewer classifies them by name into
 * [ViewerContent.Archive] and offers browsing, not rendering.
 */
class ViewerSupportTest : BaseTest() {

    /**
     * The types [ViewerWorkspace] branches on before falling through to
     * [ViewerContent.Unsupported]: APK, PDF and images.
     */
    private val classifiedTypes = listOf(
        "application/vnd.android.package-archive",
        "application/pdf",
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/svg+xml",
    )

    private val unclassifiedTypes = listOf(
        "text/plain",
        "application/json",
        "video/mp4",
        "audio/mpeg",
        "application/zip",
        "application/octet-stream",
    )

    /** An archive is browsed, not rendered, so it must never be treated as displayable content. */
    @Test
    fun `archives are not renderable`() {
        ViewerSupport.canDisplay(MimeInfo("application/zip")) shouldBe false
        ViewerSupport.canDisplay(MimeInfo("application/x-tar")) shouldBe false
        ViewerSupport.canDisplay(MimeInfo("application/gzip")) shouldBe false
    }

    @Test
    fun `every type the viewer classifies is offered`() {
        classifiedTypes.associateWith { ViewerSupport.canDisplay(MimeInfo(it)) } shouldBe
            classifiedTypes.associateWith { true }
    }

    @Test
    fun `types the viewer sends to the unsupported placeholder are not offered`() {
        unclassifiedTypes.associateWith { ViewerSupport.canDisplay(MimeInfo(it)) } shouldBe
            unclassifiedTypes.associateWith { false }
    }

    @Test
    fun `a name of the same kind needs no rewrite`() {
        ViewerSupport.hasMatchingName(MimeInfo("application/pdf"), "invoice.pdf") shouldBe true
        ViewerSupport.hasMatchingName(MimeInfo("image/png"), "photo.png") shouldBe true
        ViewerSupport.hasMatchingName(MimeInfo("image/png"), "photo.jpg") shouldBe true
        ViewerSupport.hasMatchingName(
            MimeInfo("application/vnd.android.package-archive"),
            "app.apk",
        ) shouldBe true
    }

    @Test
    fun `a name of another kind would reach the wrong renderer`() {
        ViewerSupport.hasMatchingName(MimeInfo("application/pdf"), "invoice.jpg") shouldBe false
        ViewerSupport.hasMatchingName(MimeInfo("image/png"), "scan.pdf") shouldBe false
        ViewerSupport.hasMatchingName(
            MimeInfo("application/vnd.android.package-archive"),
            "app.zip",
        ) shouldBe false
        ViewerSupport.hasMatchingName(MimeInfo("application/pdf"), "invoice") shouldBe false
    }

    @Test
    fun `a type the viewer cannot show never matches a name`() {
        ViewerSupport.hasMatchingName(MimeInfo("text/plain"), "notes.txt") shouldBe false
        ViewerSupport.hasMatchingName(MimeInfo("video/mp4"), "clip.mp4") shouldBe false
    }
}
