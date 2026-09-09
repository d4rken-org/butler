package eu.darken.butler.workspace.ui.operations.details

import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests for [PerformanceGraphData] - turning a [PerformanceHistory] into plottable series.
 */
class PerformanceGraphDataTest : BaseTest() {

    private val startTime = Instant.fromEpochMilliseconds(1_000_000)

    private fun sample(
        atMillis: Long,
        bytesPerSecond: Long = 0L,
        itemsPerSecond: Float = 0f,
        totalBytesProcessed: Long = 0L,
        totalItemsProcessed: Int = 0,
    ) = PerformanceSample(
        timestamp = startTime + atMillis.milliseconds,
        bytesPerSecond = bytesPerSecond,
        itemsPerSecond = itemsPerSecond,
        totalBytesProcessed = totalBytesProcessed,
        totalItemsProcessed = totalItemsProcessed,
    )

    private fun history(samples: List<PerformanceSample>) = PerformanceHistory(
        samples = samples,
        startTime = startTime,
    )

    /** [count] samples one second apart, the cadence at which every sample gets its own x. */
    private fun secondly(
        count: Int,
        bytesPerSecond: (Int) -> Long = { 0L },
        itemsPerSecond: (Int) -> Float = { 1f },
    ) = (0 until count).map { i ->
        sample(
            atMillis = i * 1000L,
            bytesPerSecond = bytesPerSecond(i),
            itemsPerSecond = itemsPerSecond(i),
            totalBytesProcessed = i * 1_000_000L,
            totalItemsProcessed = i,
        )
    }

    private fun overviewOf(samples: List<PerformanceSample>) = PerformanceGraphData.from(
        history = history(samples),
        scope = PerformanceGraphScope.OVERVIEW,
        now = samples.last().timestamp,
    )

    private fun liveOf(samples: List<PerformanceSample>, nowMillis: Long) = PerformanceGraphData.from(
        history = history(samples),
        scope = PerformanceGraphScope.LIVE,
        now = startTime + nowMillis.milliseconds,
    )

    // ============ TIME AXIS ============

    @Test
    fun `x values snap to the half second grid under sampling jitter`() {
        // The tracker reports every 250ms give or take a few, nothing may land between the steps
        val samples = (0 until 20).map { i ->
            val jitter = if (i % 2 == 0) 0L else 249L
            sample(atMillis = (i / 2) * 500L + jitter, itemsPerSecond = 5f, totalItemsProcessed = i)
        }

        val data = overviewOf(samples).shouldNotBeNull()

        data.elapsedSeconds shouldBe (0 until 10).map { it * 0.5f }
    }

    @Test
    fun `a sample stamped before the start clamps to zero`() {
        val samples = listOf(sample(atMillis = -500L, itemsPerSecond = 1f)) + secondly(count = 15)

        val data = overviewOf(samples).shouldNotBeNull()

        data.elapsedSeconds.first() shouldBe 0f
    }

    @Test
    fun `a wall clock rollback keeps every earlier point`() {
        val before = secondly(count = 15)
        // The clock jumps twelve seconds back, later samples carry timestamps already plotted
        val after = before + listOf(
            sample(atMillis = 2_000L, itemsPerSecond = 1f),
            sample(atMillis = 2_500L, itemsPerSecond = 1f),
            sample(atMillis = 3_000L, itemsPerSecond = 1f),
        )

        val plain = overviewOf(before).shouldNotBeNull()
        val rolledBack = overviewOf(after).shouldNotBeNull()

        rolledBack.elapsedSeconds shouldBe plain.elapsedSeconds
        rolledBack.elapsedSeconds.zipWithNext().forEach { (previous, next) -> (next > previous) shouldBe true }
    }

    @Test
    fun `the plotted prefix is stable as samples arrive`() {
        val samples = secondly(count = 15, itemsPerSecond = { (it + 1).toFloat() })

        val early = overviewOf(samples.take(12)).shouldNotBeNull()
        val later = overviewOf(samples).shouldNotBeNull()

        later.elapsedSeconds.take(12) shouldBe early.elapsedSeconds
        later.itemSpeeds.take(12) shouldBe early.itemSpeeds
    }

