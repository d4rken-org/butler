package eu.darken.butler.common

import android.content.Context
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34], qualifiers = "en-rUS")
class TimeFormattingTest : BaseTest() {

    private val utc = TimeZone.getTimeZone("UTC")
    private val now = Instant.parse("2026-06-15T12:00:00Z")
    private val sameYear = Instant.parse("2026-08-02T14:23:00Z")
    private val priorYear = Instant.parse("2025-08-02T14:23:00Z")

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun smartTimeAgo(age: Duration): String = formatSmartTime(
        context = context,
        instant = now - age,
        reference = now,
    )

    @Test
    fun `same year with 24h shows time and no year`() {
        val result = formatDateCompact(
            timestamp = sameYear,
            now = now,
            zone = utc,
            locale = Locale.forLanguageTag("en-US"),
            is24Hour = true,
        )
        result shouldContain "14:23"
        result shouldNotContain "2026"
    }

    @Test
    fun `same year with 12h shows day period`() {
        val result = formatDateCompact(
            timestamp = sameYear,
            now = now,
            zone = utc,
            locale = Locale.forLanguageTag("en-US"),
            is24Hour = false,
        )
        result shouldContain "2:23"
        result.lowercase() shouldContain "pm"
        result shouldNotContain "2026"
    }

    @Test
    fun `other year shows year and no time`() {
        val result = formatDateCompact(
            timestamp = priorYear,
            now = now,
            zone = utc,
            locale = Locale.forLanguageTag("en-US"),
            is24Hour = true,
        )
        result shouldContain "2025"
        result shouldNotContain ":"
    }

    @Test
    fun `field order follows the locale`() {
        val english = formatDateCompact(
            timestamp = priorYear,
            now = now,
            zone = utc,
            locale = Locale.forLanguageTag("en-US"),
            is24Hour = true,
        )
        val japanese = formatDateCompact(
            timestamp = priorYear,
            now = now,
            zone = utc,
            locale = Locale.forLanguageTag("ja-JP"),
            is24Hour = true,
        )
        japanese shouldStartWith "2025"
        english shouldStartWith "Aug"
        english shouldNotBe japanese
    }

    @Test
    fun `elision follows the calendar year not a 365 day delta`() {
        val newYearsEve = formatDateCompact(
            timestamp = Instant.parse("2025-12-31T23:59:00Z"),
            now = Instant.parse("2026-01-01T00:01:00Z"),
            zone = utc,
            locale = Locale.forLanguageTag("en-US"),
            is24Hour = true,
        )
        newYearsEve shouldContain "2025"
        newYearsEve shouldNotContain ":"

        val almostAYearApart = formatDateCompact(
            timestamp = Instant.parse("2026-01-05T14:23:00Z"),
            now = Instant.parse("2026-12-30T12:00:00Z"),
            zone = utc,
            locale = Locale.forLanguageTag("en-US"),
            is24Hour = true,
        )
        almostAYearApart shouldContain "14:23"
        almostAYearApart shouldNotContain "2026"
    }

    @Test
    fun `year comparison uses the supplied zone`() {
        val timestamp = Instant.parse("2025-12-31T12:00:00Z")
        val reference = Instant.parse("2026-01-15T00:00:00Z")

        // UTC+14: the instant already falls into 2026 -> same year as the reference
        val kiritimati = formatDateCompact(
            timestamp = timestamp,
            now = reference,
            zone = TimeZone.getTimeZone("Pacific/Kiritimati"),
            locale = Locale.forLanguageTag("en-US"),
            is24Hour = true,
        )
        kiritimati shouldContain "02:00"
        kiritimati shouldNotContain "2026"

        // UTC-8: the instant is still 2025 -> other year than the reference
        val losAngeles = formatDateCompact(
            timestamp = timestamp,
            now = reference,
            zone = TimeZone.getTimeZone("America/Los_Angeles"),
            locale = Locale.forLanguageTag("en-US"),
            is24Hour = true,
        )
        losAngeles shouldContain "2025"
        losAngeles shouldNotContain ":"
    }

    @Test
    fun `locale calendars do not confuse the year comparison`() {
        val result = formatDateCompact(
            timestamp = sameYear,
            now = now,
            zone = utc,
            locale = Locale.forLanguageTag("th-TH"),
            is24Hour = true,
        )
        result.shouldNotBeEmpty()
        result shouldNotContain "2026"
        result shouldNotContain "2569"
    }

    @Test
    fun `all supported locales format both variants`() {
        val locales = listOf(
            "en-US", "de-DE", "fi-FI", "ru-RU", "tr-TR",
            "ja-JP", "zh-CN", "ko-KR", "ar-EG", "hi-IN", "th-TH",
        )
        locales.forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            val current = formatDateCompact(
                timestamp = sameYear,
                now = now,
                zone = utc,
                locale = locale,
                is24Hour = true,
            )
            val other = formatDateCompact(
                timestamp = priorYear,
                now = now,
                zone = utc,
                locale = locale,
                is24Hour = true,
            )
            current.shouldNotBeEmpty()
            other.shouldNotBeEmpty()
            current shouldNotBe other
        }
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
