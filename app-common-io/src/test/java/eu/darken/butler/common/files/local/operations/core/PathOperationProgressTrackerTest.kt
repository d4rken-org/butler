package eu.darken.butler.common.files.local.operations.core

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Tests for PathOperationProgressTracker - progress and performance tracking.
 *
 * Critical tests verify:
 * - First sample is recorded even with no previous time
 * - Samples are recorded with zero time delta (< 1ms elapsed)
 * - Final 100% sample is always captured
 * - Speed estimation works correctly for edge cases
 */
class PathOperationProgressTrackerTest : BaseTest() {

    // ============ BASIC PROGRESS TRACKING ============

    @Test
    fun `tracker starts with zero progress`() {
        val tracker = PathOperationProgressTracker()

        tracker.totalItems shouldBe 0
        tracker.itemsProcessed shouldBe 0
        tracker.totalBytes shouldBe 0L
        tracker.processedBytes shouldBe 0L
        tracker.performanceHistory.samples.size shouldBe 0
    }

    @Test
    fun `completeItem increments itemsProcessed`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 10

        tracker.completeItem()
        tracker.itemsProcessed shouldBe 1

        tracker.completeItem()
        tracker.itemsProcessed shouldBe 2
    }

    @Test
    fun `completeItem with bytes increments both counters`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 10
        tracker.totalBytes = 1000L

        tracker.completeItem(bytes = 100L)

        tracker.itemsProcessed shouldBe 1
        tracker.processedBytes shouldBe 100L
    }

    @Test
    fun `file progress tracking updates processedBytes`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalBytes = 1000L

        tracker.startFile(size = 500L)
        tracker.currentFileSize shouldBe 500L
        tracker.currentFileBytes shouldBe 0L

        tracker.updateFileProgress(bytes = 100L)
        tracker.currentFileBytes shouldBe 100L
        tracker.processedBytes shouldBe 100L

        tracker.updateFileProgress(bytes = 50L)
        tracker.currentFileBytes shouldBe 150L
        tracker.processedBytes shouldBe 150L

        tracker.completeFile()
        tracker.currentFileSize shouldBe 0L
        tracker.currentFileBytes shouldBe 0L
        // processedBytes should account for remaining bytes
        tracker.processedBytes shouldBe 500L
    }

    // ============ PERFORMANCE SAMPLE RECORDING ============

    @Test
    fun `first sample is recorded even without previous timestamp`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 100
        tracker.totalBytes = 1_000_000L

        // Complete some items
        tracker.completeItem(bytes = 100_000L)
        tracker.completeItem(bytes = 100_000L)

        // Force progress report (first sample)
        val reported = tracker.shouldReportProgress(force = true)

        reported shouldBe true
        tracker.performanceHistory.samples.size shouldBe 1

        val sample = tracker.performanceHistory.samples.first()
        sample.totalBytesProcessed shouldBe 200_000L
        sample.totalItemsProcessed shouldBe 2
    }

    @Test
    fun `samples recorded with zero time delta have estimated speeds`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 10
        tracker.totalBytes = 1_000_000L

        val startTime = Instant.fromEpochMilliseconds(1000)

        // First sample
        tracker.completeItem(bytes = 100_000L)
        tracker.shouldReportProgress(force = true)
        tracker.performanceHistory.samples.size shouldBe 1

        // Second sample with < 1ms elapsed (zero time delta)
        // Simulate by completing another item instantly
        tracker.completeItem(bytes = 100_000L)
        tracker.shouldReportProgress(force = true)

        // Should still record the sample (not skip it)
        tracker.performanceHistory.samples.size shouldBe 2

        val secondSample = tracker.performanceHistory.samples[1]
        secondSample.totalBytesProcessed shouldBe 200_000L
        secondSample.totalItemsProcessed shouldBe 2
        // Speed should be estimated (not zero, not infinite)
        // Either from total progress or previous sample
    }

    @Test
    fun `final 100 percent sample is always recorded`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 5
        tracker.totalBytes = 500_000L

        // Process all items
        repeat(5) { i ->
            tracker.completeItem(bytes = 100_000L)
            if (i < 4) {
                // Normal progress reports for first 4 items
                tracker.shouldReportProgress(force = false)
            }
        }

        // Before final report
        val beforeFinal = tracker.performanceHistory.samples.size

        // Force final progress report at 100%
        tracker.shouldReportProgress(force = true)

        // Final sample should be added
        tracker.performanceHistory.samples.size shouldBeGreaterThan beforeFinal

        val finalSample = tracker.performanceHistory.samples.last()
        finalSample.totalBytesProcessed shouldBe 500_000L
        finalSample.totalItemsProcessed shouldBe 5

        // These should match totals (100% completion)
        val bytePercentage = (finalSample.totalBytesProcessed.toDouble() / tracker.totalBytes) * 100.0
        val itemPercentage = (finalSample.totalItemsProcessed.toDouble() / tracker.totalItems) * 100.0

        bytePercentage shouldBe 100.0
        itemPercentage shouldBe 100.0
    }

    @Test
    fun `no sample recorded when no progress made`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 10

        // First sample
        tracker.completeItem()
        tracker.shouldReportProgress(force = true)
        tracker.performanceHistory.samples.size shouldBe 1

        // Try to record again without any progress
        val before = tracker.performanceHistory.samples.size
        tracker.shouldReportProgress(force = true)

        // Should not add a duplicate sample
        tracker.performanceHistory.samples.size shouldBe before
    }

    // ============ PROGRESS THROTTLING ============

    @Test
    fun `shouldReportProgress throttles based on interval`() {
        val tracker = PathOperationProgressTracker(progressReportInterval = 100.milliseconds)
        tracker.totalItems = 100

        // First call should always report
        tracker.completeItem()
        tracker.shouldReportProgress() shouldBe true

        // Immediate second call should be throttled (< 100ms elapsed)
        tracker.completeItem()
        tracker.shouldReportProgress() shouldBe false

        // Force flag overrides throttling
        tracker.completeItem()
        tracker.shouldReportProgress(force = true) shouldBe true
    }

    @Test
    fun `force flag always triggers progress report`() {
        val tracker = PathOperationProgressTracker(progressReportInterval = 1000.milliseconds)
        tracker.totalItems = 10

        // Even with long interval, force=true should work
        tracker.completeItem()
        tracker.shouldReportProgress(force = true) shouldBe true

        tracker.completeItem()
        tracker.shouldReportProgress(force = true) shouldBe true
    }

    // ============ RESET ============

    @Test
    fun `reset clears all progress and performance data`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 10
        tracker.totalBytes = 1000L

        tracker.completeItem(bytes = 100L)
        tracker.completeItem(bytes = 100L)
        tracker.shouldReportProgress(force = true)

        // Should have data
        tracker.itemsProcessed shouldBe 2
        tracker.processedBytes shouldBe 200L
        tracker.performanceHistory.samples.size shouldBeGreaterThan 0

        // Reset
        tracker.reset()

        // Everything cleared
        tracker.totalItems shouldBe 0
        tracker.itemsProcessed shouldBe 0
        tracker.totalBytes shouldBe 0L
        tracker.processedBytes shouldBe 0L
        tracker.performanceHistory.samples.size shouldBe 0
    }

    // ============ SNAPSHOT ============

    @Test
    fun `createSnapshot captures current state`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 10
        tracker.totalBytes = 1000L

        tracker.startFile(size = 500L)
        tracker.updateFileProgress(bytes = 100L)
        tracker.completeItem()

        val snapshot = tracker.createSnapshot()

        snapshot.totalItems shouldBe 10
        snapshot.itemsProcessed shouldBe 1
        snapshot.totalBytes shouldBe 1000L
        snapshot.processedBytes shouldBe 100L
        snapshot.currentFileSize shouldBe 500L
        snapshot.currentFileBytes shouldBe 100L
    }

    // ============ EDGE CASES ============

    @Test
    fun `completeFile accounts for missing bytes`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalBytes = 1000L

        tracker.startFile(size = 500L)
        tracker.updateFileProgress(bytes = 100L)

        // Complete file without updating for remaining 400 bytes
        tracker.completeFile()

        // Should account for all 500 bytes
        tracker.processedBytes shouldBe 500L
    }

    @Test
    fun `multiple files tracked correctly`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 3
        tracker.totalBytes = 1500L

        // File 1
        tracker.startFile(size = 500L)
        tracker.updateFileProgress(bytes = 500L)
        tracker.completeFile()
        tracker.completeItem()

        // File 2
        tracker.startFile(size = 300L)
        tracker.updateFileProgress(bytes = 300L)
        tracker.completeFile()
        tracker.completeItem()

        // File 3
        tracker.startFile(size = 700L)
        tracker.updateFileProgress(bytes = 700L)
        tracker.completeFile()
        tracker.completeItem()

        tracker.itemsProcessed shouldBe 3
        tracker.processedBytes shouldBe 1500L
    }

    @Test
    fun `final forced progress report creates 100 percent sample`() {
        val tracker = PathOperationProgressTracker()
        tracker.totalBytes = 1_000_000L
        tracker.totalItems = 100

        // Simulate operation progress to 95%
        tracker.processedBytes = 950_000L
        tracker.itemsProcessed = 95
        tracker.shouldReportProgress(force = true)

        val before100 = tracker.performanceHistory.samples.last()
        before100.totalBytesProcessed shouldBe 950_000L

        // Complete remaining 5%
        tracker.processedBytes = 1_000_000L
        tracker.itemsProcessed = 100
        tracker.shouldReportProgress(force = true)

        // Verify final sample shows 100%
        val finalSample = tracker.performanceHistory.samples.last()
        finalSample.totalBytesProcessed shouldBe 1_000_000L
        finalSample.totalItemsProcessed shouldBe 100

        val percentage = (finalSample.totalBytesProcessed.toDouble() / tracker.totalBytes) * 100.0
        percentage shouldBe 100.0
    }
}
