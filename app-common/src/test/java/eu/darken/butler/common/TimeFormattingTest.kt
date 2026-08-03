package eu.darken.butler.common

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Config(application = TestApplication::class, sdk = [34], qualifiers = "en-rUS")
class TimeFormattingTest : ComposeTest() {

    private val utc = TimeZone.getTimeZone("UTC")
    private val timestamp = Instant.parse("2026-08-02T14:23:45.123Z")
    private val now = Instant.parse("2026-06-15T12:00:00Z")
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

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun smartTimeAgo(age: Duration): String = formatSmartTime(
        context = context,
        instant = now - age,
        reference = now,
    )

    @Test
    fun `compact style drops the century and the seconds`() {
        format(DateTimeStyle.COMPACT) shouldBe "02.08.26 14:23"
    }

    @Test
    fun `full style keeps the century and the seconds`() {
        format(DateTimeStyle.FULL) shouldBe "02.08.2026 14:23:45"
    }

    @Test
    fun `detailed style carries a month name, seconds and milliseconds`() {
        val detailed = format(DateTimeStyle.DETAILED)
        detailed shouldContain "2026"
        detailed shouldContain "Aug"
        detailed shouldContain "14:23:45"
        detailed shouldContain "123"
    }

    @Test
    fun `date only styles carry no time`() {
        format(DateTimeStyle.DATE_NUMERIC) shouldBe "02.08.2026"

        val textual = format(DateTimeStyle.DATE_TEXTUAL)
        textual shouldContain "2026"
        textual shouldContain "Aug"
        textual shouldNotContain "14"
        textual shouldNotContain "23"
    }

    @Test
    fun `months render numerically for the numeric styles`() {
        listOf(DateTimeStyle.COMPACT, DateTimeStyle.FULL, DateTimeStyle.DATE_NUMERIC).forEach { style ->
            format(style, locale = english) shouldNotContain "Aug"
            format(style, locale = german) shouldNotContain "Aug"
        }
    }

    @Test
    fun `field order follows the locale`() {
        format(DateTimeStyle.COMPACT, locale = english) shouldBe "08/02/26 14:23"
        format(DateTimeStyle.COMPACT, locale = german) shouldBe "02.08.26 14:23"
        format(DateTimeStyle.FULL, locale = Locale.forLanguageTag("ja-JP")) shouldStartWith "2026"
    }

    @Test
    fun `twelve hour preference is honoured`() {
        val compact = format(DateTimeStyle.COMPACT, locale = english, is24Hour = false)
        compact shouldContain "2:23"
        compact.lowercase() shouldContain "pm"
        compact shouldNotContain "2:23:45"

        val full = format(DateTimeStyle.FULL, locale = english, is24Hour = false)
        full shouldContain "2:23:45"
        full.lowercase() shouldContain "pm"

        val detailed = format(DateTimeStyle.DETAILED, locale = english, is24Hour = false)
        detailed shouldContain "2:23:45"
        detailed shouldContain "123"
        detailed.lowercase() shouldContain "pm"
        detailed shouldNotContain "14:23"
    }

    @Test
    fun `detailed style uses the locale's own date-time glue`() {
        // The two-call assembly would drop the Finnish "klo" ("o'clock") connector entirely
        format(DateTimeStyle.DETAILED, locale = Locale.forLanguageTag("fi-FI")) shouldContain "klo"
    }

    @Test
    fun `the fractional separator comes from the locale`() {
        format(DateTimeStyle.DETAILED, locale = english) shouldContain ".123"
        format(DateTimeStyle.DETAILED, locale = german) shouldContain ",123"
        // ar-EG uses the Arabic decimal separator, never a dot or a comma
        val arabic = format(DateTimeStyle.DETAILED, locale = Locale.forLanguageTag("ar-EG"))
        arabic shouldContain "٫"
    }

    @Test
    fun `rendering uses the supplied zone`() {
        format(DateTimeStyle.FULL, zone = TimeZone.getTimeZone("America/Los_Angeles")) shouldBe
                "02.08.2026 07:23:45"
        format(DateTimeStyle.FULL, zone = TimeZone.getTimeZone("Pacific/Kiritimati")) shouldBe
                "03.08.2026 04:23:45"
    }

    @Test
    fun `locale calendars keep their own era`() {
        // Thai renders the Buddhist year, which is what a th-TH user expects to read
        format(DateTimeStyle.FULL, locale = Locale.forLanguageTag("th-TH")) shouldContain "2569"
    }

    @Test
    fun `all supported locales format every style`() {
        val locales = listOf(
            "en-US", "de-DE", "fi-FI", "ru-RU", "tr-TR",
            "ja-JP", "zh-CN", "ko-KR", "ar-EG", "hi-IN", "th-TH",
        )
        locales.forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            val rendered = DateTimeStyle.entries.map { format(it, locale = locale) }
            rendered.forEach { it.shouldNotBeEmpty() }
            rendered.toSet().size shouldBe DateTimeStyle.entries.size
        }
    }

    @Test
    fun `relative time re-renders when the reference moves`() {
        var reference by mutableStateOf(timestamp + 1.minutes)
        val rendered = mutableListOf<String>()

        composeTestRule.setContent {
            rendered += formatRelativeTime(instant = timestamp, reference = reference)
        }
        composeTestRule.waitForIdle()

        reference = timestamp + 3.hours
        composeTestRule.waitForIdle()

        rendered.last() shouldNotBe rendered.first()
    }

    @Test
    fun `smart time stays in hours past a day`() {
        val result = smartTimeAgo(39.hours)

        result shouldContain "39"
        result shouldContain "hour"
        result shouldNotContain "day"
    }

    @Test
    fun `smart time stays in hours just below four days`() {
        val result = smartTimeAgo(95.hours + 59.minutes)

        result shouldContain "95"
        result shouldContain "hour"
        result shouldNotContain "day"
    }

    @Test
    fun `smart time switches to days at exactly four days`() {
        val result = smartTimeAgo(4.days)

        result shouldContain "4"
        result shouldContain "day"
        result shouldNotContain "hour"
    }

    @Test
    fun `smart time shows days below the absolute threshold`() {
        val result = smartTimeAgo(5.days)

        result shouldContain "5"
        result shouldContain "day"
        result shouldNotContain "hour"
    }

    @Test
    fun `smart time switches to an absolute date at exactly seven days`() {
        val result = smartTimeAgo(7.days)

        result shouldContain "2026"
        result shouldNotContain "ago"
    }

    @Test
    fun `relative time keeps its 24 hour default for other callers`() {
        val result = formatRelativeTime(
            context = context,
            instant = now - 39.hours,
            reference = now,
        )

        result shouldContain "day"
        result shouldNotContain "hour"
    }
}