    @Test
    fun `a final sample sharing the last step replaces it`() {
        // 100ms apart, so ten samples collapse onto three plot steps
        val samples = (0 until 10).map { i ->
            sample(atMillis = i * 100L, itemsPerSecond = (i + 1) * 10f, totalItemsProcessed = i)
        }

        val data = overviewOf(samples).shouldNotBeNull()

        data.elapsedSeconds shouldBe listOf(0f, 0.5f, 1f)
        // The final sample took the place of the one that shared its step: (10 + 20 + … + 100) / 10
        data.itemSpeeds.last() shouldBe (55f plusOrMinus 0.001f)
    }

    // ============ SMOOTHING ============

    @Test
    fun `smoothing averages over the preceding two seconds`() {
        val samples = secondly(count = 15, itemsPerSecond = { (it + 1).toFloat() })

        val data = overviewOf(samples).shouldNotBeNull()

        data.itemSpeeds shouldHaveSize 15
        // A one second cadence puts two samples in the window, the first point only knows itself
        data.itemSpeeds[0] shouldBe (1f plusOrMinus 0.001f)
        data.itemSpeeds[3] shouldBe (3.5f plusOrMinus 0.001f)
        data.itemSpeeds.last() shouldBe (14.5f plusOrMinus 0.001f)
        data.maxItemSpeed shouldBe 14.5
    }

    @Test
    fun `two samples sharing a timestamp cannot change the earlier one`() {
        val samples = secondly(count = 15, itemsPerSecond = { 10f })
        val withDuplicate = samples.take(6) +
            sample(atMillis = 5_000L, itemsPerSecond = 1_000f) +
            samples.drop(6)

        val plain = overviewOf(samples).shouldNotBeNull()
        val duplicated = overviewOf(withDuplicate).shouldNotBeNull()

        duplicated.elapsedSeconds shouldBe plain.elapsedSeconds
        duplicated.itemSpeeds.take(6) shouldBe plain.itemSpeeds.take(6)
    }

    // ============ LIVE WINDOW ============

    @Test
    fun `a live window under two minutes starts at zero and reports its elapsed time`() {
        val data = liveOf(secondly(count = 20), nowMillis = 30_000L).shouldNotBeNull()

        data.xStart shouldBe 0f
        data.xEnd shouldBe 30f
        data.windowSpanSeconds shouldBe 30
        data.elapsedSeconds shouldHaveSize 20
    }

    @Test
    fun `a live window past two minutes slides and keeps one point before it`() {
        val data = liveOf(secondly(count = 201), nowMillis = 200_000L).shouldNotBeNull()

        data.xStart shouldBe 80f
        data.xEnd shouldBe 200f
        data.windowSpanSeconds shouldBe 120
        // The point before the window keeps the line entering from the left edge
        data.elapsedSeconds.first() shouldBe 79f
        data.elapsedSeconds.last() shouldBe 200f
    }

    @Test
    fun `a stall longer than the window still plots and still tracks now`() {
        val data = liveOf(secondly(count = 20), nowMillis = 400_000L).shouldNotBeNull()

        // Every sample is older than the window, dropping them would blank the graph mid stall
        data.elapsedSeconds shouldBe listOf(18f, 19f)
        data.xEnd shouldBe 400f
        data.windowSpanSeconds shouldBe 120
    }

    @Test
    fun `a stall still plots when the last two samples share a plot step`() {
        // The final pair is 250ms apart, so both snap onto the same 9.5s step
        val samples = secondly(count = 9) + listOf(
            sample(atMillis = 9_300L, itemsPerSecond = 1f, totalItemsProcessed = 9),
            sample(atMillis = 9_550L, itemsPerSecond = 1f, totalItemsProcessed = 10),
        )

        liveOf(samples, nowMillis = 200_000L).shouldNotBeNull()
    }

    @Test
    fun `an overview spans the whole transfer without a window span`() {
        val data = overviewOf(secondly(count = 20)).shouldNotBeNull()

        data.xStart shouldBe 0f
        data.xEnd shouldBe 19f
        data.windowSpanSeconds shouldBe null
    }

