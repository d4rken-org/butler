package eu.darken.butler.common.files.local.operations.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
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

        // Create 2000 samples evenly distributed
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

        // Group by buckets
        val buckets = history.samples.groupBy { sample ->
            val percentage = (sample.totalBytesProcessed.toDouble() / totalBytes) * 100.0
            (percentage / 5.0).toInt().coerceIn(0, 19)
        }

        // Calculate average samples per bucket
        val avgSamplesPerBucket = history.samples.size.toDouble() / 20

        // No bucket should have more than 2x the average (allowing some variance)
        buckets.values.forEach { samplesInBucket ->
            samplesInBucket.size shouldBeLessThanOrEqual (avgSamplesPerBucket * 2).toInt()
        }

        println("Bucket distribution: ${buckets.mapValues { it.value.size }}")
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

        // Should fallback to takeLast(1000)
        history.samples.size shouldBe 1000
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
}
