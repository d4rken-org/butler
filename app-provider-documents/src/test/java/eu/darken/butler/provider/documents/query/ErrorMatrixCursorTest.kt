package eu.darken.butler.provider.documents.query

import android.provider.DocumentsContract
import eu.darken.butler.provider.documents.core.query.ErrorMatrixCursor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ErrorMatrixCursorTest {

    private val columns = arrayOf("_id", "name")

    @Test
    fun `getExtras returns Bundle with EXTRA_ERROR when error message set`() {
        val cursor = ErrorMatrixCursor(columns, "Root access required")

        val extras = cursor.extras

        extras shouldNotBe null
        extras.getString(DocumentsContract.EXTRA_ERROR) shouldBe "Root access required"
    }

    @Test
    fun `getExtras returns empty Bundle when error message is null`() {
        val cursor = ErrorMatrixCursor(columns, null)

        val extras = cursor.extras

        extras shouldNotBe null
        extras.getString(DocumentsContract.EXTRA_ERROR) shouldBe null
    }

    @Test
    fun `getExtras returns consistent Bundle across multiple calls`() {
        val cursor = ErrorMatrixCursor(columns, "Test error")

        val extras1 = cursor.extras
        val extras2 = cursor.extras

        extras1.getString(DocumentsContract.EXTRA_ERROR) shouldBe "Test error"
        extras2.getString(DocumentsContract.EXTRA_ERROR) shouldBe "Test error"
    }
}
