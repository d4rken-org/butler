package eu.darken.butler.explorer.ui.explorer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OpenDocumentTreeWithIntentTest : BaseTest() {

    @Test
    fun `createIntent returns input intent unchanged`() {
        val contract = OpenDocumentTreeWithIntent()
        val context = RuntimeEnvironment.getApplication()

        val inputIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://test/uri"))
            putExtra("custom_extra", "test_value")
        }

        val resultIntent = contract.createIntent(context, inputIntent)

        // Should return the exact same intent with all extras preserved
        resultIntent shouldBe inputIntent
        resultIntent.hasExtra("android.content.extra.SHOW_ADVANCED") shouldBe true
        resultIntent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI) shouldBe true
        resultIntent.getStringExtra("custom_extra") shouldBe "test_value"
    }

    @Test
    fun `parseResult returns URI on success`() {
        val contract = OpenDocumentTreeWithIntent()

        val expectedUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata")
        val resultIntent = Intent().apply {
            data = expectedUri
        }

        val result = contract.parseResult(Activity.RESULT_OK, resultIntent)

        result.shouldNotBeNull()
        result shouldBe expectedUri
    }

    @Test
    fun `parseResult returns null on cancellation`() {
        val contract = OpenDocumentTreeWithIntent()

        val result = contract.parseResult(Activity.RESULT_CANCELED, null)

        result.shouldBeNull()
    }

    @Test
    fun `parseResult returns null when intent is null on success`() {
        val contract = OpenDocumentTreeWithIntent()

        val result = contract.parseResult(Activity.RESULT_OK, null)

        result.shouldBeNull()
    }

    @Test
    fun `parseResult returns null when intent data is null`() {
        val contract = OpenDocumentTreeWithIntent()

        val resultIntent = Intent()  // No data set

        val result = contract.parseResult(Activity.RESULT_OK, resultIntent)

        result.shouldBeNull()
    }
}
