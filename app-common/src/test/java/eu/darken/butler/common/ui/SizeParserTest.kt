package eu.darken.butler.common.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SizeParserTest : BaseTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val parser by lazy { SizeParser(context) }

    @Test
    fun `parse bytes`() {
        parser.parse("1024 B") shouldBe 1024L
    }

    @Test
    fun `parse kilobytes`() {
        parser.parse("100 kB") shouldBe 100_000L
    }

    @Test
    fun `parse megabytes`() {
        parser.parse("500 MB") shouldBe 500_000_000L
    }

    @Test
    fun `parse gigabytes`() {
        parser.parse("1 GB") shouldBe 1_000_000_000L
    }

    @Test
    fun `parse terabytes`() {
        parser.parse("1 TB") shouldBe 1_000_000_000_000L
    }

    @Test
    fun `parse decimal megabytes`() {
        parser.parse("1.5 MB") shouldBe 1_500_000L
    }

    @Test
    fun `parse decimal gigabytes`() {
        parser.parse("2.5 GB") shouldBe 2_500_000_000L
    }

    @Test
    fun `parse with leading and trailing whitespace`() {
        parser.parse("  500 MB  ") shouldBe 500_000_000L
    }

    @Test
    fun `parse is case insensitive`() {
        parser.parse("500 mb") shouldBe 500_000_000L
        parser.parse("500 MB") shouldBe 500_000_000L
        parser.parse("1 gb") shouldBe 1_000_000_000L
    }

    @Test
    fun `parse zero`() {
        parser.parse("0 MB") shouldBe 0L
    }

    @Test
    fun `parse large value`() {
        parser.parse("10 GB") shouldBe 10_000_000_000L
    }

    @Test
    fun `invalid input - random text`() {
        parser.parse("abc").shouldBeNull()
    }

    @Test
    fun `invalid input - empty string`() {
        parser.parse("").shouldBeNull()
    }

    @Test
    fun `invalid input - only number`() {
        parser.parse("500").shouldBeNull()
    }

    @Test
    fun `invalid input - only unit`() {
        parser.parse("MB").shouldBeNull()
    }

    @Test
    fun `invalid input - unsupported unit`() {
        parser.parse("500 PB").shouldBeNull()
    }

    @Test
    fun `invalid input - negative value`() {
        parser.parse("-500 MB").shouldBeNull()
    }

    // Edge cases

    @Test
    fun `parse with multiple spaces between number and unit`() {
        parser.parse("500  MB") shouldBe 500_000_000L
    }

    @Test
    fun `parse with tab between number and unit`() {
        parser.parse("500\tMB") shouldBe 500_000_000L
    }

    @Test
    fun `parse with Arabic numerals`() {
        // Arabic-Indic numerals: ٥٠٠ = 500
        parser.parse("٥٠٠ MB") shouldBe 500_000_000L
    }

    // Note: Full locale support (including localized unit names like Russian "МБ")
    // requires instrumentation tests because Robolectric's Formatter.formatShortFileSize()
    // doesn't return fully localized unit names.
    //
    // Decimal separators are locale-aware:
    // - English: period is decimal separator (comma is thousand separator)
    // - German: comma is decimal separator (period is thousand separator)
    // In English locale, "1,5 GB" won't parse because comma is not accepted.
}