    @Test
    fun `the range never ends before the newest sample`() {
        // A clock that lags behind the samples must not crop the newest point away
        val data = liveOf(secondly(count = 20), nowMillis = 0L).shouldNotBeNull()

        data.xEnd shouldBe 19f
    }

    // ============ BYTE UNIT SELECTION ============

    @Test
    fun `slow transfer is scaled into kilobytes per second`() {
        val samples = secondly(count = 20, bytesPerSecond = { 500_000L }, itemsPerSecond = { 4f })

        val data = overviewOf(samples).shouldNotBeNull()

        data.byteUnit shouldBe ByteSpeedUnit.KB_S
        data.byteSpeeds.shouldNotBeNull().forEach { it shouldBe 500f }
        data.maxByteSpeed shouldBe 500.0
    }

    @Test
    fun `byte unit is selected from the fastest sample`() {
        fun unitFor(bytesPerSecond: Long): ByteSpeedUnit? {
            val samples = secondly(count = 20, bytesPerSecond = { bytesPerSecond })
            return overviewOf(samples).shouldNotBeNull().byteUnit
        }

        unitFor(999L) shouldBe ByteSpeedUnit.B_S
        unitFor(1_000L) shouldBe ByteSpeedUnit.KB_S
        unitFor(999_999L) shouldBe ByteSpeedUnit.KB_S
        unitFor(1_000_000L) shouldBe ByteSpeedUnit.MB_S
        unitFor(999_999_999L) shouldBe ByteSpeedUnit.MB_S
        unitFor(1_000_000_000L) shouldBe ByteSpeedUnit.GB_S
    }

    @Test
    fun `the byte unit follows the plotted window`() {
        // A fast burst up front, then a long slow tail
        fun burstThenTail(count: Int) = secondly(
            count = count,
            bytesPerSecond = { if (it < 20) 2_000_000_000L else 5_000_000L },
        )

        val early = liveOf(burstThenTail(30), nowMillis = 29_000L).shouldNotBeNull()
        val late = liveOf(burstThenTail(220), nowMillis = 219_000L).shouldNotBeNull()

        early.byteUnit shouldBe ByteSpeedUnit.GB_S
        // The burst scrolled out of the window, so the axis relabels
        late.byteUnit shouldBe ByteSpeedUnit.MB_S
    }

    @Test
    fun `bytes moved without a measured speed still get a byte series`() {
        val samples = secondly(count = 20, itemsPerSecond = { 2f })

        val data = overviewOf(samples).shouldNotBeNull()

        data.byteUnit shouldBe ByteSpeedUnit.B_S
        data.byteSpeeds.shouldNotBeNull().forEach { it shouldBe 0f }
        data.maxByteSpeed shouldBe 0.0
    }

    @Test
    fun `an item only operation has no byte series`() {
        val samples = (0 until 20).map { i ->
            sample(atMillis = i * 1000L, itemsPerSecond = 5f, totalItemsProcessed = i)
        }

        val data = overviewOf(samples).shouldNotBeNull()

        data.byteSpeeds shouldBe null
        data.byteUnit shouldBe null
        data.maxByteSpeed shouldBe 0.0
    }

    @Test
    fun `a byteless window keeps the byte series of the full history`() {
        // Byte data anywhere in the history keeps both axes, a window without it must not flip modes
        val samples = (0 until 220).map { i ->
            sample(
                atMillis = i * 1000L,
                bytesPerSecond = if (i < 20) 1_000_000L else 0L,
                itemsPerSecond = 5f,
                totalItemsProcessed = i,
            )
        }

        val data = liveOf(samples, nowMillis = 219_000L).shouldNotBeNull()

        data.byteSpeeds.shouldNotBeNull().forEach { it shouldBe 0f }
    }

    // ============ NO GRAPH ============

    @Test
    fun `too few samples produce no data`() {
        val samples = secondly(count = 9)

        PerformanceGraphData.from(history(samples), PerformanceGraphScope.OVERVIEW, startTime) shouldBe null
    }

