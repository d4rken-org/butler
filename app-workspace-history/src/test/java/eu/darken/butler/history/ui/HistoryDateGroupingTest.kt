package eu.darken.butler.history.ui

import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.BaseTest
import java.time.ZoneId
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The "Today"/"Yesterday" headers are calendar-day labels, so they have to be resolved in the
 * user's zone. The classifier takes the zone as a parameter so these cases never have to mutate
 * the JVM-wide default.
 */
class HistoryDateGroupingTest : BaseTest() {

    private val losAngeles = ZoneId.of("America/Los_Angeles")

    private fun entry(id: String, completedAt: Instant) = HistoryEntry(
        id = id,
        kind = Operation.Metadata.Kind.COPY,
        intent = null,
        originType = HistoryEntry.OriginType.EXPLORER,
        originWorkspaceId = "ws",
        title = "Copy",
        description = "Copy",
        summary = null,
        startedAt = completedAt - 1.seconds,
        completedAt = completedAt,
        duration = 1.seconds,
        outcome = HistoryOutcome.COMPLETED,
        errorMessage = null,
        errorClass = null,
        affectedPathsCount = 1,
        partialErrorCount = 0,
        pathsTruncated = false,
        paths = emptyList(),
    )

    private fun group(now: Instant, vararg entries: HistoryEntry, zone: ZoneId = losAngeles) =
        HistoryWorkspaceViewModel.groupByDate(entries.toList(), now, zone)
            .associate { it.key to it.entries.map { entry -> entry.id } }

    @Test
    fun `local midnight, not UTC midnight, separates today from yesterday`() {
        // 00:30 on 2026-08-02 in Los Angeles is 07:30 UTC on the same date
        val now = Instant.parse("2026-08-02T07:30:00Z")
        // 23:30 the previous local evening - the same UTC day as `now`, but yesterday locally
        val lastNight = Instant.parse("2026-08-02T06:30:00Z")
        // 00:10 this local morning
        val thisMorning = Instant.parse("2026-08-02T07:10:00Z")

        group(now, entry("last-night", lastNight), entry("this-morning", thisMorning)) shouldBe mapOf(
            HistoryWorkspaceViewModel.GroupKey.TODAY to listOf("this-morning"),
            HistoryWorkspaceViewModel.GroupKey.YESTERDAY to listOf("last-night"),
        )
    }

    @Test
    fun `UTC day arithmetic would have mislabelled the evening entry`() {
        // The same pair, bucketed by epochMillis / 86_400_000: both land on UTC day 2026-08-02, so
        // the old code called both of them "Today".
        val now = Instant.parse("2026-08-02T07:30:00Z")
        val lastNight = Instant.parse("2026-08-02T06:30:00Z")
        val dayMs = 86_400_000L
        (now.toEpochMilliseconds() / dayMs) shouldBe (lastNight.toEpochMilliseconds() / dayMs)
    }

    @Test
    fun `a DST transition does not shift the day boundaries`() {
        // Los Angeles springs forward at 02:00 local on 2026-03-08, making that day 23 hours long
        val now = Instant.parse("2026-03-09T19:00:00Z") // 12:00 local, Monday 2026-03-09
        val duringShortDay = Instant.parse("2026-03-08T20:00:00Z") // 13:00 local, Sunday 2026-03-08
        val beforeShortDay = Instant.parse("2026-03-07T21:00:00Z") // 13:00 local, Saturday 2026-03-07

        group(
            now,
            entry("sunday", duringShortDay),
            entry("saturday", beforeShortDay),
        ) shouldBe mapOf(
            HistoryWorkspaceViewModel.GroupKey.YESTERDAY to listOf("sunday"),
            HistoryWorkspaceViewModel.GroupKey.THIS_WEEK to listOf("saturday"),
        )
    }

    @Test
    fun `the older buckets follow calendar days too`() {
        val now = Instant.parse("2026-08-02T07:30:00Z") // 2026-08-02 00:30 local
        val threeDaysAgo = Instant.parse("2026-07-30T18:00:00Z") // 2026-07-30 local
        val tenDaysAgo = Instant.parse("2026-07-23T18:00:00Z") // 2026-07-23 local
        val ancient = Instant.parse("2026-01-01T18:00:00Z")

        group(
            now,
            entry("recent", threeDaysAgo),
            entry("older", tenDaysAgo),
            entry("ancient", ancient),
        ) shouldBe mapOf(
            HistoryWorkspaceViewModel.GroupKey.THIS_WEEK to listOf("recent"),
            HistoryWorkspaceViewModel.GroupKey.THIS_MONTH to listOf("older"),
            HistoryWorkspaceViewModel.GroupKey.OLDER to listOf("ancient"),
        )
    }

    @Test
    fun `classifying does not touch the global default zone`() {
        val before = TimeZone.getDefault()

        group(Instant.parse("2026-08-02T07:30:00Z"), entry("a", Instant.parse("2026-08-02T06:30:00Z")))

        TimeZone.getDefault() shouldBe before
    }
}
