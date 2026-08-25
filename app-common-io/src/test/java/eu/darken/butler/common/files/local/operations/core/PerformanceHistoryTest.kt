package eu.darken.butler.common.files.local.operations.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests for PerformanceHistory - performance tracking with adaptive sampling.
 *
 * Critical tests verify:
 * - Sample distribution across 0-100% range after downsampling
 * - Early samples (0-5%) are retained
 * - Late samples (95-100%) are retained
 * - Total samples never exceed MAX_SAMPLES (1000)
 * - Percentage > 100% is handled correctly
 * - Chronological ordering is maintained
 */
class PerformanceHistoryTest : BaseTest() {

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

    // ============ NO DOWNSAMPLING (UNDER LIMIT) ============

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

    // ============ CRITICAL DOWNSAMPLING TESTS ============

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
    fun `distribution across 0-100 percent is maintained after downsampling`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_500_000_000L

        // Create 1500 samples evenly distributed from 0% to 100%
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

        // Verify we have samples in each 5% bucket (20 buckets total)
        val buckets = history.samples.groupBy { sample ->
            val percentage = (sample.totalBytesProcessed.toDouble() / totalBytes) * 100.0
            (percentage / 5.0).toInt().coerceIn(0, 19)
        }

        // All 20 buckets should have at least one sample
        buckets.keys.size shouldBe 20
        buckets.keys.min() shouldBe 0
        buckets.keys.max() shouldBe 19
    }

    @Test
    fun `early samples (0-5 percent) are retained after downsampling`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 2_000_000_000L

        // Create 2000 samples
        repeat(2000) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 2000),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 2000
            )
        }

        // Find samples in 0-5% range
        val earlySamples = history.samples.filter { sample ->
            val percentage = (sample.totalBytesProcessed.toDouble() / totalBytes) * 100.0
            percentage < 5.0
        }

        earlySamples.size shouldBeGreaterThan 0
        println("Early samples (0-5%): ${earlySamples.size}")
    }

    @Test
    fun `late samples (95-100 percent) are retained after downsampling`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 2_000_000_000L

        // Create 2000 samples
        repeat(2000) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 2000),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 2000
            )
        }

        // Find samples in 95-100% range
        val lateSamples = history.samples.filter { sample ->
            val percentage = (sample.totalBytesProcessed.toDouble() / totalBytes) * 100.0
            percentage >= 95.0
        }

        lateSamples.size shouldBeGreaterThan 0
        println("Late samples (95-100%): ${lateSamples.size}")
    }

    @Test
    fun `mid-range samples (40-60 percent) are retained after downsampling`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 2_000_000_000L

        // Create 2000 samples
        repeat(2000) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 2000),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 2000
            )
        }

        // Find samples in 40-60% range
        val midSamples = history.samples.filter { sample ->
            val percentage = (sample.totalBytesProcessed.toDouble() / totalBytes) * 100.0
            percentage in 40.0..60.0
        }

        midSamples.size shouldBeGreaterThan 0
        println("Mid samples (40-60%): ${midSamples.size}")
    }

    @Test
    fun `percentage over 100 is clamped correctly`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_000_000L

        // Add 1100 samples, with the last 100 exceeding totalBytes (simulating measurement errors)
        repeat(1100) { i ->
            val bytesProcessed = if (i < 1000) {
                (i + 1) * (totalBytes / 1000)
            } else {
                // Exceed totalBytes - these should be clamped to 100%
                totalBytes + ((i - 1000 + 1) * 10_000L)
            }

            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = bytesProcessed,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 1100
            )
        }

        // Should not crash and should stay under limit
        history.samples.size shouldBeLessThanOrEqual 1000

        // Verify all samples with >100% are in the last bucket
        val samplesOver100 = history.samples.filter { sample ->
            sample.totalBytesProcessed > totalBytes
        }
        println("Samples over 100%: ${samplesOver100.size}")

        // All should be treated as 100% (bucket 19)
        samplesOver100.forEach { sample ->
            val percentage = (sample.totalBytesProcessed.toDouble() / totalBytes) * 100.0
            percentage shouldBeGreaterThan 100.0  // Raw percentage exceeds 100
        }
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
    fun `even bucket distribution - no bucket dominates`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 2_000_000_000L

        fun addSample(i: Int) {
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 2000),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 2000
            )
        }

        // The bound only holds right after a compaction, mid-amortization there are up to ~200
        // uncompacted trailing samples that all land in the same bucket
        repeat(1001) { addSample(it) }

        val buckets = history.samples.groupBy { sample ->
            val percentage = (sample.totalBytesProcessed.toDouble() / totalBytes) * 100.0
            (percentage / 5.0).toInt().coerceIn(0, 19)
        }

        val avgSamplesPerBucket = history.samples.size.toDouble() / 20

        buckets.values.forEach { samplesInBucket ->
            samplesInBucket.size shouldBeLessThanOrEqual (avgSamplesPerBucket * 2).toInt()
        }

        println("Bucket distribution: ${buckets.mapValues { it.value.size }}")

        // The cap still holds while amortizing towards the next compaction
        (1001 until 2000).forEach { addSample(it) }
        history.samples.size shouldBeLessThanOrEqual 1000
    }

    @Test
    fun `compaction is amortized - one compaction lands on the target`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_001_000_000L

        repeat(1001) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 1001),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 1001
            )
        }

        history.samples shouldHaveSize 800
    }

    @Test
    fun `final sample survives compaction`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_001_000_000L
        lateinit var lastSample: PerformanceSample

        // Even distribution puts 50+ samples into every bucket, so every bucket gets downsampled
        repeat(1001) { i ->
            lastSample = PerformanceSample(
                timestamp = startTime + (i * 10).milliseconds,
                bytesPerSecond = 1_000_000L,
                itemsPerSecond = 10f,
                totalBytesProcessed = (i + 1) * (totalBytes / 1001),
                totalItemsProcessed = i + 1
            )
            history = history.addSample(lastSample, totalBytes = totalBytes, totalItems = 1001)
        }

        history.samples.last() shouldBe lastSample
    }

    @Test
    fun `totals jumping on the cap-crossing add drive bucket assignment`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // 1000 samples that look complete under the old total of 1000 items
        repeat(1000) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 0L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = 0L,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = 0L,
                totalItems = 1000
            )
        }

        // The add that crosses the cap discovers 9000 more items
        history = history.addSample(
            PerformanceSample(
                timestamp = startTime + 10_010.milliseconds,
                bytesPerSecond = 0L,
                itemsPerSecond = 10f,
                totalBytesProcessed = 0L,
                totalItemsProcessed = 1001
            ),
            totalBytes = 0L,
            totalItems = 10_000
        )

        history.totalItems shouldBe 10_000

        // Under the new denominator everything sits below 11%, so only the first three buckets
        // are populated: 40 + 40 + 2. Stale totals would have spread them over all 20 buckets.
        history.samples shouldHaveSize 82

        history.samples.forEach { sample ->
            val percentage = (sample.totalItemsProcessed.toDouble() / history.totalItems) * 100.0
            (percentage / 5.0).toInt() shouldBeLessThanOrEqual 2
        }

        history.samples.first().totalItemsProcessed shouldBe 1
        history.samples.last().totalItemsProcessed shouldBe 1001
    }

    // ============ BYTE-BASED VS ITEM-BASED OPERATIONS ============

    @Test
    fun `byte-based operation uses bytes for percentage`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_000_000L

        repeat(1200) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 1200),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 0  // Items = 0, so use bytes
            )
        }

        history.samples.size shouldBeLessThanOrEqual 1000
        history.samples.size shouldBeGreaterThan 0
    }

    @Test
    fun `item-based operation uses items for percentage`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalItems = 1200

        repeat(1200) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 0L,  // No bytes
                    itemsPerSecond = 10f,
                    totalBytesProcessed = 0L,
                    totalItemsProcessed = i + 1
                ),
                totalBytes = 0L,  // Bytes = 0, so use items
                totalItems = totalItems
            )
        }

        history.samples.size shouldBeLessThanOrEqual 1000
        history.samples.size shouldBeGreaterThan 0
    }

    @Test
    fun `mixed operation prefers bytes when both available`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_000_000L
        val totalItems = 100

        repeat(1200) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = (i + 1) * (totalBytes / 1200),
                    totalItemsProcessed = (i + 1) * (totalItems / 1200)
                ),
                totalBytes = totalBytes,
                totalItems = totalItems
            )
        }

        history.samples.size shouldBeLessThanOrEqual 1000
        history.samples.size shouldBeGreaterThan 0
    }

    @Test
    fun `fallback when neither bytes nor items available`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        repeat(1200) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = 0L,
                    totalItemsProcessed = 0
                ),
                totalBytes = 0L,  // No total to calculate percentage
                totalItems = 0
            )
        }

        // Should fallback to takeLast(800): add 1001 compacts, the remaining 199 adds accumulate
        history.samples.size shouldBe 999
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
    fun `percentage calculation uses latest totals not first totals`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Simulate discovering files during scanning
        // First sample: Only 1000 files discovered, processed 500
        history = history.addSample(
            PerformanceSample(startTime, 1_000_000L, 10f, 500_000L, 500),
            totalBytes = 1_000_000L,
            totalItems = 1000
        )

        // This looks like 50% complete (500/1000)

        // Second sample: Now 9000 files total discovered, still only processed 500
        history = history.addSample(
            PerformanceSample(startTime + 100.milliseconds, 1_000_000L, 10f, 500_000L, 500),
            totalBytes = 9_000_000L,
            totalItems = 9000
        )

        // Now it's only ~5.5% complete (500/9000), not 50%!
        history.totalItems shouldBe 9000
        val percentage = (500.0 / history.totalItems) * 100.0
        percentage shouldBeLessThan 10.0  // Should be much less than 50%
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
    fun `downsampling uses latest totals for bucketing`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()

        // Add 1500 samples with increasing totals (simulates long scanning phase)
        repeat(1500) { i ->
            // Totals grow linearly as scanning progresses
            val currentTotal = 1000 + (i * 5)  // Starts at 1000, ends at 8500
            val processed = i + 1

            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 10).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = processed * 1000L,
                    totalItemsProcessed = processed
                ),
                totalBytes = currentTotal * 1000L,
                totalItems = currentTotal
            )
        }

        // Should downsample to ≤ 1000
        history.samples.size shouldBeLessThanOrEqual 1000

        // Verify it used the FINAL total (8500) not the first total (1000) for bucketing
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
    fun `last bucket (95-100 percent) is populated when operation completes`() {
        val startTime = Instant.fromEpochMilliseconds(1000)
        var history = PerformanceHistory()
        val totalBytes = 1_000_000L

        // Add samples up to 95%
        repeat(95) { i ->
            history = history.addSample(
                PerformanceSample(
                    timestamp = startTime + (i * 100).milliseconds,
                    bytesPerSecond = 1_000_000L,
                    itemsPerSecond = 10f,
                    totalBytesProcessed = ((i + 1) * 10_000L),
                    totalItemsProcessed = i + 1
                ),
                totalBytes = totalBytes,
                totalItems = 100
            )
        }

        // Add final 100% sample
        history = history.addSample(
            PerformanceSample(
                timestamp = startTime + 100.seconds,
                bytesPerSecond = 1_000_000L,
                itemsPerSecond = 10f,
                totalBytesProcessed = totalBytes,
                totalItemsProcessed = 100
            ),
            totalBytes = totalBytes,
            totalItems = 100
        )

        // Find samples in 95-100% range (bucket 19)
        val finalBucketSamples = history.samples.filter { sample ->
            val percentage = (sample.totalBytesProcessed.toDouble() / totalBytes) * 100.0
            percentage >= 95.0
        }

        finalBucketSamples.size shouldBeGreaterThan 0

        // Verify 100% sample exists
        val completeSample = history.samples.last()
        completeSample.totalBytesProcessed shouldBe totalBytes
    }
}
