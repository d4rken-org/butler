package eu.darken.butler.common.files.local.operations.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests for PerformanceHistory - performance tracking with time-based retention.
 *
 * Critical tests verify:
 * - Samples inside the recent window survive compaction verbatim
 * - Older samples are thinned evenly and stay chronological
 * - The first and the last sample are always retained
 * - Total samples never exceed MAX_SAMPLES (1000)
 */
class PerformanceHistoryTest : BaseTest() {

    /** The most a compaction can leave behind: a full recent tail plus a full overview. */
    private val retentionBound = PerformanceHistory.RECENT_TARGET +
        PerformanceHistory.OVERVIEW_BUCKETS * PerformanceHistory.SAMPLES_PER_OVERVIEW_BUCKET + 1

    private fun samples(
        count: Int,
        spacing: Duration,
        startTime: Instant = Instant.fromEpochMilliseconds(1000),
    ) = (0 until count).map { i ->
        PerformanceSample(
            timestamp = startTime + spacing * i,
            bytesPerSecond = (i + 1) * 1_000_000L,
            itemsPerSecond = (i + 1).toFloat(),
            totalBytesProcessed = (i + 1) * 1_000L,
            totalItemsProcessed = i + 1,
        )
    }

    private fun historyOf(samples: List<PerformanceSample>, totalBytes: Long = 0L, totalItems: Int = 0) =
        samples.fold(PerformanceHistory()) { history, sample ->
            history.addSample(sample, totalBytes = totalBytes, totalItems = totalItems)
        }

    // ============ BASIC FUNCTIONALITY ============

    @Test
    fun `empty history has no samples`() {
        val history = PerformanceHistory()

        history.samples shouldHaveSize 0
        history.startTime shouldBe null
        history.totalBytes shouldBe 0L
        history.totalItems shouldBe 0
        history.averageBytesPerSecond shouldBe 0L
        history.averageItemsPerSecond shouldBe 0f
        history.peakBytesPerSecond shouldBe 0L
        history.duration shouldBe null
    }

    @Test
    fun `adding first sample initializes history`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        val sample = PerformanceSample(
            timestamp = startTime,
            bytesPerSecond = 1_000_000L,
            itemsPerSecond = 10f,
            totalBytesProcessed = 100_000L,
            totalItemsProcessed = 5
        )

        val history = PerformanceHistory()
            .addSample(sample, totalBytes = 10_000_000L, totalItems = 100)

