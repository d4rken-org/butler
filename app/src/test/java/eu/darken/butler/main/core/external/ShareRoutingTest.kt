package eu.darken.butler.main.core.external

import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

/** Robolectric because the routing is about [Uri]s, which do not exist on a plain JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareRoutingTest : BaseTest() {

    private val zip = Uri.parse("content://com.example.files/document/42")
    private val photo = Uri.parse("content://com.example.files/document/43")

    @Test
    fun `a single file goes to the arrival dialog`() {
        resolveShareRoute(text = null, subject = null, uris = listOf(zip)) shouldBe
            ShareRoute.SingleFile(zip, caption = null)
    }

    @Test
    fun `a file beats accompanying text, which rides along as the caption`() {
        resolveShareRoute(text = "look at this", subject = "Backup", uris = listOf(zip)) shouldBe
            ShareRoute.SingleFile(zip, caption = "look at this")
    }

    @Test
    fun `a styled caption is not dropped`() {
        // Messengers hand over the message as a Spanned, which getStringExtra reports as absent -
        // this is how MainActivity reads it, so the caption has to survive that path.
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, SpannableString("look at this") as CharSequence)
        }

        intent.getStringExtra(Intent.EXTRA_TEXT) shouldBe null

        resolveShareRoute(
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            subject = null,
            uris = listOf(zip),
        ) shouldBe ShareRoute.SingleFile(zip, caption = "look at this")
    }

    @Test
    fun `text without a file goes to the editor`() {
        resolveShareRoute(text = "notes", subject = null, uris = emptyList()) shouldBe
            ShareRoute.Text("notes", subject = null)
    }

    @Test
    fun `the subject survives a text-only share`() {
        resolveShareRoute(text = "notes", subject = "Shopping list", uris = emptyList()) shouldBe
            ShareRoute.Text("notes", subject = "Shopping list")
    }

    @Test
    fun `several files go straight to the saver`() {
        resolveShareRoute(text = null, subject = null, uris = listOf(zip, photo)) shouldBe
            ShareRoute.MultipleFiles(listOf(zip, photo))
    }

    @Test
    fun `several files keep beating accompanying text`() {
        resolveShareRoute(text = "look at these", subject = null, uris = listOf(zip, photo)) shouldBe
            ShareRoute.MultipleFiles(listOf(zip, photo))
    }

    @Test
    fun `an empty share is nothing to act on`() {
        resolveShareRoute(text = null, subject = null, uris = emptyList()) shouldBe ShareRoute.Nothing
        resolveShareRoute(text = null, subject = "Subject only", uris = emptyList()) shouldBe ShareRoute.Nothing
    }
}
