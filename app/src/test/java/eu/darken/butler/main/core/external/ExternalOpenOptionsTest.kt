package eu.darken.butler.main.core.external

import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.editor.core.PasteFileReader
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExternalOpenOptionsTest : BaseTest() {

    @Test
    fun `images can be viewed or saved`() {
        computeExternalOpenOptions(MimeInfo("image/png"), 1024L) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `pdfs can be viewed or saved but not edited`() {
        computeExternalOpenOptions(MimeInfo("application/pdf"), 1024L) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `apks can be viewed or saved`() {
        computeExternalOpenOptions(
            MimeInfo("application/vnd.android.package-archive"),
            1024L,
        ) shouldBe listOf(ExternalOpenOption.VIEW, ExternalOpenOption.SAVE_AS)
    }

    @Test
    fun `content with a real location can be shown in the explorer`() {
        computeExternalOpenOptions(
            MimeInfo("image/png"),
            1024L,
            hasContainingFolder = true,
        ) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.SHOW_IN_EXPLORER,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `content that only exists behind a provider has no folder to show`() {
        computeExternalOpenOptions(
            MimeInfo("image/png"),
            1024L,
            hasContainingFolder = false,
        ) shouldBe listOf(ExternalOpenOption.VIEW, ExternalOpenOption.SAVE_AS)
    }

    @Test
    fun `archives with a real location offer view, explorer and save`() {
        computeExternalOpenOptions(
            MimeInfo("application/zip"),
            1024L,
            hasContainingFolder = true,
        ) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.SHOW_IN_EXPLORER,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `small text can be viewed, edited or saved`() {
        computeExternalOpenOptions(MimeInfo("text/plain"), 1024L) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.EDIT_AS_TEXT,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `text over the editor cap can be viewed or saved`() {
        computeExternalOpenOptions(
            MimeInfo("text/plain"),
            PasteFileReader.MAX_PASTE_FILE_SIZE + 1,
        ) shouldBe listOf(ExternalOpenOption.VIEW, ExternalOpenOption.SAVE_AS)
    }

    @Test
    fun `text exactly at the editor cap can still be edited`() {
        computeExternalOpenOptions(
            MimeInfo("text/plain"),
            PasteFileReader.MAX_PASTE_FILE_SIZE,
        ) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.EDIT_AS_TEXT,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `text of unknown size is offered for editing`() {
        computeExternalOpenOptions(MimeInfo("text/plain"), null) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.EDIT_AS_TEXT,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `json counts as text`() {
        computeExternalOpenOptions(MimeInfo("application/json"), 512L) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.EDIT_AS_TEXT,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `yaml arriving without a declared type counts as text`() {
        computeExternalOpenOptions(MimeInfo.fromFileName("notes.yaml"), 1024L) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.EDIT_AS_TEXT,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `yaml declared by the sending app counts as text`() {
        computeExternalOpenOptions(MimeInfo("application/yaml"), 1024L) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.EDIT_AS_TEXT,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `unknown content can be viewed or saved`() {
        computeExternalOpenOptions(MimeInfo("application/octet-stream"), 512L) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `archives can be viewed or saved`() {
        computeExternalOpenOptions(MimeInfo("application/zip"), 512L) shouldBe listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.SAVE_AS,
        )
    }

    /**
     * The Viewer explains what it cannot render, so nothing arrives without a view action - a media
     * type Butler has no renderer for included.
     */
    @Test
    fun `every arrival is offered for viewing`() {
        val types = listOf(
            "image/png",
            "application/pdf",
            "application/vnd.android.package-archive",
            "text/plain",
            "application/json",
            "application/zip",
            "application/x-tar",
            "application/octet-stream",
            "video/mp4",
            "audio/mpeg",
        )
        types.associateWith { computeExternalOpenOptions(MimeInfo(it), 512L).first() } shouldBe
            types.associateWith { ExternalOpenOption.VIEW }
    }
}