        history.samples shouldHaveSize 1
        history.samples.first() shouldBe sample
        history.startTime shouldBe startTime
        history.totalBytes shouldBe 10_000_000L
        history.totalItems shouldBe 100
    }

    @Test
    fun `adding multiple samples builds list`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        repeat(10) { i ->
            val sample = PerformanceSample(
                timestamp = startTime + (i * 100).milliseconds,
                bytesPerSecond = 1_000_000L,
                itemsPerSecond = 10f,
                totalBytesProcessed = (i + 1) * 100_000L,
                totalItemsProcessed = i + 1
            )
            history = history.addSample(sample, totalBytes = 1_000_000L, totalItems = 10)
        }

        history.samples shouldHaveSize 10
        history.startTime shouldBe startTime
    }

    @Test
    fun `totalBytes and totalItems can increase but never decrease`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // First sample sets initial totals
        history = history.addSample(
            PerformanceSample(startTime, 1_000_000L, 10f, 100_000L, 1),
            totalBytes = 5_000_000L,
            totalItems = 50
        )

        history.totalBytes shouldBe 5_000_000L
        history.totalItems shouldBe 50

        // Subsequent samples with higher totals should increase them
        history = history.addSample(
            PerformanceSample(startTime + 100.milliseconds, 1_000_000L, 10f, 200_000L, 2),
            totalBytes = 999_999_999L,  // Higher value - should be accepted
            totalItems = 999            // Higher value - should be accepted
        )

        history.totalBytes shouldBe 999_999_999L
        history.totalItems shouldBe 999
    }

    // ============ CALCULATED PROPERTIES ============

    @Test
    fun `averageBytesPerSecond calculates correctly`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        history = history.addSample(
            PerformanceSample(startTime, 1_000_000L, 10f, 100_000L, 1),
            totalBytes = 1_000_000L,
            totalItems = 10
        )
        history = history.addSample(
            PerformanceSample(startTime + 100.milliseconds, 2_000_000L, 20f, 200_000L, 2),
            totalBytes = 1_000_000L,
            totalItems = 10
        )
        history = history.addSample(
            PerformanceSample(startTime + 200.milliseconds, 3_000_000L, 30f, 300_000L, 3),
            totalBytes = 1_000_000L,
            totalItems = 10
        )

        // Average of 1M, 2M, 3M = 2M
        history.averageBytesPerSecond shouldBe 2_000_000L
    }

    @Test
    fun `averageItemsPerSecond calculates correctly`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        history = history.addSample(
            PerformanceSample(startTime, 1_000_000L, 10f, 100_000L, 1),
            totalBytes = 1_000_000L,
            totalItems = 10
        )
        history = history.addSample(
            PerformanceSample(startTime + 100.milliseconds, 2_000_000L, 20f, 200_000L, 2),
            totalBytes = 1_000_000L,
            totalItems = 10
        )
        history = history.addSample(
            PerformanceSample(startTime + 200.milliseconds, 3_000_000L, 30f, 300_000L, 3),
            totalBytes = 1_000_000L,
            totalItems = 10
        )

        // Average of 10, 20, 30 = 20
        history.averageItemsPerSecond shouldBe 20f
    }

    @Test
    fun `peakBytesPerSecond finds maximum`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        history = history.addSample(
            PerformanceSample(startTime, 1_000_000L, 10f, 100_000L, 1),
            totalBytes = 1_000_000L,
            totalItems = 10
        )
        history = history.addSample(
            PerformanceSample(startTime + 100.milliseconds, 5_000_000L, 20f, 200_000L, 2),
            totalBytes = 1_000_000L,
            totalItems = 10
        )
        history = history.addSample(
            PerformanceSample(startTime + 200.milliseconds, 3_000_000L, 30f, 300_000L, 3),
            totalBytes = 1_000_000L,
            totalItems = 10
        )

        history.peakBytesPerSecond shouldBe 5_000_000L
    }

    @Test
    fun `duration calculates from start to last sample`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        history = history.addSample(
            PerformanceSample(startTime, 1_000_000L, 10f, 100_000L, 1),
            totalBytes = 1_000_000L,
            totalItems = 10
        )
        history = history.addSample(
            PerformanceSample(startTime + 5.seconds, 2_000_000L, 20f, 500_000L, 5),
            totalBytes = 1_000_000L,
            totalItems = 10
        )

        history.duration shouldBe 5.seconds
    }

    @Test
    fun `empty history calculated properties return zero`() {
        val history = PerformanceHistory()

        history.averageBytesPerSecond shouldBe 0L
        history.averageItemsPerSecond shouldBe 0f
        history.peakBytesPerSecond shouldBe 0L
        history.duration shouldBe null
    }

    // ============ RECENT SPEED CALCULATIONS ============

    @Test
    fun `getRecentBytesPerSecond with no samples returns 0`() {
        val history = PerformanceHistory()

        history.getRecentBytesPerSecond() shouldBe 0L
        history.getRecentBytesPerSecond(10) shouldBe 0L
    }

    @Test
    fun `getRecentItemsPerSecond with no samples returns 0`() {
        val history = PerformanceHistory()

        history.getRecentItemsPerSecond() shouldBe 0f
        history.getRecentItemsPerSecond(10) shouldBe 0f
    }

    @Test
    fun `getRecentBytesPerSecond with fewer samples than window uses all samples`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Add only 10 samples
        repeat(10) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 100).milliseconds,
                    bytesPerSecond = (i + 1) * 1_000_000L,  // 1M, 2M, 3M, ..., 10M
                    itemsPerSecond = (i + 1) * 10f,
                    totalBytesProcessed = (i + 1) * 100_000L,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = 1_000_000L,
                totalItems = 100
            )
        }

        // Request last 30 samples, but only 10 exist - should use all 10
        // Average of 1M, 2M, 3M, ..., 10M = 5.5M
        val recentSpeed = history.getRecentBytesPerSecond(30)
        recentSpeed shouldBe 5_500_000L
    }

    @Test
    fun `getRecentItemsPerSecond with fewer samples than window uses all samples`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Add only 5 samples
        repeat(5) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 100).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = (i + 1) * 10f,  // 10, 20, 30, 40, 50
                    totalBytesProcessed = (i + 1) * 100_000L,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = 1_000_000L,
                totalItems = 100
            )
        }

        // Request last 30 samples, but only 5 exist - should use all 5
        // Average of 10, 20, 30, 40, 50 = 30
        val recentSpeed = history.getRecentItemsPerSecond(30)
        recentSpeed shouldBe 30f
    }

    @Test
    fun `getRecentBytesPerSecond with more samples than window uses only recent samples`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Add 100 samples
        repeat(100) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 100).milliseconds,
                    bytesPerSecond = (i + 1) * 1_000_000L,  // 1M, 2M, ..., 100M
                    itemsPerSecond = (i + 1) * 10f,
                    totalBytesProcessed = (i + 1) * 100_000L,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = 10_000_000L,
                totalItems = 100
            )
        }

        // Request last 10 samples - should use samples 91-100
        // Average of 91M, 92M, ..., 100M = 95.5M
        val recentSpeed = history.getRecentBytesPerSecond(10)
        recentSpeed shouldBe 95_500_000L
    }

    @Test
    fun `getRecentItemsPerSecond with more samples than window uses only recent samples`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Add 50 samples
        repeat(50) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 100).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = (i + 1) * 10f,  // 10, 20, ..., 500
                    totalBytesProcessed = (i + 1) * 100_000L,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = 5_000_000L,
                totalItems = 50
            )
        }

        // Request last 5 samples - should use samples 46-50
        // Average of 460, 470, 480, 490, 500 = 480
        val recentSpeed = history.getRecentItemsPerSecond(5)
        recentSpeed shouldBe 480f
    }

    @Test
    fun `getRecentBytesPerSecond default window is 30 samples`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Add 100 samples with constant speed
        repeat(100) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 100).milliseconds,
                    bytesPerSecond = if (i < 70) 5_000_000L else 10_000_000L,  // Speed changes at sample 70
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * 100_000L,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = 10_000_000L,
                totalItems = 100
            )
        }

        // Default (last 30 samples) should be 10M (samples 71-100)
        val recentSpeed = history.getRecentBytesPerSecond()
        recentSpeed shouldBe 10_000_000L

        // Explicit window of 50 samples should be average of 5M (samples 1-50) and 10M (samples 51-100)
        val longerWindowSpeed = history.getRecentBytesPerSecond(50)
        // 20 samples at 5M + 30 samples at 10M = average ~8M
        longerWindowSpeed shouldBeGreaterThan 7_000_000L
        longerWindowSpeed shouldBeLessThan 9_000_000L
    }

    @Test
    fun `getRecentItemsPerSecond default window is 30 samples`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Add 100 samples with speed change
        repeat(100) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 100).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = if (i < 70) 50f else 100f,  // Speed changes at sample 70
                    totalBytesProcessed = (i + 1) * 100_000L,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = 10_000_000L,
                totalItems = 100
            )
        }

        // Default (last 30 samples) should be 100 (samples 71-100)
        val recentSpeed = history.getRecentItemsPerSecond()
        recentSpeed shouldBe 100f
    }

    @Test
    fun `getRecentBytesPerSecond matches overall average when all samples requested`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Add 50 samples
        repeat(50) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 100).milliseconds,
                    bytesPerSecond = (i + 1) * 1_000_000L,
                    itemsPerSecond = (i + 1) * 10f,
                    totalBytesProcessed = (i + 1) * 100_000L,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = 5_000_000L,
                totalItems = 50
            )
        }

        // When window >= total samples, should match overall average
        val recentSpeed = history.getRecentBytesPerSecond(100)
        val overallSpeed = history.averageBytesPerSecond

        recentSpeed shouldBe overallSpeed
    }

    // ============ NO COMPACTION (UNDER LIMIT) ============

    @Test
    fun `500 samples do not trigger downsampling`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_000_000_000L

        repeat(500) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 500),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 500
            )
        }

        history.samples shouldHaveSize 500
    }

    @Test
    fun `exactly 1000 samples do not trigger downsampling`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_000_000_000L

        repeat(1000) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 1000),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 1000
            )
        }

        history.samples shouldHaveSize 1000
    }

    @Test
    fun `samples under limit maintain chronological order`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_000_000L

        repeat(100) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 100),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 100
            )
        }

        // Verify chronological order
        history.samples.zipWithNext().forEach { (prev, next) ->
            (prev.timestamp < next.timestamp) shouldBe true
        }
    }

    // ============ CRITICAL COMPACTION TESTS ============

    @Test
    fun `1500 samples trigger downsampling to under 1000`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_500_000_000L

        repeat(1500) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 1500),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 1500
            )
        }

        history.samples.size shouldBeLessThanOrEqual 1000
        history.samples.size shouldBeGreaterThan 0
    }

    @Test
    fun `samples inside the recent window survive compaction verbatim`() {
        val added = samples(count = 1001, spacing = 250.milliseconds)
        val history = historyOf(added, totalBytes = 1_001_000L, totalItems = 1001)

        val cutoff = added.last().timestamp - PerformanceHistory.RECENT_WINDOW
        val expected = added.filter { it.timestamp >= cutoff }

        expected shouldHaveSize 481
        history.samples.filter { it.timestamp >= cutoff } shouldBe expected
        history.samples shouldHaveSize 721
    }

    @Test
    fun `the first sample survives repeated compactions`() {
        val added = samples(count = 5000, spacing = 250.milliseconds)
        val history = historyOf(added, totalBytes = 5_000_000L, totalItems = 5000)

        history.samples.first() shouldBe added.first()
        history.samples.size shouldBeLessThanOrEqual PerformanceHistory.MAX_SAMPLES
    }

    @Test
    fun `the newest samples survive compaction untouched`() {
        val added = samples(count = 2000, spacing = 250.milliseconds)
        val history = historyOf(added, totalBytes = 2_000_000L, totalItems = 2000)

        history.samples.takeLast(100) shouldBe added.takeLast(100)
    }

    @Test
    fun `older samples are thinned but stay chronological`() {
        val added = samples(count = 1001, spacing = 250.milliseconds)
        val history = historyOf(added, totalBytes = 1_001_000L, totalItems = 1001)

        val cutoff = added.last().timestamp - PerformanceHistory.RECENT_WINDOW
        val older = history.samples.filter { it.timestamp < cutoff }

        older shouldHaveSize 240
        older.size shouldBeLessThan added.count { it.timestamp < cutoff }
        older.first() shouldBe added.first()
        history.samples.zipWithNext().forEach { (prev, next) ->
            (prev.timestamp < next.timestamp) shouldBe true
        }
    }

    @Test
    fun `older samples are evenly spaced within their bucket`() {
        val added = samples(count = 1001, spacing = 250.milliseconds)
        val history = historyOf(added, totalBytes = 1_001_000L, totalItems = 1001)

        val cutoff = added.last().timestamp - PerformanceHistory.RECENT_WINDOW
        val older = history.samples.filter { it.timestamp < cutoff }
        val span = older.last().timestamp - older.first().timestamp

        val buckets = older.groupBy { sample ->
            val position = (sample.timestamp - older.first().timestamp) / span
            (position * PerformanceHistory.OVERVIEW_BUCKETS)
                .toInt()
                .coerceAtMost(PerformanceHistory.OVERVIEW_BUCKETS - 1)
        }

        buckets.keys shouldHaveSize PerformanceHistory.OVERVIEW_BUCKETS
        buckets.values.forEach { it shouldHaveSize PerformanceHistory.SAMPLES_PER_OVERVIEW_BUCKET }
    }

    @Test
    fun `chronological order preserved after downsampling`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_500_000_000L

        repeat(1500) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 1500),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 1500
            )
        }

        // Verify chronological order after downsampling
        history.samples.zipWithNext().forEach { (prev, next) ->
            (prev.timestamp < next.timestamp) shouldBe true
        }
    }

    @Test
    fun `max samples limit is never exceeded`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 5_000_000_000L

        // Try to add 5000 samples - should downsample to ≤ 1000
        repeat(5000) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 5000),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 5000
            )

            // At no point should samples exceed 1000
            history.samples.size shouldBeLessThanOrEqual 1000
        }

        history.samples.size shouldBeLessThanOrEqual 1000
        println("Final sample count: ${history.samples.size}")
    }

    @Test
    fun `every sampling cadence compacts inside the retention bound`() {
        // One add past the cap, so each case is measured right after a compaction
        val sizes = listOf(10.milliseconds, 250.milliseconds, 1.seconds).map { spacing ->
            historyOf(samples(count = 1001, spacing = spacing)).samples.size
        }

        // A fast cadence fits entirely inside the recent window, a slow one spills into the overview
        sizes shouldBe listOf(500, 721, 361)
        sizes.forEach { it shouldBeLessThanOrEqual retentionBound }
    }

    @Test
    fun `compaction is amortized - one compaction leaves room to grow`() {
        val history = historyOf(samples(count = 1001, spacing = 10.milliseconds))

        history.samples shouldHaveSize 500
        history.samples.size shouldBeLessThanOrEqual PerformanceHistory.COMPACT_TARGET
    }

    @Test
    fun `final sample survives compaction`() {
        val added = samples(count = 1001, spacing = 10.milliseconds)
        val history = historyOf(added, totalBytes = 1_001_000L, totalItems = 1001)

        history.samples.last() shouldBe added.last()
    }

    @Test
    fun `compaction ignores the totals an add carries`() {
        val added = samples(count = 1001, spacing = 10.milliseconds)
        var history = PerformanceHistory()

        // The totals only become known while the operation runs, retention must not depend on them
        added.dropLast(1).forEach { history = history.addSample(it, totalBytes = 0L, totalItems = 1000) }
        history = history.addSample(added.last(), totalBytes = 0L, totalItems = 10_000)

        history.totalItems shouldBe 10_000
        history.samples shouldHaveSize 500
        history.samples.first() shouldBe added.first()
        history.samples.last() shouldBe added.last()
    }

    // ============ RETENTION IS INDEPENDENT OF THE TOTALS ============

    @Test
    fun `a history with neither total compacts identically to one with totals`() {
        val added = samples(count = 1200, spacing = 10.milliseconds)

        val byteBased = historyOf(added, totalBytes = 1_200_000L, totalItems = 0)
        val itemBased = historyOf(added, totalBytes = 0L, totalItems = 1200)
        val mixed = historyOf(added, totalBytes = 1_200_000L, totalItems = 1200)
        val neither = historyOf(added, totalBytes = 0L, totalItems = 0)

        // 1001 adds compact to 500, the remaining 199 adds accumulate on top
        byteBased.samples shouldHaveSize 699
        itemBased.samples shouldBe byteBased.samples
        mixed.samples shouldBe byteBased.samples
        neither.samples shouldBe byteBased.samples
    }

    @Test
    fun `samples that never transferred a byte are retained like any other`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Nothing is ever transferred, every item completes by being created or skipped
        val added = (0 until 1001).map { i ->
            PerformanceSample(
                timestamp = startTime + (i * 10).milliseconds,
                bytesPerSecond = 0L,
                itemsPerSecond = 10f,
                totalBytesProcessed = 0L,
                totalItemsProcessed = i + 1,
                totalBytesAccounted = (i + 1) * 1_000L,
            )
        }
        added.forEach { history = history.addSample(it, totalBytes = 1_001_000L, totalItems = 0) }

        history.samples shouldHaveSize 500
        history.samples.takeLast(60) shouldBe added.takeLast(60)
        history.samples.all { it.totalBytesProcessed == 0L } shouldBe true
    }

    @Test
    fun `compaction sorts samples recorded out of order`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        repeat(1001) { i ->
            // Every tenth sample is recorded out of insertion order
            val offsetMs = if (i % 10 == 0) (i * 10) - 25 else i * 10
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + offsetMs.milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = 0L,
                    totalItemsProcessed = 0
                ),
                totalBytes = 0L,
                totalItems = 0
            )
        }

        history.samples shouldHaveSize 500
        history.samples.zipWithNext().forEach { (prev, next) ->
            (prev.timestamp <= next.timestamp) shouldBe true
        }
    }

    @Test
    fun `a backwards clock jump still compacts within the cap`() {
        val base = Instant.fromEpochMilliseconds(1000)
        // The clock rewinds a minute right after the first sample, then runs forward again
        val stamps = listOf(base) +
            (0 until 500).map { i -> base - 60.seconds + (i * 120).milliseconds } +
            (0 until 500).map { i -> base + 1500.milliseconds + (i * 250).milliseconds }
        val added = stamps.mapIndexed { i, timestamp ->
            PerformanceSample(
                timestamp = timestamp,
                bytesPerSecond = (i + 1) * 1_000_000L,
                itemsPerSecond = (i + 1).toFloat(),
                totalBytesProcessed = (i + 1) * 1_000L,
                totalItemsProcessed = i + 1,
            )
        }

        val history = historyOf(added)

        history.samples.size shouldBeLessThanOrEqual PerformanceHistory.MAX_SAMPLES
    }

    @Test
    fun `the first recorded sample survives a backwards clock jump`() {
        val base = Instant.fromEpochMilliseconds(1_000_000)
        // The clock rewinds a minute right after the first sample, then a forced burst runs forward
        val stamps = listOf(base) + (0 until 1000).map { i -> base - 60.seconds + (i * 120).milliseconds }
        val added = stamps.mapIndexed { i, timestamp ->
            PerformanceSample(
                timestamp = timestamp,
                bytesPerSecond = (i + 1) * 1_000_000L,
                itemsPerSecond = (i + 1).toFloat(),
                totalBytesProcessed = (i + 1) * 1_000L,
                totalItemsProcessed = i + 1,
            )
        }

        val history = historyOf(added)

        // The operation's origin point, wherever the clock put its timestamp
        val origin = added.first()
        history.samples.firstOrNull { it.bytesPerSecond == origin.bytesPerSecond } shouldBe origin
    }

    @Test
    fun `the first recorded sample survives a second compaction after a backwards clock jump`() {
        val base = Instant.fromEpochMilliseconds(1_000_000)
        // Same rewind, but the burst runs long enough to compact twice
        val stamps = listOf(base) + (0 until 1500).map { i -> base - 60.seconds + (i * 120).milliseconds }
        val added = stamps.mapIndexed { i, timestamp ->
            PerformanceSample(
                timestamp = timestamp,
                bytesPerSecond = (i + 1) * 1_000_000L,
                itemsPerSecond = (i + 1).toFloat(),
                totalBytesProcessed = (i + 1) * 1_000L,
                totalItemsProcessed = i + 1,
            )
        }

        val history = historyOf(added)

        // The operation's origin point, wherever the clock put its timestamp
        val origin = added.first()
        history.samples.firstOrNull { it.bytesPerSecond == origin.bytesPerSecond } shouldBe origin
    }

    @Test
    fun `a forced sampling burst keeps its newest sample after a backwards clock jump`() {
        val base = Instant.fromEpochMilliseconds(1_000_000)
        // The clock rewinds during the burst, so the newest sample no longer has the newest timestamp
        val stamps = (0 until 1000).map { i -> base + (i * 100).milliseconds } + (base + 60_050.milliseconds)
        val added = stamps.mapIndexed { i, timestamp ->
            PerformanceSample(
                timestamp = timestamp,
                bytesPerSecond = (i + 1) * 1_000_000L,
                itemsPerSecond = (i + 1).toFloat(),
                totalBytesProcessed = (i + 1) * 1_000L,
                totalItemsProcessed = i + 1,
            )
        }

        val history = historyOf(added)

        // Whatever the clock did, this is what the progress bar's speed and ETA just measured
        val newest = added.last()
        history.samples.firstOrNull { it.bytesPerSecond == newest.bytesPerSecond } shouldBe newest
    }

    // ============ EDGE CASES ============

    @Test
    fun `single sample history`() {
        val sample = PerformanceSample(
            timestamp = Instant.fromEpochMilliseconds(1000),
            bytesPerSecond = 1_000_000L,
            itemsPerSecond = 10f,
            totalBytesProcessed = 100_000L,
            totalItemsProcessed = 1
        )

        val history = PerformanceHistory()
            .addSample(sample, totalBytes = 1_000_000L, totalItems = 10)

        history.samples shouldHaveSize 1
        history.averageBytesPerSecond shouldBe 1_000_000L
        history.peakBytesPerSecond shouldBe 1_000_000L
        history.duration shouldBe 0.seconds
    }

    @Test
    fun `two sample history`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        history = history.addSample(
            PerformanceSample(startTime, 1_000_000L, 10f, 100_000L, 1),
            totalBytes = 1_000_000L,
            totalItems = 10
        )
        history = history.addSample(
            PerformanceSample(startTime + 1.seconds, 2_000_000L, 20f, 500_000L, 5),
            totalBytes = 1_000_000L,
            totalItems = 10
        )

        history.samples shouldHaveSize 2
        history.averageBytesPerSecond shouldBe 1_500_000L
        history.duration shouldBe 1.seconds
    }

    @Test
    fun `rapid sampling with many small files`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 10_000_000L

        // Simulate copying 3000 small files rapidly
        repeat(3000) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 2).milliseconds,  // Very rapid
                    bytesPerSecond = 5_000_000L,
                    itemsPerSecond = 100f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 3000),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 3000
            )
        }

        history.samples.size shouldBeLessThanOrEqual 1000
        history.samples.size shouldBeGreaterThan 0

        // Verify distribution still covers full range
        val firstPercentage = (history.samples.first().totalBytesProcessed.toDouble() / totalBytes) * 100.0
        val lastPercentage = (history.samples.last().totalBytesProcessed.toDouble() / totalBytes) * 100.0

        firstPercentage shouldBeLessThan 10.0  // Early sample
        lastPercentage shouldBeGreaterThan 90.0  // Late sample
    }

    @Test
    fun `sparse sampling with few large files`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 10_000_000_000L  // 10GB

        // Simulate copying 10 large files with sparse updates
        repeat(10) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 5).seconds,  // Sparse
                    bytesPerSecond = 100_000_000L,
                    itemsPerSecond = 0.2f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 10),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 10
            )
        }

        history.samples shouldHaveSize 10  // All retained, under limit
        history.duration shouldBe 45.seconds
    }

    // ============ DYNAMIC TOTALS (SCANNING PHASE) ============

    @Test
    fun `totalBytes increases across samples during scanning`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Simulate scanning phase where totals increase as files are discovered
        // First sample: 100 files found (1MB total)
        history = history.addSample(
            PerformanceSample(startTime, 1_000_000L, 10f, 100_000L, 10),
            totalBytes = 1_000_000L,
            totalItems = 100
        )
        history.totalBytes shouldBe 1_000_000L
        history.totalItems shouldBe 100

        // Second sample: 500 more files found (5MB total now)
        history = history.addSample(
            PerformanceSample(startTime + 100.milliseconds, 1_000_000L, 10f, 500_000L, 50),
            totalBytes = 5_000_000L,
            totalItems = 500
        )
        history.totalBytes shouldBe 5_000_000L  // Should grow to 5MB
        history.totalItems shouldBe 500

        // Third sample: All 1000 files found (10MB total)
        history = history.addSample(
            PerformanceSample(startTime + 200.milliseconds, 1_000_000L, 10f, 1_000_000L, 100),
            totalBytes = 10_000_000L,
            totalItems = 1000
        )
        history.totalBytes shouldBe 10_000_000L  // Should grow to 10MB
        history.totalItems shouldBe 1000
    }

    @Test
    fun `totalItems increases across samples during scanning`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Simulate item-only operation (delete) where items discovered incrementally
        // First: 100 items found
        history = history.addSample(
            PerformanceSample(startTime, 0L, 10f, 0L, 10),
            totalBytes = 0L,
            totalItems = 100
        )
        history.totalItems shouldBe 100

        // Second: 500 items total
        history = history.addSample(
            PerformanceSample(startTime + 100.milliseconds, 0L, 10f, 0L, 50),
            totalBytes = 0L,
            totalItems = 500
        )
        history.totalItems shouldBe 500

        // Third: 1000 items total
        history = history.addSample(
            PerformanceSample(startTime + 200.milliseconds, 0L, 10f, 0L, 100),
            totalBytes = 0L,
            totalItems = 1000
        )
        history.totalItems shouldBe 1000
    }

    @Test
    fun `both totals increase together during scanning`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        val progressions = listOf(
            Triple(1_000_000L, 100, 10),
            Triple(5_000_000L, 500, 50),
            Triple(10_000_000L, 1000, 100),
        )

        progressions.forEachIndexed { i, (totalBytes, totalItems, processed) ->
            history = history.addSample(
                PerformanceSample(
                    startTime + (i * 100).milliseconds,
                    1_000_000L,
                    10f,
                    processed * (totalBytes / totalItems),
                    processed
                ),
                totalBytes = totalBytes,
                totalItems = totalItems
            )

            history.totalBytes shouldBe totalBytes
            history.totalItems shouldBe totalItems
        }
    }

    @Test
    fun `totals never decrease when lower values passed`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // First sample: 10MB, 1000 items
        history = history.addSample(
            PerformanceSample(startTime, 1_000_000L, 10f, 1_000_000L, 100),
            totalBytes = 10_000_000L,
            totalItems = 1000
        )

        history.totalBytes shouldBe 10_000_000L
        history.totalItems shouldBe 1000

        // Second sample: Try to pass lower totals (should be ignored)
        history = history.addSample(
            PerformanceSample(startTime + 100.milliseconds, 1_000_000L, 10f, 2_000_000L, 200),
            totalBytes = 5_000_000L,  // Lower than current 10MB
            totalItems = 500          // Lower than current 1000
        )

        // Totals should not decrease
        history.totalBytes shouldBe 10_000_000L
        history.totalItems shouldBe 1000
    }

    @Test
    fun `a growing span coarsens the overview rather than dropping the tail`() {
        val shortRun = historyOf(samples(count = 2000, spacing = 250.milliseconds))
        val longRun = historyOf(samples(count = 5000, spacing = 250.milliseconds))

        fun tailAndOverview(history: PerformanceHistory): Pair<Int, Int> {
            val cutoff = history.samples.last().timestamp - PerformanceHistory.RECENT_WINDOW
            return history.samples.count { it.timestamp >= cutoff } to
                history.samples.count { it.timestamp < cutoff }
        }

        val (shortTail, shortOverview) = tailAndOverview(shortRun)
        val (longTail, longOverview) = tailAndOverview(longRun)

        // The recent window holds the same samples either way, only the overview thins out
        shortTail shouldBe 481
        longTail shouldBe 481
        longOverview shouldBeLessThan shortOverview
    }

    @Test
    fun `graph reaches 100 percent only when operation complete - simulates 9000 file bug`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val targetFiles = 9000
        val targetBytes = 9_000_000_000L

        // Simulate real copy operation scanning phase
        // Scan phase 1: Found 1000 files, processed 100
        history = history.addSample(
            PerformanceSample(startTime, 10_000_000L, 100f, 100_000_000L, 100),
            totalBytes = 1_000_000_000L,  // 1000 files discovered
            totalItems = 1000
        )

        // At this point, OLD bug would lock totals at 1000 files/1GB
        // Percentage appears to be: 100/1000 = 10%

        // Scan phase 2: Found 5000 files total, processed 500
        history = history.addSample(
            PerformanceSample(startTime + 1.seconds, 10_000_000L, 100f, 500_000_000L, 500),
            totalBytes = 5_000_000_000L,  // 5000 files discovered
            totalItems = 5000
        )

        // NEW fix: totals grow to 5000 files
        // Percentage: 500/5000 = 10% (correct!)
        // OLD bug: 500/1000 = 50% (wrong - graph halfway done but 8500 files remain!)
        history.totalItems shouldBe 5000
        val midPercentage = (500.0 / history.totalItems) * 100.0
        midPercentage shouldBeLessThan 15.0

        // Scan phase 3: All 9000 files discovered, processed 1000
        history = history.addSample(
            PerformanceSample(startTime + 2.seconds, 10_000_000L, 100f, 1_000_000_000L, 1000),
            totalBytes = targetBytes,
            totalItems = targetFiles
        )

        history.totalItems shouldBe 9000
        val earlyPercentage = (1000.0 / history.totalItems) * 100.0
        earlyPercentage shouldBeLessThan 15.0  // Only ~11% complete

        // Continue processing: 4500/9000 files
        history = history.addSample(
            PerformanceSample(startTime + 10.seconds, 10_000_000L, 100f, 4_500_000_000L, 4500),
            totalBytes = targetBytes,
            totalItems = targetFiles
        )

        val halfPercentage = (4500.0 / history.totalItems) * 100.0
        halfPercentage shouldBeGreaterThan 45.0
        halfPercentage shouldBeLessThan 55.0  // ~50% complete

        // Final: 9000/9000 files complete
        history = history.addSample(
            PerformanceSample(startTime + 20.seconds, 10_000_000L, 100f, targetBytes, targetFiles),
            totalBytes = targetBytes,
            totalItems = targetFiles
        )

        val finalPercentage = (targetFiles.toDouble() / history.totalItems) * 100.0
        finalPercentage shouldBe 100.0  // Now 100% complete
    }

    @Test
    fun `growing totals do not change what compaction retains`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        val added = (0 until 1500).map { i ->
            PerformanceSample(
                timestamp = startTime + (i * 10).milliseconds,
                bytesPerSecond = 1_000_000L,
                itemsPerSecond = 10f,
                totalBytesProcessed = (i + 1) * 1000L,
                totalItemsProcessed = i + 1
            )
        }
        // Totals grow linearly as scanning progresses
        added.forEachIndexed { i, sample ->
            val currentTotal = 1000 + (i * 5)
            history = history.addSample(sample, totalBytes = currentTotal * 1000L, totalItems = currentTotal)
        }

        history.samples.size shouldBeLessThanOrEqual PerformanceHistory.MAX_SAMPLES
        history.samples shouldBe historyOf(added).samples

        history.totalItems shouldBe 8495  // Last total: 1000 + (1499 * 5)
        history.totalBytes shouldBe 8_495_000L
    }

    // ============ toString() ============

    @Test
    fun `toString includes key metrics`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        val history = PerformanceHistory()
            .addSample(
                PerformanceSample(startTime, 1_000_000L, 10f, 100_000L, 5),
                totalBytes = 5_000_000L,
                totalItems = 50
            )

        val string = history.toString()

        string shouldNotBe null
        string.contains("startTime") shouldBe true
        string.contains("totalBytes") shouldBe true
        string.contains("totalItems") shouldBe true
        string.contains("samples") shouldBe true
    }

    @Test
    fun `a forced sampling burst keeps its newest samples untouched`() {
        val added = samples(count = 1001, spacing = 1.milliseconds)
        val uncompacted = PerformanceHistory(samples = added, startTime = added.first().timestamp)
        val history = historyOf(added, totalBytes = 1_001_000L, totalItems = 1001)

        history.samples shouldHaveSize PerformanceHistory.RECENT_TARGET
        history.samples.takeLast(PerformanceHistory.PROTECTED_TAIL) shouldBe
            added.takeLast(PerformanceHistory.PROTECTED_TAIL)

        // The progress bar's speed and ETA read these, thinning under them would change a shown number
        history.getRecentBytesPerSecond() shouldBe uncompacted.getRecentBytesPerSecond()
        history.getRecentItemsPerSecond() shouldBe uncompacted.getRecentItemsPerSecond()
    }
}
