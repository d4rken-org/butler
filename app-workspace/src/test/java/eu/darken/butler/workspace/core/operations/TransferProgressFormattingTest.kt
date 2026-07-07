package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class TransferProgressFormattingTest : BaseTest() {

    private val baseTime = Instant.fromEpochMilliseconds(1_000_000)

    private fun history(bytesPerSecond: Long, itemsPerSecond: Float) = PerformanceHistory(
        samples = listOf(
            PerformanceSample(
                timestamp = baseTime,
                bytesPerSecond = bytesPerSecond,
                itemsPerSecond = itemsPerSecond,
                totalBytesProcessed = 0L,
                totalItemsProcessed = 0,
            )
        ),
    )

    private fun build(
        performanceHistory: PerformanceHistory? = history(bytesPerSecond = 100L, itemsPerSecond = 2.7f),
        totalBytes: Long = 1000L,
        processedBytes: Long = 400L,
        currentFileSize: Long = 0L,
        currentFileBytes: Long = 0L,
        currentFileStartTime: Instant? = null,
        truncateItemSpeed: Boolean = true,
        requireTotalBytesForEta: Boolean = true,
        now: Instant = baseTime,
    ) = buildTransferProgressMetrics(
        performanceHistory = performanceHistory,
        totalBytes = totalBytes,
        processedBytes = processedBytes,
        currentFileSize = currentFileSize,
        currentFileBytes = currentFileBytes,
        currentFileStartTime = currentFileStartTime,
        truncateItemSpeed = truncateItemSpeed,
        requireTotalBytesForEta = requireTotalBytesForEta,
        now = now,
    )

    @Test
    fun `null performance history yields no overall metrics`() {
        val metrics = build(performanceHistory = null)

        metrics.overallBytesSpeed shouldBe 0L
        metrics.overallItemsSpeed shouldBe 0.0
        metrics.overallEta shouldBe null
        metrics.overall shouldBe null
    }

    @Test
    fun `item speed is truncated to whole units for transfer operations`() {
        build(truncateItemSpeed = true).overallItemsSpeed shouldBe 2.0
        build(
            performanceHistory = history(bytesPerSecond = 100L, itemsPerSecond = 0.9f),
            truncateItemSpeed = true,
        ).overallItemsSpeed shouldBe 0.0
    }

    @Test
    fun `item speed keeps fractions for save operations`() {
        build(truncateItemSpeed = false).overallItemsSpeed shouldBe 2.7f.toDouble()
    }

    @Test
    fun `overall eta derives from remaining bytes and speed`() {
        val metrics = build(totalBytes = 1000L, processedBytes = 400L)

        metrics.overallEta shouldBe 6L
        metrics.overall shouldNotBe null
    }

    @Test
    fun `overall eta suppressed for unknown total when flagged`() {
        build(totalBytes = 0L, requireTotalBytesForEta = true).overallEta shouldBe null
        // Documents preserved Saver behavior: without the flag the (possibly negative) value
        // is computed from whatever the tracker reports.
        build(totalBytes = 0L, processedBytes = 500L, requireTotalBytesForEta = false)
            .overallEta shouldBe -5L
    }

    @Test
    fun `per-file metrics derive speed and eta from elapsed time`() {
        val metrics = build(
            currentFileSize = 1000L,
            currentFileBytes = 200L,
            currentFileStartTime = baseTime,
            now = baseTime + kotlin.time.Duration.parse("2s"),
        )

        metrics.fileSpeed shouldBe 100L
        metrics.fileEta shouldBe 8L
        metrics.currentFile shouldNotBe null
    }

    @Test
    fun `per-file metrics absent when no time has elapsed`() {
        val metrics = build(
            currentFileSize = 1000L,
            currentFileBytes = 200L,
            currentFileStartTime = baseTime,
            now = baseTime,
        )

        metrics.fileSpeed shouldBe 0L
        metrics.fileEta shouldBe null
        metrics.currentFile shouldBe null
    }

    @Test
    fun `per-file metrics absent without start time or file size`() {
        build(currentFileStartTime = null, currentFileSize = 1000L).fileSpeed shouldBe 0L
        build(
            currentFileStartTime = baseTime,
            currentFileSize = 0L,
            now = baseTime + kotlin.time.Duration.parse("2s"),
        ).fileSpeed shouldBe 0L
    }

    @Test
    fun `zero byte speed yields no overall string even with item speed`() {
        val metrics = build(performanceHistory = history(bytesPerSecond = 0L, itemsPerSecond = 5f))

        metrics.overall shouldBe null
        metrics.overallEta shouldBe null
    }
}