    @Test
    fun `an operation that never advanced is still plottable against time`() {
        val samples = (0 until 20).map { i ->
            sample(atMillis = i * 1000L, itemsPerSecond = 5f, totalItemsProcessed = 0)
        }

        val data = overviewOf(samples).shouldNotBeNull()

        data.elapsedSeconds shouldBe (0 until 20).map { it.toFloat() }
    }

    @Test
    fun `samples recorded within one plot step are not plottable`() {
        val samples = (0 until 20).map { i ->
            sample(atMillis = i.toLong(), itemsPerSecond = 5f, totalItemsProcessed = i)
        }

        overviewOf(samples) shouldBe null
    }

    // ============ RECENT SPEEDS ============

    @Test
    fun `recent speeds average the raw samples`() {
        val samples = secondly(
            count = 20,
            bytesPerSecond = { (it + 1) * 1_000_000L },
            itemsPerSecond = { (it + 1).toFloat() },
        )

        val data = overviewOf(samples).shouldNotBeNull()

        // (1 + 2 + … + 20) / 20 = 10.5
        data.recentBytesPerSecond shouldBe 10_500_000L
        data.recentItemsPerSecond shouldBe (10.5f plusOrMinus 0.001f)
    }

    @Test
    fun `recent speeds only cover the last 30 samples`() {
        val samples = secondly(
            count = 40,
            bytesPerSecond = { if (it < 10) 500_000_000L else 1_000_000L },
            itemsPerSecond = { if (it < 10) 100f else 2f },
        )

        val data = overviewOf(samples).shouldNotBeNull()

        data.recentBytesPerSecond shouldBe 1_000_000L
        data.recentItemsPerSecond shouldBe (2f plusOrMinus 0.001f)
    }

    @Test
    fun `recent speeds ignore the filtering and smoothing of the series`() {
        // 100ms apart, so ten samples collapse onto three plot steps
        val samples = (0 until 10).map { i ->
            sample(atMillis = i * 100L, itemsPerSecond = (i + 1) * 10f, totalItemsProcessed = i)
        }

        val data = overviewOf(samples).shouldNotBeNull()

        data.itemSpeeds shouldHaveSize 3
        // (10 + 20 + … + 100) / 10, over every sample rather than the three plotted points
        data.recentItemsPerSecond shouldBe (55f plusOrMinus 0.001f)
    }

    // ============ CROPPING ============

    @Test
    fun `the live window crops the samples that scrolled out`() {
        val samples = secondly(count = 201)

        val cropped = liveOf(samples, nowMillis = 200_000L).shouldNotBeNull()
        val full = overviewOf(samples).shouldNotBeNull()

        cropped.elapsedSeconds shouldHaveSize 122
        full.elapsedSeconds shouldHaveSize 201
        cropped.elapsedSeconds shouldBe full.elapsedSeconds.takeLast(122)
    }

    @Test
    fun `a window shorter than the samples still smooths from before its edge`() {
        val samples = secondly(count = 201, itemsPerSecond = { (it + 1).toFloat() })

        val cropped = liveOf(samples, nowMillis = 200_000L).shouldNotBeNull()
        val full = overviewOf(samples).shouldNotBeNull()

        // Smoothing runs over the raw history, so a cropped point keeps the value it always had
        cropped.itemSpeeds shouldBe full.itemSpeeds.takeLast(122)
    }

    @Test
    fun `the smoothing window is measured in time, not in samples`() {
        val dense = (0 until 20).map { i ->
            sample(atMillis = i * 500L, itemsPerSecond = if (i == 0) 100f else 0f)
        }

        val data = PerformanceGraphData.from(
            history = history(dense),
            scope = PerformanceGraphScope.OVERVIEW,
            now = startTime + 10.seconds,
        ).shouldNotBeNull()

        // Four samples still hold the 100/s spike, the fifth is a full two seconds past it
        data.itemSpeeds[3] shouldBe (25f plusOrMinus 0.001f)
        data.itemSpeeds[4] shouldBe (0f plusOrMinus 0.001f)
    }
}
