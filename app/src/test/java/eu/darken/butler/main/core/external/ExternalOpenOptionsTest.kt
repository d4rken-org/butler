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
    fun `small text can be edited or saved`() {
        computeExternalOpenOptions(MimeInfo("text/plain"), 1024L) shouldBe listOf(
            ExternalOpenOption.EDIT_AS_TEXT,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `text over the editor cap can only be saved`() {
        computeExternalOpenOptions(
            MimeInfo("text/plain"),
            PasteFileReader.MAX_PASTE_FILE_SIZE + 1,
        ) shouldBe listOf(ExternalOpenOption.SAVE_AS)
    }

    @Test
    fun `text exactly at the editor cap can still be edited`() {
        computeExternalOpenOptions(
            MimeInfo("text/plain"),
            PasteFileReader.MAX_PASTE_FILE_SIZE,
        ) shouldBe listOf(ExternalOpenOption.EDIT_AS_TEXT, ExternalOpenOption.SAVE_AS)
    }

    @Test
    fun `text of unknown size is offered for editing`() {
        computeExternalOpenOptions(MimeInfo("text/plain"), null) shouldBe listOf(
            ExternalOpenOption.EDIT_AS_TEXT,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `json counts as text`() {
        computeExternalOpenOptions(MimeInfo("application/json"), 512L) shouldBe listOf(
            ExternalOpenOption.EDIT_AS_TEXT,
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `unknown content can only be saved`() {
        computeExternalOpenOptions(MimeInfo("application/octet-stream"), 512L) shouldBe listOf(
            ExternalOpenOption.SAVE_AS,
        )
    }

    @Test
    fun `binary content can only be saved`() {
        computeExternalOpenOptions(MimeInfo("application/zip"), 512L) shouldBe listOf(
            ExternalOpenOption.SAVE_AS,
        )
    }
}
