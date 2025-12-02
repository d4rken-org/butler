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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DurationParserTest : BaseTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val parser by lazy { DurationParser(context) }

    @Test
    fun `parse days - singular`() {
        parser.parse("1 day") shouldBe 1.days
    }

    @Test
    fun `parse days - plural`() {
        parser.parse("30 days") shouldBe 30.days
    }

    @Test
    fun `parse hours - singular`() {
        parser.parse("1 hour") shouldBe 1.hours
    }

    @Test
    fun `parse hours - plural`() {
        parser.parse("24 hours") shouldBe 24.hours
    }

    @Test
    fun `parse with leading and trailing whitespace`() {
        parser.parse("  30 days  ") shouldBe 30.days
    }

    @Test
    fun `parse decimal rounds to nearest integer`() {
        parser.parse("2.5 days") shouldBe 3.days
        parser.parse("2.4 days") shouldBe 2.days
    }

    // Note: Decimal separators are locale-aware.
    // In English locale, only period is accepted as decimal separator.
    // In German locale, comma would be accepted instead.

    @Test
    fun `parse is case insensitive`() {
        parser.parse("30 DAYS") shouldBe 30.days
        parser.parse("30 Days") shouldBe 30.days
        parser.parse("24 HOURS") shouldBe 24.hours
    }

    @Test
    fun `parse zero days`() {
        parser.parse("0 days") shouldBe 0.days
    }

    @Test
    fun `parse large value`() {
        parser.parse("365 days") shouldBe 365.days
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
        parser.parse("30").shouldBeNull()
    }

    @Test
    fun `invalid input - only unit`() {
        parser.parse("days").shouldBeNull()
    }

    @Test
    fun `invalid input - unsupported unit`() {
        parser.parse("30 weeks").shouldBeNull()
        parser.parse("30 months").shouldBeNull()
    }

    @Test
    fun `invalid input - negative value`() {
        parser.parse("-5 days").shouldBeNull()
    }

    // Edge cases

    @Test
    fun `parse with multiple spaces between number and unit`() {
        parser.parse("30  days") shouldBe 30.days
    }

    @Test
    fun `parse with tab between number and unit`() {
        parser.parse("30\tdays") shouldBe 30.days
    }

    @Test
    fun `parse with Arabic numerals`() {
        // Arabic-Indic numerals: ٣٠ = 30
        parser.parse("٣٠ days") shouldBe 30.days
    }

    @Test
    fun `invalid input - comma not accepted in English locale`() {
        // In English locale, comma is the thousand separator, not decimal separator
        // The parser only accepts the locale's decimal separator (period in English)
        parser.parse("1,000 days").shouldBeNull()
    }
}
