package eu.darken.butler.workspace.core.operations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import kotlin.time.Instant

// Robolectric: the progress strings are CaStrings, so asserting what they render needs a context.
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class TransferProgressFormattingTest : BaseTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

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

    @Test
    fun `an hour of remaining work reads as an hour, not 3600 seconds`() {
        val metrics = build(
            performanceHistory = history(bytesPerSecond = 1L, itemsPerSecond = 1f),
            totalBytes = 3600L,
            processedBytes = 0L,
        )

        metrics.overallEta shouldBe 3600L
        val rendered = metrics.overall!!.get(context)
        rendered shouldContain "1 hour remaining"
        rendered shouldNotContain "3600"
    }

    @Test
    fun `a per-file hour reads as an hour too`() {
        val metrics = build(
            currentFileSize = 3601L,
            currentFileBytes = 1L,
            currentFileStartTime = baseTime,
            now = baseTime + kotlin.time.Duration.parse("1s"),
        )

        metrics.fileEta shouldBe 3600L
        metrics.currentFile!!.get(context) shouldContain "1 hour remaining"
    }

    @Test
    fun `a zero eta renders no remaining segment`() {
        val metrics = build(totalBytes = 1000L, processedBytes = 1000L)

        metrics.overallEta shouldBe 0L
        metrics.overall!!.get(context) shouldNotContain "remaining"
    }

    @Test
    fun `a negative eta renders no remaining segment`() {
        // Preserved Saver behavior: without requireTotalBytesForEta the raw metric can go negative.
        // The number stays untouched, but "-5 seconds remaining" is not shown.
        val metrics = build(totalBytes = 0L, processedBytes = 500L, requireTotalBytesForEta = false)

        metrics.overallEta shouldBe -5L
        metrics.overall!!.get(context) shouldNotContain "remaining"
    }
}
