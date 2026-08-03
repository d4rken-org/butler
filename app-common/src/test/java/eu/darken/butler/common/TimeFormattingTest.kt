package eu.darken.butler.common

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class TimeFormattingTest : BaseTest() {

    private val utc = TimeZone.getTimeZone("UTC")
    private val timestamp = Instant.parse("2026-08-02T14:23:45Z")
    private val german = Locale.forLanguageTag("de-DE")
    private val english = Locale.forLanguageTag("en-US")

    private fun format(
        style: DateTimeStyle,
        locale: Locale = german,
        is24Hour: Boolean = true,
        zone: TimeZone = utc,
    ) = formatDateTime(
        timestamp = timestamp,
        zone = zone,
        locale = locale,
        is24Hour = is24Hour,
        style = style,
    )

    @Test
    fun `short style drops the century and the seconds`() {
        format(DateTimeStyle.SHORT) shouldBe "02.08.26 14:23"
    }

    @Test
    fun `long style keeps the century and the seconds`() {
        format(DateTimeStyle.LONG) shouldBe "02.08.2026 14:23:45"
    }

    @Test
    fun `months render numerically`() {
        listOf(DateTimeStyle.SHORT, DateTimeStyle.LONG).forEach { style ->
            format(style, locale = english) shouldNotContain "Aug"
            format(style, locale = german) shouldNotContain "Aug"
        }
    }

    @Test
    fun `field order follows the locale`() {
        format(DateTimeStyle.SHORT, locale = english) shouldBe "08/02/26 14:23"
        format(DateTimeStyle.SHORT, locale = german) shouldBe "02.08.26 14:23"
        format(DateTimeStyle.LONG, locale = Locale.forLanguageTag("ja-JP")) shouldStartWith "2026"
    }

    @Test
    fun `twelve hour preference is honoured`() {
        val short = format(DateTimeStyle.SHORT, locale = english, is24Hour = false)
        short shouldContain "2:23"
        short.lowercase() shouldContain "pm"
        short shouldNotContain "2:23:45"

        val long = format(DateTimeStyle.LONG, locale = english, is24Hour = false)
        long shouldContain "2:23:45"
        long.lowercase() shouldContain "pm"
    }

    @Test
    fun `rendering uses the supplied zone`() {
        format(DateTimeStyle.LONG, zone = TimeZone.getTimeZone("America/Los_Angeles")) shouldBe
                "02.08.2026 07:23:45"
        format(DateTimeStyle.LONG, zone = TimeZone.getTimeZone("Pacific/Kiritimati")) shouldBe
                "03.08.2026 04:23:45"
    }

    @Test
    fun `locale calendars keep their own era`() {
        // Thai renders the Buddhist year, which is what a th-TH user expects to read
        format(DateTimeStyle.LONG, locale = Locale.forLanguageTag("th-TH")) shouldContain "2569"
    }

    @Test
    fun `all supported locales format both styles`() {
        val locales = listOf(
            "en-US", "de-DE", "fi-FI", "ru-RU", "tr-TR",
            "ja-JP", "zh-CN", "ko-KR", "ar-EG", "hi-IN", "th-TH",
        )
        locales.forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            val short = format(DateTimeStyle.SHORT, locale = locale)
            val long = format(DateTimeStyle.LONG, locale = locale)
            short.shouldNotBeEmpty()
            long.shouldNotBeEmpty()
            short shouldNotBe long
        }
    }
}
