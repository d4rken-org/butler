package eu.darken.butler.main.core.external

import android.net.Uri
import eu.darken.butler.common.files.MimeInfo
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

/**
 * The arrival is logged on every step it takes through the dialog, and a caption is text the sender
 * wrote rather than Butler's data. Only its presence may reach the log a bug report ships.
 *
 * Robolectric because the arrival is built around a `content://` [Uri].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalOpenStateRedactionTest : BaseTest() {

    private val uri = Uri.parse("content://com.example.files/document/42")
    private val caption = "my bank pin is 1234"

    private fun state(caption: String?) = ExternalOpenState(
        ref = SourceRef.Content(uri),
        originalUri = uri,
        displayName = "backup.zip",
        sizeBytes = 4096L,
        mime = MimeInfo("application/zip"),
        callerPackage = "com.example.files",
        options = listOf(ExternalOpenOption.VIEW, ExternalOpenOption.SAVE_AS),
        caption = caption,
    )

    @Test
    fun `the caption is not printed`() {
        state(caption).toString() shouldNotContain caption
        state(caption).toString() shouldContain "caption=<present>"
    }

    @Test
    fun `an arrival without a caption says so`() {
        state(null).toString() shouldContain "caption=null"
    }

    @Test
    fun `everything else stays diagnosable`() {
        val printed = state(caption).toString()

        printed shouldContain "backup.zip"
        printed shouldContain uri.toString()
        printed shouldContain "com.example.files"
        printed shouldContain "application/zip"
        printed shouldContain "4096"
        printed shouldContain "VIEW"
    }
}
